//! Local-only detail projections; no source requests.
use crate::{
    CoreError, LadiesCore, SearchRequest,
    saved_search::{LocalVisibility, decode_definition, validate_local},
    source::{
        filter::{EvidenceOption, canonical_category, canonicalize_request, evidence_option},
        known_age,
    },
};
use rusqlite::{OptionalExtension, params};
use serde::Deserialize;
use serde_json::Value;
use std::collections::BTreeMap;

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
pub struct FilterMatchGroup {
    pub group: String,
    pub status: EvidenceStatus,
    pub options: Vec<FilterEvidence>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ProfileFacts {
    pub traits: Vec<ProfileField>,
    pub services: Vec<ProfileField>,
    pub opening_times: Vec<ProfileField>,
    pub filters: Vec<FilterMatchGroup>,
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
                yes |=
                    value == target || (label == target && matches!(value.as_str(), "ja" | "yes"));
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

/// Preserve unknown headings and unsupported live-time inferences as unknown.
fn in_trait_group(group: &str, label: &str) -> bool {
    let label = normalize(label);
    match group {
        "figur" => matches!(label.as_str(), "figur" | "körperbau"),
        "busen" => matches!(
            label.as_str(),
            "busen" | "oberweite" | "brustgröße" | "brustgroesse" | "brust"
        ),
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
        "service" => services
            .iter()
            .filter(|field| {
                !matches!(
                    normalize(&field.label).as_str(),
                    "treffpunkt" | "treff" | "location"
                )
            })
            .collect(),
        "treff" => traits
            .iter()
            .chain(services)
            .filter(|field| {
                matches!(
                    normalize(&field.label).as_str(),
                    "treffpunkt" | "treff" | "treffen"
                )
            })
            .collect(),
        "location" => traits
            .iter()
            .chain(services)
            .filter(|field| normalize(&field.label) == "location")
            .collect(),
        "zeiten" => times.iter().collect(),
        group => traits
            .iter()
            .filter(|field| in_trait_group(group, &field.label))
            .collect(),
    };
    FilterEvidence {
        status: evidence(&option, &scoped),
        group: option.group,
        label: option.label,
    }
}

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum MatchContext {
    Run {
        run_id: i64,
        owner_id: i64,
        local: LocalVisibility,
    },
    Saved {
        saved_id: String,
        owner_id: i64,
    },
}

#[derive(Deserialize)]
struct RunFilters {
    category: String,
    age_min: u32,
    age_max: u32,
    height_min: u32,
    height_max: u32,
    selected: BTreeMap<String, Vec<String>>,
}

struct MatchBasis {
    query: SearchRequest,
    local: LocalVisibility,
    video: Option<bool>,
    photo: Option<bool>,
    ai: Option<bool>,
}

fn match_basis(
    db: &rusqlite::Connection,
    context_json: &str,
    listing: i64,
    owner: i64,
) -> Result<Option<MatchBasis>, CoreError> {
    if context_json == "{}" {
        return Ok(None);
    }
    let context: MatchContext = serde_json::from_str(context_json)
        .map_err(|e| CoreError::InvalidInput(format!("Invalid profile search context: {e}")))?;
    match context {
        MatchContext::Run {
            run_id,
            owner_id,
            local,
        } if owner_id == owner => {
            validate_local(&local)?;
            let row = db
                .query_row(
                    "SELECT run.city,run.radius_km,run.filters_json,r.has_video,
                        r.thumbnail_url IS NOT NULL,r.has_ai_content
                 FROM search_runs run JOIN search_results r USING(search_run_id)
                 WHERE run.search_run_id=?1 AND r.listing_id=?2
                   AND run.finished_at IS NOT NULL AND run.city IS NOT NULL
                   AND run.radius_km IS NOT NULL AND run.filters_json IS NOT NULL",
                    params![run_id, listing],
                    |r| {
                        Ok((
                            r.get::<_, String>(0)?,
                            r.get::<_, u32>(1)?,
                            r.get::<_, String>(2)?,
                            r.get::<_, bool>(3)?,
                            r.get::<_, bool>(4)?,
                            r.get::<_, bool>(5)?,
                        ))
                    },
                )
                .optional()?;
            let Some((city, radius_km, raw, video, photo, ai)) = row else {
                return Ok(None);
            };
            let Ok(stored) = serde_json::from_str::<RunFilters>(&raw) else {
                return Ok(None);
            };
            let query = SearchRequest {
                city,
                radius_km,
                category: stored.category,
                age_min: stored.age_min,
                age_max: stored.age_max,
                height_min: stored.height_min,
                height_max: stored.height_max,
                filters_json: serde_json::to_string(&stored.selected)
                    .map_err(|e| CoreError::Storage(e.to_string()))?,
            };
            let Ok(query) = canonicalize_request(query) else {
                return Ok(None);
            };
            Ok(Some(MatchBasis {
                query,
                local,
                video: Some(video),
                photo: Some(photo),
                ai: Some(ai),
            }))
        }
        MatchContext::Saved { saved_id, owner_id } if owner_id == owner => {
            let row = db
                .query_row(
                    "SELECT s.filters_json,r.snapshot_json
                 FROM saved_searches s JOIN saved_search_results r USING(saved_search_id)
                 JOIN listings l ON l.source='LADIES' AND l.external_listing_id=r.result_key
                 WHERE s.saved_search_id=?1 AND l.listing_id=?2 AND s.last_run_at IS NOT NULL
                   AND r.present=1 AND r.visible=1",
                    params![saved_id, listing],
                    |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)),
                )
                .optional()?;
            let Some((definition, snapshot)) = row else {
                return Ok(None);
            };
            let Ok(definition) = decode_definition(&definition) else {
                return Ok(None);
            };
            let snapshot: Value =
                serde_json::from_str(&snapshot).map_err(|e| CoreError::Storage(e.to_string()))?;
            let photo = match snapshot.get("thumbnail_url") {
                Some(Value::Null) => Some(false),
                Some(Value::String(_)) => Some(true),
                _ => None,
            };
            Ok(Some(MatchBasis {
                query: definition.query,
                local: definition.local,
                video: snapshot.get("has_video").and_then(Value::as_bool),
                photo,
                ai: snapshot.get("has_ai_content").and_then(Value::as_bool),
            }))
        }
        _ => Ok(None),
    }
}

