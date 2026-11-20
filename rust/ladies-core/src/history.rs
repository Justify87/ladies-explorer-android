use rusqlite::{Connection, params};
use serde_json::{Value, json};

use crate::{CoreError, LadiesCore};

#[derive(Debug, Clone, uniffi::Record)]
pub struct ChangeRecord {
    pub change_event_id: i64,
    pub listing_id: Option<i64>,
    pub event_type: String,
    pub field_key: Option<String>,
    pub old_json: Option<String>,
    pub new_json: Option<String>,
    pub old_display: Option<String>,
    pub new_display: Option<String>,
    pub created_at: String,
    pub from_snapshot_id: Option<i64>,
    pub to_snapshot_id: Option<i64>,
}

const TRACKED: &[(&str, &str)] = &[
    ("name", "name"),
    ("city", "city"),
    ("postal_code", "postal_code"),
    ("hidden_location", "hidden_location"),
    ("category", "category"),
    ("description", "description"),
    ("published_at", "published_at"),
    ("external_profile_id", "external_profile_id"),
    ("canonical_id", "canonical_id"),
    ("phone_display", "phone_display"),
    ("traits_json", "traits_json"),
    ("services_json", "services_json"),
    ("opening_times_json", "opening_times_json"),
];

/// Rendering-only projection; never changes persisted history.
fn readable(raw: Option<&str>) -> Option<String> {
    let raw = raw?;
    fn display(value: &Value) -> String {
        match value {
            Value::Null => String::new(),
            Value::String(text) => text.clone(),
            Value::Bool(value) => if *value { "Ja" } else { "Nein" }.into(),
            Value::Number(number) => number.to_string(),
            Value::Array(items) => items
                .iter()
                .map(display)
                .filter(|s| !s.is_empty())
                .collect::<Vec<_>>()
                .join("\n"),
            Value::Object(fields) => fields
                .iter()
                .filter_map(|(key, value)| {
                    let value = display(value);
                    (!value.is_empty()).then(|| format!("{key}: {value}"))
                })
                .collect::<Vec<_>>()
                .join("\n"),
        }
    }
    let visible = serde_json::from_str::<Value>(raw)
        .map(|value| display(&value))
        .unwrap_or_else(|_| raw.to_owned());
    (!visible.is_empty()).then_some(visible)
}

fn field(db: &Connection, snapshot: i64, column: &str) -> Result<Option<String>, CoreError> {
    // Only internal allowlisted columns reach this statement.
    Ok(db.query_row(
        &format!("SELECT {column} FROM listing_snapshots WHERE snapshot_id=?1"),
        [snapshot],
        |row| row.get(0),
    )?)
}

fn as_json(value: Option<String>, structured: bool) -> Result<Option<String>, CoreError> {
    value
        .map(|text| {
            let value = if structured {
                serde_json::from_str::<Value>(&text)
                    .map_err(|e| CoreError::Storage(format!("Invalid stored snapshot JSON: {e}")))?
            } else {
                json!(text)
            };
            Ok(value.to_string())
        })
        .transpose()
}

fn media(db: &Connection, snapshot: i64) -> Result<Value, CoreError> {
    let mut query = db.prepare(
        "SELECT kind, role, remote_url, remote_variant, revision_token
         FROM snapshot_media WHERE snapshot_id=?1 ORDER BY position, snapshot_media_id",
    )?;
    let rows = query.query_map([snapshot], |row| {
        Ok(json!({
            "kind": row.get::<_, String>(0)?,
            "role": row.get::<_, String>(1)?,
            "url": row.get::<_, String>(2)?,
            "variant": row.get::<_, Option<String>>(3)?,
            "revision": row.get::<_, Option<String>>(4)?,
        }))
    })?;
    Ok(Value::Array(rows.collect::<rusqlite::Result<Vec<_>>>()?))
}

// First advert is not a relisting; apply chronology to snapshots and identity confirmation.
fn later_listing(db: &Connection, listing_id: i64) -> Result<bool, CoreError> {
    Ok(db.query_row(
        "SELECT EXISTS(SELECT 1 FROM listings l JOIN listings older ON older.person_id=l.person_id
          WHERE l.listing_id=?1 AND older.listing_id!=l.listing_id
            AND (julianday(older.first_seen_at)<julianday(l.first_seen_at)
              OR (julianday(older.first_seen_at)=julianday(l.first_seen_at)
                  AND older.listing_id<l.listing_id)))",
        [listing_id],
        |r| r.get(0),
    )?)
}

