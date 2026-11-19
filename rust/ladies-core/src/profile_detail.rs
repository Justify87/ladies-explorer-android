//! Local-only typed detail projections. No source requests or schema changes.
use rusqlite::{OptionalExtension, params};
use serde_json::Value;
use crate::{CoreError, LadiesCore};

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
    pub label: String,
    pub status: EvidenceStatus,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ProfileFacts {
    pub traits: Vec<ProfileField>,
    pub services: Vec<ProfileField>,
    pub opening_times: Vec<ProfileField>,
    pub filters: Vec<FilterEvidence>,
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
    let Some(raw) = raw else { return Ok(Vec::new()) };
    let value: Value = serde_json::from_str(&raw)
        .map_err(|e| CoreError::Storage(format!("Invalid profile field JSON: {e}")))?;
    let Some(object) = value.as_object() else { return Ok(Vec::new()) };
    Ok(object.iter().filter_map(|(label, value)| {
        let values: Vec<String> = match value {
            Value::Array(items) => items.iter().filter_map(Value::as_str).map(str::trim)
                .filter(|s| !s.is_empty()).map(str::to_owned).collect(),
            Value::String(s) if !s.trim().is_empty() => vec![s.trim().into()],
            _ => vec![],
        };
        (!values.is_empty()).then(|| ProfileField { label: label.clone(), values })
    }).collect())
}

fn normalize(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase()
}

fn evidence(target: &str, fields: &[ProfileField]) -> EvidenceStatus {
    let target = normalize(target);
    let mut yes = false;
    let mut no = false;
    for field in fields {
        let label = normalize(&field.label);
        for raw in &field.values {
            let value = normalize(raw);
            yes |= value == target || (label == target && matches!(value.as_str(), "ja" | "yes"));
            no |= value == format!("kein {target}") || value == format!("ohne {target}")
                || value == format!("{target} nein") || (label == target && matches!(value.as_str(), "nein" | "no"));
        }
    }
    match (yes, no) {
        (true, false) => EvidenceStatus::Yes,
        (false, true) => EvidenceStatus::No,
        _ => EvidenceStatus::Unknown,
    }
}

pub(crate) fn facts(core: &LadiesCore, snapshot_id: i64, filters_json: &str) -> Result<ProfileFacts, CoreError> {
    let db = core.connection()?;
    let (traits, services, times): (Option<String>, Option<String>, Option<String>) = db.query_row(
        "SELECT traits_json,services_json,opening_times_json FROM listing_snapshots WHERE snapshot_id=?1",
        [snapshot_id], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?)),
    )?;
    let traits = fields(traits)?;
    let services = fields(services)?;
    let opening_times = fields(times)?;
    let all: Vec<_> = traits.iter().chain(&services).chain(&opening_times).cloned().collect();
    let selected: Value = serde_json::from_str(filters_json)
        .map_err(|e| CoreError::InvalidInput(format!("Invalid selected filters: {e}")))?;
    let mut labels = std::collections::BTreeSet::new();
    if let Some(groups) = selected.as_object() {
        for values in groups.values().filter_map(Value::as_array) {
            labels.extend(values.iter().filter_map(Value::as_str).filter(|s| !s.trim().is_empty()).map(str::to_owned));
        }
    }
    let filters = labels.into_iter().map(|label| FilterEvidence { status: evidence(&label, &all), label }).collect();
    Ok(ProfileFacts { traits, services, opening_times, filters })
}

pub(crate) fn person_detail(core: &LadiesCore, person_id: i64) -> Result<PersonDetail, CoreError> {
    let db = core.connection()?;
    let private_note = db.query_row(
        "SELECT private_note FROM person_user_state WHERE person_id=?1",
        [person_id], |r| r.get(0),
    ).optional()?.ok_or(CoreError::PersonNotFound(person_id))?;
    let mut query = db.prepare(
        "SELECT listing_id,external_listing_id,current_status,last_seen_at,current_url
         FROM listings WHERE person_id=?1 ORDER BY last_seen_at DESC,listing_id DESC",
    )?;
    let listings = query.query_map([person_id], |r| {
        let raw: Option<String> = r.get(4)?;
        Ok(PersonListing { listing_id:r.get(0)?, external_id:r.get(1)?, status:r.get(2)?,
            last_seen_at:r.get(3)?, url:raw.and_then(|url| crate::source::validated_profile_url(&url)) })
    })?.collect::<rusqlite::Result<Vec<_>>>()?;
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
    Ok(PersonDetail { private_note, listings, known_names, known_cities, archived_images, shared_images })
}

pub(crate) fn save_note(core: &LadiesCore, listing_id: i64, person_id: i64, expected: Option<String>, note: Option<String>) -> Result<(), CoreError> {
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    let owner: i64 = tx.query_row("SELECT person_id FROM listings WHERE listing_id=?1", [listing_id], |r| r.get(0))
        .optional()?.ok_or(CoreError::ListingNotFound(listing_id))?;
    if owner != person_id {
        return Err(CoreError::IdentityConflict("Note owner changed; reopen profile".into()));
    }
    let note = note.filter(|s| !s.trim().is_empty());
    let changed = tx.execute(
        "UPDATE person_user_state SET private_note=?1,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
         WHERE person_id=?2 AND private_note IS ?3", params![note,person_id,expected],
    )?;
    if changed != 1 {
        return Err(CoreError::IdentityConflict("Note has changed; reload before saving".into()));
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
        let rows = fields(Some(r#"{"A":["Ja"],"B":["Nein"],"C":["Ja","Nein"],"D":["value"]}"#.into())).unwrap();
        assert_eq!(evidence("A",&rows),EvidenceStatus::Yes);
        assert_eq!(evidence("B",&rows),EvidenceStatus::No);
        assert_eq!(evidence("C",&rows),EvidenceStatus::Unknown);
        assert_eq!(evidence("missing",&rows),EvidenceStatus::Unknown);
        assert_eq!(evidence("value",&rows),EvidenceStatus::Yes);
    }

    #[test]
    fn note_cas_preserves_other_fields_and_rejects_wrong_owner() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(None).unwrap();
        let other = core.create_person(None).unwrap();
        let listing = core.add_listing(person,None,"LADIES".into(),"1".into(),None).unwrap();
        core.set_preference(person, Preference::Like).unwrap();
        core.set_favorite(person,true).unwrap();
        save_note(&core,listing,person,None,Some("First".into())).unwrap();
        assert!(save_note(&core,listing,person,None,Some("Stale".into())).is_err());
        assert!(save_note(&core,listing,other,None,Some("Wrong".into())).is_err());
        assert_eq!(person_detail(&core,person).unwrap().private_note.as_deref(),Some("First"));
        save_note(&core,listing,person,Some("First".into()),Some(" ".into())).unwrap();
        assert!(person_detail(&core,person).unwrap().private_note.is_none());
        let state=core.get_person(person).unwrap().unwrap();
        assert!(state.favorite);
        assert_eq!(state.preference,Preference::Like);
    }
}