fn status(value: Option<bool>) -> EvidenceStatus {
    match value {
        Some(true) => EvidenceStatus::Yes,
        Some(false) => EvidenceStatus::No,
        None => EvidenceStatus::Unknown,
    }
}

fn match_group(group: String, options: Vec<FilterEvidence>) -> FilterMatchGroup {
    // The source combines choices within one group as alternatives.
    let status = if options
        .iter()
        .any(|option| option.status == EvidenceStatus::Yes)
    {
        EvidenceStatus::Yes
    } else if options
        .iter()
        .all(|option| option.status == EvidenceStatus::No)
    {
        EvidenceStatus::No
    } else {
        EvidenceStatus::Unknown
    };
    FilterMatchGroup {
        group,
        status,
        options,
    }
}

fn single_match(group: &str, label: String, value: Option<bool>) -> FilterMatchGroup {
    match_group(
        group.into(),
        vec![FilterEvidence {
            group: group.into(),
            label,
            status: status(value),
        }],
    )
}

fn known_height(traits: &[ProfileField]) -> Option<u32> {
    let mut heights = traits.iter().filter(|field| {
        matches!(
            normalize(&field.label).as_str(),
            "größe" | "groesse" | "körpergröße" | "koerpergroesse"
        )
    });
    let field = heights.next()?;
    if heights.next().is_some() || field.values.len() != 1 {
        return None;
    }
    let raw = normalize(&field.values[0]);
    let number = raw.strip_suffix("cm").unwrap_or(&raw).trim();
    if number.len() == 3 && number.bytes().all(|b| b.is_ascii_digit()) {
        return number.parse().ok();
    }
    let meters = raw.strip_suffix('m')?.trim();
    let (whole, fraction) = meters.split_once([',', '.'])?;
    if whole.len() != 1
        || fraction.len() != 2
        || !whole
            .bytes()
            .chain(fraction.bytes())
            .all(|b| b.is_ascii_digit())
    {
        return None;
    }
    Some(whole.parse::<u32>().ok()? * 100 + fraction.parse::<u32>().ok()?)
}

fn range_match(
    group: &str,
    min: u32,
    max: u32,
    unit: &str,
    observed: Option<u32>,
) -> FilterMatchGroup {
    let mut label = format!("{min}–{max} {unit}");
    if let Some(value) = observed {
        label.push_str(&format!(" · belegt: {value} {unit}"));
    }
    single_match(
        group,
        label,
        observed.map(|value| (min..=max).contains(&value)),
    )
}