pub(crate) fn reconcile_relistings(db: &Connection, person_id: i64) -> Result<(), CoreError> {
    let mut stmt = db.prepare(
        "SELECT l.listing_id FROM listings l WHERE l.person_id=?1 AND l.current_snapshot_id IS NOT NULL",
    )?;
    let listings = stmt
        .query_map([person_id], |r| r.get::<_, i64>(0))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    for listing in listings {
        if !later_listing(db, listing)? {
            continue;
        }
        let exists: bool = db.query_row(
            "SELECT EXISTS(SELECT 1 FROM change_events WHERE listing_id=?1
                           AND event_type='NEW_LISTING_FOR_PERSON')",
            [listing],
            |r| r.get(0),
        )?;
        if exists {
            continue;
        }
        // Reclassify native events without changing timestamps/snapshots; preserve imported provenance.
        let updated = db.execute(
            "UPDATE change_events SET event_type='NEW_LISTING_FOR_PERSON',importance='HIGH'
             WHERE person_id=?1 AND listing_id=?2 AND event_type='PROFILE_CREATED'",
            params![person_id, listing],
        )?;
        if updated == 0 {
            db.execute(
                "INSERT INTO change_events(person_id,listing_id,to_snapshot_id,event_type,importance,created_at)
                 SELECT ?1,?2,s.snapshot_id,'NEW_LISTING_FOR_PERSON','HIGH',s.captured_at
                 FROM listing_snapshots s WHERE s.listing_id=?2
                 ORDER BY julianday(s.captured_at),s.snapshot_id LIMIT 1",
                params![person_id, listing],
            )?;
        }
    }
    Ok(())
}

pub(crate) fn record_transition(
    db: &Connection,
    person_id: i64,
    listing_id: i64,
    previous: Option<i64>,
    current: i64,
) -> Result<(), CoreError> {
    if previous == Some(current) {
        return Ok(());
    }
    let owner: i64 = db.query_row(
        "SELECT person_id FROM listings WHERE listing_id=?1",
        [listing_id],
        |row| row.get(0),
    )?;
    if owner != person_id {
        return Err(CoreError::IdentityConflict(
            "Snapshot listing owner changed".into(),
        ));
    }
    if previous.is_none() {
        let relisting = later_listing(db, listing_id)?;
        let (event, importance) = if relisting {
            ("NEW_LISTING_FOR_PERSON", "HIGH")
        } else {
            ("PROFILE_CREATED", "LOW")
        };
        db.execute(
            "INSERT INTO change_events
             (person_id, listing_id, from_snapshot_id, to_snapshot_id,event_type,importance)
             SELECT ?1,?2,NULL,?3,?4,?5
             WHERE NOT EXISTS(SELECT 1 FROM change_events WHERE listing_id=?2 AND event_type=?4)",
            params![person_id, listing_id, current, event, importance],
        )?;
        return Ok(());
    }
    let previous = previous.expect("checked above");
    for (key, column) in TRACKED {
        let old = field(db, previous, column)?;
        let new = field(db, current, column)?;
        let structured = key.ends_with("_json");
        let (old_json, new_json) = (as_json(old, structured)?, as_json(new, structured)?);
        if old_json != new_json {
            db.execute(
                "INSERT INTO change_events
                 (person_id, listing_id, from_snapshot_id, to_snapshot_id,
                  event_type, field_key, old_json, new_json, importance)
                 VALUES (?1,?2,?3,?4,'FIELD_CHANGED',?5,?6,?7,?8)",
                params![
                    person_id,
                    listing_id,
                    previous,
                    current,
                    key,
                    old_json,
                    new_json,
                    if *key == "phone_display" || *key == "external_profile_id" {
                        "HIGH"
                    } else {
                        "MEDIUM"
                    }
                ],
            )?;
        }
    }
    let before = media(db, previous)?;
    let after = media(db, current)?;
    if before != after {
        db.execute(
            "INSERT INTO change_events
             (person_id, listing_id, from_snapshot_id, to_snapshot_id,
              event_type, field_key, old_json, new_json, importance)
             VALUES (?1,?2,?3,?4,'MEDIA_CHANGED','media',?5,?6,'MEDIUM')",
            params![
                person_id,
                listing_id,
                previous,
                current,
                before.to_string(),
                after.to_string()
            ],
        )?;
    }
    Ok(())
}

