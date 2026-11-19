//! Local-only typed detail projections. No source requests or schema changes.
use crate::{CoreError, LadiesCore, source::filter::{EvidenceOption, evidence_option}};
use rusqlite::{OptionalExtension, params};
use serde_json::Value;

#[derive(Debug, Clone, uniffi::Record)]
pub struct ProfileField {
    pub label: String,
    pub values: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum EvidenceStatus {
    Yes,
    No,
    Unknown,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FilterEvidence {
    pub group: String,
    pub label: String,
    pub status: EvidenceStatus,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ProfileFacts {
    pub traits: Vec<ProfileField>,
    pub services: Vec<ProfileField>,
    pub opening_times: Vec<ProfileField>,
    pub filters: Vec<FilterEvidence>,
    pub important: Vec<FilterEvidence>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PersonListing {
    pub listing_id: i64,
    pub external_id: String,
    pub status: String,
    pub last_seen_at: String,
    pub url: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PersonDetail {
    pub private_note: Option<String>,
    pub listings: Vec<PersonListing>,
    pub known_names: u32,
    pub known_cities: u32,
    pub archived_images: u32,
    pub shared_images: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SnapshotDifference {
    pub field: String,
    pub before: Option<String>,
    pub after: Option<String>,
}

fn fields(raw: Option<String>) -> Result<Vec<ProfileField>, CoreError> {
    let Some(raw) = raw else {
        return Ok(Vec::new());
    };
    let value: Value = serde_json::from_str(&raw)
        .map_err(|e| CoreError::Storage(format!("Invalid profile field JSON: {e}")))?;
    let Some(object) = value.as_object() else {
        return Ok(Vec::new());
    };
    Ok(object
        .iter()
        .filter_map(|(label, value)| {
            let values: Vec<String> = match value {
                Value::Array(items) => items
                    .iter()
                    .filter_map(Value::as_str)
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .map(str::to_owned)
                    .collect(),
                Value::String(s) if !s.trim().is_empty() => vec![s.trim().into()],
                _ => vec![],
            };
            (!values.is_empty()).then(|| ProfileField {
                label: label.clone(),
                values,
            })
        })
        .collect())
}

fn normalize(s: &str) -> String {
    s.split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase()
}

fn evidence(option: &EvidenceOption, fields: &[&ProfileField]) -> EvidenceStatus {
    let mut yes = false;
    let mut no = false;
    for alias in &option.aliases {
        let target = normalize(alias);
        let negative = [
            format!("{target}: nein"),
            format!("kein {target}"),
            format!("ohne {target}"),
            format!("{target} nein"),
        ];
        for field in fields {
            let label = normalize(&field.label);
            for raw in &field.values {
                let value = normalize(raw);
                yes |= value == target
                    || (label == target && matches!(value.as_str(), "ja" | "yes"));
                no |= negative.contains(&value)
                    || (label == target && matches!(value.as_str(), "nein" | "no"));
            }
        }
    }
    match (yes, no) {
        (true, false) => EvidenceStatus::Yes,
        (false, true) => EvidenceStatus::No,
        _ => EvidenceStatus::Unknown,
    }
}

/// Structural groups come from the parser (trait/standard attributes/opening times).
/// Unknown headings and unsupported live-time inferences deliberately remain unknown.
fn in_trait_group(group: &str, label: &str) -> bool {
    let label = normalize(label);
    match group {
        "figur" => matches!(label.as_str(), "figur" | "körperbau"),
        "busen" => matches!(label.as_str(), "busen" | "oberweite" | "brustgröße" | "brustgroesse" | "brust"),
        "haarfarbe" => matches!(label.as_str(), "haare" | "haarfarbe"),
        "haarlaenge" => matches!(label.as_str(), "haarlänge" | "haarlaenge"),
        "augenfarbe" => matches!(label.as_str(), "augen" | "augenfarbe"),
        "koerperschmuck" => matches!(label.as_str(), "körperschmuck" | "koerperschmuck"),
        "typ" | "geschlecht" | "intimbereich" | "hautton" | "sonstiges" => label == group,
        _ => false,
    }
}

fn project_evidence(
    option: EvidenceOption,
    traits: &[ProfileField],
    services: &[ProfileField],
    times: &[ProfileField],
) -> FilterEvidence {
    let scoped: Vec<&ProfileField> = match option.group.as_str() {
        "service" => services.iter().filter(|field| {
            !matches!(normalize(&field.label).as_str(), "treffpunkt" | "treff" | "location")
        }).collect(),
        "treff" => traits.iter().chain(services).filter(|field| {
            matches!(normalize(&field.label).as_str(), "treffpunkt" | "treff" | "treffen")
        }).collect(),
        "location" => traits.iter().chain(services).filter(|field| {
            normalize(&field.label) == "location"
        }).collect(),
        "zeiten" => times.iter().collect(),
        group => traits.iter().filter(|field| in_trait_group(group, &field.label)).collect(),
    };
    FilterEvidence {
        status: evidence(&option, &scoped),
        group: option.group,
        label: option.label,
    }
}

pub(crate) fn facts(
    core: &LadiesCore,
    snapshot_id: i64,
    filters_json: &str,
) -> Result<ProfileFacts, CoreError> {
    let db = core.connection()?;
    let (traits, services, times): (Option<String>, Option<String>, Option<String>) = db.query_row(
        "SELECT traits_json,services_json,opening_times_json FROM listing_snapshots WHERE snapshot_id=?1",
        [snapshot_id], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?)),
    )?;
    let traits = fields(traits)?;
    let services = fields(services)?;
    let opening_times = fields(times)?;
    let selected: std::collections::BTreeMap<String, Vec<String>> = serde_json::from_str(filters_json)
        .map_err(|e| CoreError::InvalidInput(format!("Invalid selected filters: {e}")))?;
    let mut choices = std::collections::BTreeMap::new();
    for (group, values) in selected {
        for value in values {
            let option = evidence_option(&group, &value);
            choices.insert((option.group.clone(), option.label.clone()), option);
        }
    }
    let filters = choices
        .into_values()
        .map(|option| project_evidence(option, &traits, &services, &opening_times))
        .collect();
    let important = ["GV", "AV", "ZK", "Girlfriend-Sex"]
        .into_iter()
        .map(|value| project_evidence(evidence_option("service", value), &traits, &services, &opening_times))
        .collect();
    Ok(ProfileFacts {
        traits,
        services,
        opening_times,
        filters,
        important,
    })
}

pub(crate) fn person_detail(core: &LadiesCore, person_id: i64) -> Result<PersonDetail, CoreError> {
    let db = core.connection()?;
    let private_note = db
        .query_row(
            "SELECT private_note FROM person_user_state WHERE person_id=?1",
            [person_id],
            |r| r.get(0),
        )
        .optional()?
        .ok_or(CoreError::PersonNotFound(person_id))?;
    let mut query = db.prepare(
        "SELECT listing_id,external_listing_id,current_status,last_seen_at,current_url
         FROM listings WHERE person_id=?1 ORDER BY last_seen_at DESC,listing_id DESC",
    )?;
    let listings = query
        .query_map([person_id], |r| {
            let raw: Option<String> = r.get(4)?;
            Ok(PersonListing {
                listing_id: r.get(0)?,
                external_id: r.get(1)?,
                status: r.get(2)?,
                last_seen_at: r.get(3)?,
                url: raw.and_then(|url| crate::source::validated_profile_url(&url)),
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    let (known_names, known_cities) = db.query_row(
        "SELECT count(DISTINCT nullif(trim(s.name),'')),count(DISTINCT nullif(trim(s.city),''))
         FROM listing_snapshots s JOIN listings l ON l.listing_id=s.listing_id WHERE l.person_id=?1",
        [person_id], |r| Ok((r.get(0)?,r.get(1)?)),
    )?;
    let (archived_images, shared_images) = db.query_row(
        "SELECT count(DISTINCT m.media_asset_id), count(DISTINCT CASE WHEN EXISTS(
             SELECT 1 FROM snapshot_media other JOIN listing_snapshots os ON os.snapshot_id=other.snapshot_id
             JOIN listings ol ON ol.listing_id=os.listing_id
             WHERE other.media_asset_id=m.media_asset_id AND ol.person_id<>?1
         ) THEN m.media_asset_id END)
         FROM snapshot_media m JOIN listing_snapshots s ON s.snapshot_id=m.snapshot_id
         JOIN listings l ON l.listing_id=s.listing_id
         WHERE l.person_id=?1 AND m.kind='IMAGE' AND m.media_asset_id IS NOT NULL",
        [person_id], |r| Ok((r.get(0)?,r.get(1)?)),
    )?;
    Ok(PersonDetail {
        private_note,
        listings,
        known_names,
        known_cities,
        archived_images,
        shared_images,
    })
}

pub(crate) fn person_note(
    core: &LadiesCore,
    listing_id: i64,
    person_id: i64,
) -> Result<Option<String>, CoreError> {
    let db = core.connection()?;
    db.query_row(
        "SELECT u.private_note FROM person_user_state u JOIN listings l ON l.person_id=u.person_id
         WHERE l.listing_id=?1 AND u.person_id=?2",
        params![listing_id, person_id],
        |r| r.get(0),
    )
    .optional()?
    .ok_or_else(|| CoreError::IdentityConflict("Note owner changed; reopen profile".into()))
}

pub(crate) fn save_note(
    core: &LadiesCore,
    listing_id: i64,
    person_id: i64,
    expected: Option<String>,
    note: Option<String>,
) -> Result<(), CoreError> {
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    let owner: i64 = tx
        .query_row(
            "SELECT person_id FROM listings WHERE listing_id=?1",
            [listing_id],
            |r| r.get(0),
        )
        .optional()?
        .ok_or(CoreError::ListingNotFound(listing_id))?;
    if owner != person_id {
        return Err(CoreError::IdentityConflict(
            "Note owner changed; reopen profile".into(),
        ));
    }
    let note = note.filter(|s| !s.trim().is_empty());
    let changed = tx.execute(
        "UPDATE person_user_state SET private_note=?1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
         WHERE person_id=?2 AND private_note IS ?3", params![note,person_id,expected],
    )?;
    if changed != 1 {
        return Err(CoreError::IdentityConflict(
            "Note has changed; reload before saving".into(),
        ));
    }
    tx.commit()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Preference;

    #[test]
    fn explicit_evidence_and_missing_or_conflicting_values() {
        let rows = fields(Some(
            r#"{"Haare":["Blond"],"Augenfarbe":["blau","blau: nein"],"Figur":["normal"],"Sonstiges":["kein Nichtraucher"]}"#.into(),
        )).unwrap();
        let services = fields(Some(r#"{"Service":["blond"]}"#.into())).unwrap();
        let status = |group, value| {
            project_evidence(evidence_option(group, value), &rows, &services, &[]).status
        };
        assert_eq!(status("haarfarbe", "blond"), EvidenceStatus::Yes);
        assert_eq!(status("augenfarbe", "blau"), EvidenceStatus::Unknown);
        assert_eq!(status("figur", "mittel"), EvidenceStatus::Yes);
        assert_eq!(status("sonstiges", "Nichtraucher"), EvidenceStatus::No);
        assert_eq!(status("augenfarbe", "braun"), EvidenceStatus::Unknown);
        assert_eq!(status("haarfarbe", "schwarz"), EvidenceStatus::Unknown);
        assert_eq!(status("service", "blond"), EvidenceStatus::Unknown);
        assert_eq!(status("zeiten", "jetzt geöffnet"), EvidenceStatus::Unknown);
    }

    #[test]
    fn facts_listings_and_snapshot_comparison_use_only_stored_evidence() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(None).unwrap();
        let other = core.create_person(None).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "11".into(), None)
            .unwrap();
        let second = core
            .add_listing(person, None, "LADIES".into(), "12".into(), None)
            .unwrap();
        let (before, after, unrelated) = {
            let db = core.connection().unwrap();
            db.execute(
                "INSERT INTO listing_snapshots (listing_id,fingerprint,name,city,traits_json)
                 VALUES (?1,'before','Fixture A','City A',?2)",
                params![listing, r#"{"Haare":["Blond"],"Augenfarbe":["kein blau"]}"#],
            )
            .unwrap();
            let before = db.last_insert_rowid();
            db.execute(
                "INSERT INTO listing_snapshots (listing_id,fingerprint,name,city,traits_json)
                 VALUES (?1,'after','Fixture B','City A',?2)",
                params![listing, r#"{"Haare":["Blond"],"Augenfarbe":["kein blau"]}"#],
            )
            .unwrap();
            let after = db.last_insert_rowid();
            db.execute(
                "INSERT INTO listing_snapshots (listing_id,fingerprint,name,city)
                 VALUES (?1,'second','Fixture B','City B')",
                [second],
            )
            .unwrap();
            (before, after, db.last_insert_rowid())
        };
        let projected = facts(
            &core,
            before,
            r#"{"haarfarbe":["blond","schwarz"],"augenfarbe":["blau"]}"#,
        ).unwrap();
        assert_eq!(projected.traits.len(), 2);
        assert_eq!(projected.filters[0].status, EvidenceStatus::No);
        assert_eq!(projected.filters[1].status, EvidenceStatus::Yes);
        assert_eq!(projected.filters[2].status, EvidenceStatus::Unknown);
        assert!(facts(&core, -1, "{}").is_err());
        let details = person_detail(&core, person).unwrap();
        assert_eq!(details.listings.len(), 2);
        assert_eq!(details.known_names, 2);
        assert_eq!(details.known_cities, 2);
        let changes = crate::history::compare(&core, person, before, after).unwrap();
        assert_eq!(changes.len(), 1);
        assert_eq!(changes[0].field, "name");
        assert!(crate::history::compare(&core, other, before, after).is_err());
        assert!(crate::history::compare(&core, person, before, unrelated).is_err());
        assert!(crate::history::compare(&core, person, before, -1).is_err());
    }

    #[test]
    fn note_cas_preserves_other_fields_and_rejects_wrong_owner() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(None).unwrap();
        let other = core.create_person(None).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "1".into(), None)
            .unwrap();
        core.set_preference(person, Preference::Like).unwrap();
        core.set_favorite(person, true).unwrap();
        save_note(&core, listing, person, None, Some("First".into())).unwrap();
        assert!(save_note(&core, listing, person, None, Some("Stale".into())).is_err());
        assert!(save_note(&core, listing, other, None, Some("Wrong".into())).is_err());
        assert_eq!(
            person_detail(&core, person)
                .unwrap()
                .private_note
                .as_deref(),
            Some("First")
        );
        save_note(
            &core,
            listing,
            person,
            Some("First".into()),
            Some(" ".into()),
        )
        .unwrap();
        assert!(person_detail(&core, person).unwrap().private_note.is_none());
        let state = core.get_person(person).unwrap().unwrap();
        assert!(state.favorite);
        assert_eq!(state.preference, Preference::Like);
    }
}