fn local_matches(basis: &MatchBasis, preference: &str) -> Vec<FilterMatchGroup> {
    let local = &basis.local;
    let mut groups = Vec::new();
    let mut add = |key: &str, label: &str, rule: LocalVisibility, known: bool| {
        let result = known.then(|| {
            rule.allows(
                basis.video.unwrap_or(false),
                basis.photo.unwrap_or(false),
                basis.ai.unwrap_or(false),
                preference,
            )
        });
        groups.push(single_match(key, label.into(), result));
    };
    if local.video_only {
        add(
            "local_video",
            "Nur mit Video",
            LocalVisibility {
                video_only: true,
                ..Default::default()
            },
            basis.video.is_some(),
        );
    }
    if local.photos_only {
        add(
            "local_photo",
            "Nur mit Bildern",
            LocalVisibility {
                photos_only: true,
                ..Default::default()
            },
            basis.photo.is_some(),
        );
    }
    if local.hide_likes {
        add(
            "local_likes",
            "Likes ausblenden",
            LocalVisibility {
                hide_likes: true,
                ..Default::default()
            },
            true,
        );
    }
    if local.hide_dislikes {
        add(
            "local_dislikes",
            "Dislikes ausblenden",
            LocalVisibility {
                hide_dislikes: true,
                ..Default::default()
            },
            true,
        );
    }
    if matches!(local.image_mode.as_str(), "AI" | "NATURAL") {
        let label = if local.image_mode == "AI" {
            "KI-markiert"
        } else {
            "Ohne KI-Markierung"
        };
        add(
            "local_image",
            label,
            LocalVisibility {
                image_mode: local.image_mode.clone(),
                ..Default::default()
            },
            basis.ai.is_some(),
        );
    }
    groups
}

fn project_matches(
    basis: &MatchBasis,
    raw_traits: Option<&str>,
    traits: &[ProfileField],
    services: &[ProfileField],
    opening_times: &[ProfileField],
    category: Option<&str>,
    preference: &str,
) -> Result<Vec<FilterMatchGroup>, CoreError> {
    let query = &basis.query;
    let mut groups = Vec::new();
    // A verified search boundary is not evidence of a listing's individual distance.
    groups.push(single_match(
        "radius",
        format!(
            "{} · bis {} km (Einzelentfernung nicht belegt)",
            query.city, query.radius_km
        ),
        None,
    ));
    if !query.category.is_empty() {
        let observed = category.and_then(canonical_category);
        groups.push(single_match(
            "category",
            query.category.clone(),
            observed.map(|value| value == query.category.as_str()),
        ));
    }
    if query.age_min > 18 || query.age_max < 60 {
        let age = known_age(raw_traits).and_then(|value| value.parse().ok());
        groups.push(range_match(
            "age",
            query.age_min,
            query.age_max,
            "Jahre",
            age,
        ));
    }
    if query.height_min > 140 || query.height_max < 200 {
        groups.push(range_match(
            "height",
            query.height_min,
            query.height_max,
            "cm",
            known_height(traits),
        ));
    }
    let selected: BTreeMap<String, Vec<String>> = serde_json::from_str(&query.filters_json)
        .map_err(|e| CoreError::InvalidInput(format!("Invalid selected filters: {e}")))?;
    for (group, values) in selected {
        let options: Vec<_> = values
            .into_iter()
            .map(|value| {
                project_evidence(
                    evidence_option(&group, &value),
                    traits,
                    services,
                    opening_times,
                )
            })
            .collect();
        if !options.is_empty() {
            groups.push(match_group(group, options));
        }
    }
    groups.extend(local_matches(basis, preference));
    Ok(groups)
}