#[cfg(test)]
pub(crate) fn list(
    core: &LadiesCore,
    person_id: i64,
    limit: u32,
) -> Result<Vec<ChangeRecord>, CoreError> {
    let db = core.connection()?;
    list_from(&db, person_id, limit)
}

pub(crate) fn list_from(
    db: &Connection,
    person_id: i64,
    limit: u32,
) -> Result<Vec<ChangeRecord>, CoreError> {
    if !(1..=200).contains(&limit) {
        return Err(CoreError::InvalidInput(
            "History limit must be between 1 and 200".into(),
        ));
    }
    let exists: bool = db.query_row(
        "SELECT EXISTS(SELECT 1 FROM persons WHERE person_id=?1)",
        [person_id],
        |r| r.get(0),
    )?;
    if !exists {
        return Err(CoreError::PersonNotFound(person_id));
    }
    let mut query = db.prepare(
        "SELECT change_event_id, listing_id, event_type, field_key, old_json, new_json, created_at,
                from_snapshot_id, to_snapshot_id
         FROM change_events WHERE person_id=?1
         ORDER BY created_at DESC, change_event_id DESC LIMIT ?2",
    )?;
    let rows = query.query_map(params![person_id, limit], |row| {
        let old_json: Option<String> = row.get(4)?;
        let new_json: Option<String> = row.get(5)?;
        Ok(ChangeRecord {
            change_event_id: row.get(0)?,
            listing_id: row.get(1)?,
            event_type: row.get(2)?,
            field_key: row.get(3)?,
            old_display: readable(old_json.as_deref()),
            new_display: readable(new_json.as_deref()),
            old_json,
            new_json,
            created_at: row.get(6)?,
            from_snapshot_id: row.get(7)?,
            to_snapshot_id: row.get(8)?,
        })
    })?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

pub(crate) fn compare(
    core: &LadiesCore,
    person_id: i64,
    from_id: i64,
    to_id: i64,
) -> Result<Vec<crate::SnapshotDifference>, CoreError> {
    let db = core.connection()?;
    // Both snapshots must belong to the same listing and its current owner.
    let allowed: bool = db.query_row(
        "SELECT EXISTS(SELECT 1 FROM listing_snapshots a
         JOIN listing_snapshots b ON b.listing_id=a.listing_id
         JOIN listings l ON l.listing_id=a.listing_id
         WHERE a.snapshot_id=?1 AND b.snapshot_id=?2 AND l.person_id=?3)",
        params![from_id, to_id, person_id],
        |r| r.get(0),
    )?;
    if !allowed {
        return Err(CoreError::IdentityConflict(
            "Snapshots missing or not owned by this person/listing".into(),
        ));
    }
    let mut differences = Vec::new();
    for (key, column) in TRACKED {
        let before = as_json(field(&db, from_id, column)?, key.ends_with("_json"))?;
        let after = as_json(field(&db, to_id, column)?, key.ends_with("_json"))?;
        if before != after {
            differences.push(crate::SnapshotDifference {
                field: (*key).into(),
                before: readable(before.as_deref()),
                after: readable(after.as_deref()),
            });
        }
    }
    let before = media(&db, from_id)?;
    let after = media(&db, to_id)?;
    if before != after {
        differences.push(crate::SnapshotDifference {
            field: "media".into(),
            before: readable(Some(&before.to_string())),
            after: readable(Some(&after.to_string())),
        });
    }
    Ok(differences)
}

#[cfg(test)]
mod tests {
    use super::readable;

    #[test]
    fn history_projection_handles_structured_scalar_null_and_imported_plain_text() {
        assert_eq!(readable(Some(r#""Fixture""#)).as_deref(), Some("Fixture"));
        assert_eq!(readable(Some("null")), None);
        assert_eq!(
            readable(Some("imported plain")).as_deref(),
            Some("imported plain")
        );
        assert_eq!(
            readable(Some(r#"{"Merkmal":["A","B"]}"#)).as_deref(),
            Some("Merkmal: A\nB")
        );
    }
}