pub(crate) fn facts(
    core: &LadiesCore,
    snapshot_id: i64,
    context_json: &str,
) -> Result<ProfileFacts, CoreError> {
    let db = core.connection()?;
    let (raw_traits, services, times, listing, owner, current, category, preference): (
        Option<String>,
        Option<String>,
        Option<String>,
        i64,
        i64,
        Option<i64>,
        Option<String>,
        String,
    ) = db.query_row(
        "SELECT s.traits_json,s.services_json,s.opening_times_json,s.listing_id,
                    l.person_id,l.current_snapshot_id,s.category,u.preference
             FROM listing_snapshots s JOIN listings l USING(listing_id)
             JOIN person_user_state u ON u.person_id=l.person_id WHERE s.snapshot_id=?1",
        [snapshot_id],
        |r| {
            Ok((
                r.get(0)?,
                r.get(1)?,
                r.get(2)?,
                r.get(3)?,
                r.get(4)?,
                r.get(5)?,
                r.get(6)?,
                r.get(7)?,
            ))
        },
    )?;
    let traits = fields(raw_traits.clone())?;
    let services = fields(services)?;
    let opening_times = fields(times)?;
    let filters = if current == Some(snapshot_id) {
        match match_basis(&db, context_json, listing, owner)? {
            Some(basis) => project_matches(
                &basis,
                raw_traits.as_deref(),
                &traits,
                &services,
                &opening_times,
                category.as_deref(),
                &preference,
            )?,
            None => Vec::new(),
        }
    } else {
        Vec::new()
    };
    let important = ["GV", "AV", "ZK", "Girlfriend-Sex"]
        .into_iter()
        .map(|value| {
            project_evidence(
                evidence_option("service", value),
                &traits,
                &services,
                &opening_times,
            )
        })
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
    let exists: bool = db.query_row(
        "SELECT EXISTS(SELECT 1 FROM persons WHERE person_id=?1)",
        [person_id],
        |r| r.get(0),
    )?;
    if !exists {
        return Err(CoreError::PersonNotFound(person_id));
    }
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
        let run = {
            let db = core.connection().unwrap();
            db.execute(
                "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
                params![before, listing],
            )
            .unwrap();
            db.execute(
                "INSERT INTO search_runs(city,radius_km,filters_json,finished_at)
                 VALUES ('Fixture',20,?1,'2026-11-25T00:00:00Z')",
                [r#"{"category":"","age_min":25,"age_max":40,"height_min":150,"height_max":180,"selected":{"haarfarbe":["blond","schwarz"],"augenfarbe":["blau"]}}"#],
            ).unwrap();
            let run = db.last_insert_rowid();
            db.execute("INSERT INTO search_results(search_run_id,listing_id,position,has_video) VALUES (?1,?2,1,1)", params![run, listing]).unwrap();
            run
        };
        let context = |run_id, owner_id| {
            serde_json::json!({
                "kind":"run","run_id":run_id,"owner_id":owner_id,
                "local":LocalVisibility {video_only:true,photos_only:true,..Default::default()}
            })
            .to_string()
        };
        let projected = facts(&core, before, &context(run, person)).unwrap();
        assert_eq!(projected.traits.len(), 2);
        let group = |key| {
            projected
                .filters
                .iter()
                .find(|group| group.group == key)
                .unwrap()
        };
        assert_eq!(group("augenfarbe").status, EvidenceStatus::No);
        assert_eq!(group("haarfarbe").status, EvidenceStatus::Yes);
        assert_eq!(
            group("haarfarbe").options[1].status,
            EvidenceStatus::Unknown
        );
        assert_eq!(group("age").status, EvidenceStatus::Unknown);
        assert_eq!(group("height").status, EvidenceStatus::Unknown);
        assert_eq!(group("radius").status, EvidenceStatus::Unknown);
        assert_eq!(group("local_video").status, EvidenceStatus::Yes);
        assert_eq!(group("local_photo").status, EvidenceStatus::No);
        assert!(
            facts(&core, before, &context(run + 1, person))
                .unwrap()
                .filters
                .is_empty()
        );
        assert!(
            facts(&core, before, &context(run, other))
                .unwrap()
                .filters
                .is_empty()
        );
        assert!(
            facts(&core, after, &context(run, person))
                .unwrap()
                .filters
                .is_empty()
        );
        assert!(facts(&core, before, "{}").unwrap().filters.is_empty());
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
    fn saved_match_requires_visible_membership_and_preserves_missing_markers() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(None).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "11".into(), None)
            .unwrap();
        let mut request = SearchRequest::for_test();
        request.age_min = 25;
        request.age_max = 40;
        let id = crate::saved_search::save_with_local(
            &core,
            "Fixture".into(),
            request,
            LocalVisibility {
                video_only: true,
                photos_only: true,
                ..Default::default()
            },
        )
        .unwrap();
        let snapshot = {
            let db = core.connection().unwrap();
            db.execute(
                "INSERT INTO listing_snapshots (listing_id,fingerprint,traits_json)
                 VALUES (?1,'saved',?2)",
                params![listing, r#"{"Alter":["32 Jahre"]}"#],
            )
            .unwrap();
            let snapshot = db.last_insert_rowid();
            db.execute(
                "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
                params![snapshot, listing],
            )
            .unwrap();
            db.execute(
                "UPDATE saved_searches SET last_run_at='2000-01-01' WHERE saved_search_id=?1",
                [&id],
            )
            .unwrap();
            db.execute(
                "INSERT INTO saved_search_results
                 (saved_search_id,result_key,snapshot_json,position,present,visible)
                 VALUES (?1,'11','{}',0,1,1)",
                [&id],
            )
            .unwrap();
            snapshot
        };
        let context = serde_json::json!({
            "kind":"saved","saved_id":id,"owner_id":person
        })
        .to_string();
        let status = |key: &str| {
            facts(&core, snapshot, &context)
                .unwrap()
                .filters
                .into_iter()
                .find(|group| group.group == key)
                .unwrap()
                .status
        };
        assert_eq!(status("age"), EvidenceStatus::Yes);
        assert_eq!(status("local_video"), EvidenceStatus::Unknown);
        assert_eq!(status("local_photo"), EvidenceStatus::Unknown);
        {
            let db = core.connection().unwrap();
            db.execute(
                "UPDATE saved_search_results SET snapshot_json=?1 WHERE saved_search_id=?2",
                params![r#"{"has_video":true,"thumbnail_url":null}"#, id],
            )
            .unwrap();
        }
        assert_eq!(status("local_video"), EvidenceStatus::Yes);
        assert_eq!(status("local_photo"), EvidenceStatus::No);
        {
            let db = core.connection().unwrap();
            db.execute(
                "UPDATE saved_search_results SET visible=0 WHERE saved_search_id=?1",
                [&id],
            )
            .unwrap();
        }
        assert!(facts(&core, snapshot, &context).unwrap().filters.is_empty());
    }

    #[test]
    fn numeric_ranges_and_alternatives_preserve_unknown_and_negative_evidence() {
        let traits = fields(Some(r#"{"Größe":["1,70 m"],"Alter":["32 Jahre"]}"#.into())).unwrap();
        assert_eq!(known_height(&traits), Some(170));
        assert_eq!(
            range_match("height", 150, 170, "cm", known_height(&traits)).status,
            EvidenceStatus::Yes
        );
        assert_eq!(
            range_match("height", 171, 180, "cm", known_height(&traits)).status,
            EvidenceStatus::No
        );
        let conflict = fields(Some(r#"{"Größe":["170 cm","175 cm"]}"#.into())).unwrap();
        assert_eq!(known_height(&conflict), None);
        let negative = FilterEvidence {
            group: "fixture".into(),
            label: "A".into(),
            status: EvidenceStatus::No,
        };
        let unknown = FilterEvidence {
            group: "fixture".into(),
            label: "B".into(),
            status: EvidenceStatus::Unknown,
        };
        assert_eq!(
            match_group("fixture".into(), vec![negative.clone(), unknown]).status,
            EvidenceStatus::Unknown
        );
        assert_eq!(
            match_group("fixture".into(), vec![negative.clone(), negative]).status,
            EvidenceStatus::No
        );
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
        assert!(matches!(
            save_note(&core, listing, person, None, Some("Stale".into())),
            Err(CoreError::IdentityConflict(reason)) if reason == "Note has changed; reload before saving"
        ));
        assert!(matches!(
            save_note(&core, listing, other, None, Some("Wrong".into())),
            Err(CoreError::IdentityConflict(reason)) if reason == "Note owner changed; reopen profile"
        ));
        assert!(matches!(
            person_note(&core, listing, other),
            Err(CoreError::IdentityConflict(reason)) if reason == "Note owner changed; reopen profile"
        ));
        assert_eq!(
            person_note(&core, listing, person).unwrap().as_deref(),
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
        assert!(person_note(&core, listing, person).unwrap().is_none());
        let state = core.get_person(person).unwrap().unwrap();
        assert!(state.favorite);
        assert_eq!(state.preference, Preference::Like);
    }
}
