use rusqlite::{OptionalExtension, params};
use serde_json::json;
use std::collections::HashSet;

use crate::{
    CoreError, LadiesCore,
    source::{self, SearchRequest, SearchResponse},
};

#[derive(Debug, Clone, uniffi::Record)]
pub struct SavedSearchInfo {
    pub search_id: String,
    pub name: String,
    pub city: String,
    pub radius_km: u32,
    pub last_run_at: Option<String>,
    pub last_error: Option<String>,
    pub unread_new_results: bool,
    pub definition_supported: bool,
}

pub(crate) fn save(
    core: &LadiesCore,
    name: String,
    request: SearchRequest,
) -> Result<String, CoreError> {
    source::filter::validate_request(&request)?;
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 100 {
        return Err(CoreError::InvalidInput(
            "Search name must have 1–100 characters".into(),
        ));
    }
    let definition =
        serde_json::to_string(&request).map_err(|e| CoreError::Storage(e.to_string()))?;
    let db = core.connection()?;
    db.execute(
        "INSERT INTO saved_searches (saved_search_id,name,filters_json,definition_version)
         VALUES (lower(hex(randomblob(16))),?1,?2,2)",
        params![name, definition],
    )?;
    db.query_row(
        "SELECT saved_search_id FROM saved_searches WHERE rowid=last_insert_rowid()",
        [],
        |r| r.get(0),
    )
    .map_err(Into::into)
}

pub(crate) fn update(
    core: &LadiesCore,
    id: String,
    name: String,
    request: SearchRequest,
) -> Result<(), CoreError> {
    source::filter::validate_request(&request)?;
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 100 {
        return Err(CoreError::InvalidInput(
            "Search name must have 1–100 characters".into(),
        ));
    }
    let definition =
        serde_json::to_string(&request).map_err(|e| CoreError::Storage(e.to_string()))?;
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    let previous: String = tx
        .query_row(
            "SELECT filters_json FROM saved_searches WHERE saved_search_id=?1",
            [&id],
            |r| r.get(0),
        )
        .optional()?
        .ok_or_else(|| CoreError::InvalidInput("Saved search not found".into()))?;
    // Filter changes invalidate the old result baseline, but renaming never does.
    let changed = decode_definition(&previous).map_or(true, |old| {
        serde_json::to_value(old).ok() != serde_json::to_value(&request).ok()
    });
    if changed {
        tx.execute(
            "DELETE FROM saved_search_results WHERE saved_search_id=?1",
            [&id],
        )?;
        tx.execute(
            "UPDATE saved_searches SET name=?2,filters_json=?3,
             definition_version=definition_version+1,
             last_run_at=NULL,last_attempt_at=NULL,last_error=NULL,last_delta_json=NULL,
             unread_new_results=0,new_results_at=NULL,
             updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
             WHERE saved_search_id=?1",
            params![id, name, definition],
        )?;
    } else {
        tx.execute(
            "UPDATE saved_searches SET name=?2,filters_json=?3,
             updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
             WHERE saved_search_id=?1",
            params![id, name, definition],
        )?;
    }
    tx.commit()?;
    Ok(())
}

fn decode_definition(definition: &str) -> Result<SearchRequest, CoreError> {
    if let Ok(request) = serde_json::from_str::<SearchRequest>(definition) {
        source::filter::validate_request(&request)?;
        return Ok(request);
    }
    // Imported definitions use camelCase ranges and a nested selected filter map.
    // Translate the intent into the native request rather than preserving a Python pipeline.
    let legacy: serde_json::Value = serde_json::from_str(definition)
        .map_err(|_| CoreError::InvalidInput("Malformed saved-search definition".into()))?;
    local_visibility(definition)?;
    let number = |camel: &str, snake: &str, default: u32| {
        legacy
            .get(camel)
            .or_else(|| legacy.get(snake))
            .and_then(|value| {
                value
                    .as_u64()
                    .and_then(|n| u32::try_from(n).ok())
                    .or_else(|| value.as_str()?.parse().ok())
            })
            .unwrap_or(default)
    };
    let request = SearchRequest {
        city: legacy
            .get("city")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_owned(),
        radius_km: number("radius", "radius_km", 20),
        category: legacy
            .get("category")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_owned(),
        age_min: number("ageMin", "age_min", 18),
        age_max: number("ageMax", "age_max", 60),
        height_min: number("heightMin", "height_min", 140),
        height_max: number("heightMax", "height_max", 200),
        filters_json: legacy
            .get("selected")
            .filter(|v| v.is_object())
            .map_or_else(|| "{}".into(), |v| v.to_string()),
        max_pages: 50,
    };
    source::filter::validate_request(&request)?;
    Ok(request)
}

fn definition(core: &LadiesCore, id: &str) -> Result<(String, i64), CoreError> {
    let db = core.connection()?;
    db.query_row(
        "SELECT filters_json, definition_version FROM saved_searches WHERE saved_search_id=?1",
        [id],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )
    .optional()?
    .ok_or_else(|| CoreError::InvalidInput("Saved search not found".into()))
}

fn request(core: &LadiesCore, id: &str) -> Result<SearchRequest, CoreError> {
    decode_definition(&definition(core, id)?.0)
}

pub(crate) fn load(core: &LadiesCore, id: String) -> Result<SearchRequest, CoreError> {
    request(core, &id)
}

pub(crate) fn list(core: &LadiesCore) -> Result<Vec<SavedSearchInfo>, CoreError> {
    let db = core.connection()?;
    let mut query = db.prepare(
        "SELECT saved_search_id,name,filters_json,last_run_at,last_error,unread_new_results
         FROM saved_searches ORDER BY updated_at DESC,saved_search_id DESC",
    )?;
    let rows = query.query_map([], |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, String>(1)?,
            r.get::<_, String>(2)?,
            r.get::<_, Option<String>>(3)?,
            r.get::<_, Option<String>>(4)?,
            r.get::<_, i64>(5)?,
        ))
    })?;
    rows.map(|row| {
        let (search_id, name, definition, last_run_at, last_error, unread) = row?;
        let parsed = decode_definition(&definition).ok();
        Ok(SavedSearchInfo {
            search_id,
            name,
            city: parsed.as_ref().map_or(String::new(), |r| r.city.clone()),
            radius_km: parsed.as_ref().map_or(0, |r| r.radius_km),
            last_run_at,
            last_error,
            unread_new_results: unread != 0,
            definition_supported: parsed.is_some(),
        })
    })
    .collect()
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SavedSearchCard {
    pub result_key: String,
    pub listing_id: Option<i64>,
    pub person_id: Option<i64>,
    pub name: String,
    pub city: String,
    pub thumbnail_url: Option<String>,
    pub first_seen_at: String,
}

pub(crate) fn results(core: &LadiesCore, id: String) -> Result<Vec<SavedSearchCard>, CoreError> {
    let db = core.connection()?;
    let exists: bool = db.query_row(
        "SELECT EXISTS(SELECT 1 FROM saved_searches WHERE saved_search_id=?1)",
        [&id],
        |row| row.get(0),
    )?;
    if !exists {
        return Err(CoreError::InvalidInput("Saved search not found".into()));
    }
    let mut query = db.prepare(
        "SELECT r.result_key,r.snapshot_json,r.first_seen_at,
                l.listing_id,l.person_id
         FROM saved_search_results r
         LEFT JOIN listings l ON l.source='LADIES' AND l.external_listing_id=r.result_key
         WHERE r.saved_search_id=?1 AND r.present=1 AND r.visible=1
         ORDER BY r.position ASC LIMIT 1000",
    )?;
    let rows = query.query_map([id], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
            row.get::<_, Option<i64>>(3)?,
            row.get::<_, Option<i64>>(4)?,
        ))
    })?;
    rows.map(|row| {
        let (result_key, snapshot, first_seen_at, listing_id, person_id) = row?;
        let value = serde_json::from_str::<serde_json::Value>(&snapshot).unwrap_or_default();
        Ok(SavedSearchCard {
            name: value
                .get("name")
                .and_then(|v| v.as_str())
                .unwrap_or(&result_key)
                .to_owned(),
            city: value
                .get("city")
                .and_then(|v| v.as_str())
                .unwrap_or_default()
                .to_owned(),
            thumbnail_url: value
                .get("thumbnail_url")
                .and_then(|v| v.as_str())
                .map(str::to_owned),
            result_key,
            listing_id,
            person_id,
            first_seen_at,
        })
    })
    .collect()
}

pub(crate) fn delete(core: &LadiesCore, id: String) -> Result<(), CoreError> {
    let db = core.connection()?;
    db.execute("DELETE FROM saved_searches WHERE saved_search_id=?1", [id])?;
    Ok(())
}

pub(crate) fn acknowledge(core: &LadiesCore, id: String) -> Result<(), CoreError> {
    let db = core.connection()?;
    db.execute(
        "UPDATE saved_searches SET unread_new_results=0 WHERE saved_search_id=?1",
        [id],
    )?;
    Ok(())
}

fn persist_results(
    core: &LadiesCore,
    id: &str,
    result: &SearchResponse,
    expected_definition: &str,
    expected_version: i64,
) -> Result<(), CoreError> {
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    let (existing, current_definition, current_version): (Option<String>, String, i64) = tx
        .query_row(
            "SELECT last_run_at, filters_json, definition_version
             FROM saved_searches WHERE saved_search_id=?1",
            [id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .optional()?
        .ok_or_else(|| CoreError::InvalidInput("Saved search was deleted".into()))?;
    if current_version != expected_version || current_definition != expected_definition {
        return Err(CoreError::InvalidInput(
            "Saved search changed during execution; stale results discarded".into(),
        ));
    }
    let previous_keys: HashSet<String> = {
        let mut query = tx.prepare(
            "SELECT result_key FROM saved_search_results WHERE saved_search_id=?1 AND present=1",
        )?;
        let rows = query.query_map([id], |r| r.get::<_, String>(0))?;
        rows.collect::<rusqlite::Result<HashSet<_>>>()?
    };
    tx.execute(
        "UPDATE saved_search_results SET present=0 WHERE saved_search_id=?1",
        [id],
    )?;
    let mut newly_seen = 0u32;
    for (position, card) in result.profiles.iter().enumerate() {
        let key = card.advert_id.clone();
        if !previous_keys.contains(&key) && existing.is_some() {
            newly_seen += 1;
        }
        let snapshot = json!({
            "listing_id":card.listing_id,
            "person_id":card.person_id,
            "name":card.name,
            "city":card.city,
            "profile_url":card.profile_url,
            "thumbnail_url":card.thumbnail_url,
        })
        .to_string();
        tx.execute(
            "INSERT INTO saved_search_results
             (saved_search_id,result_key,snapshot_json,position,present,visible,first_visible_at)
             VALUES (?1,?2,?3,?4,1,1,strftime('%Y-%m-%dT%H:%M:%fZ','now'))
             ON CONFLICT(saved_search_id,result_key) DO UPDATE SET
             snapshot_json=excluded.snapshot_json,position=excluded.position,
             present=1,visible=1,
             last_seen_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')",
            params![id, key, snapshot, position as i64],
        )?;
    }
    let current_keys: HashSet<String> = result
        .profiles
        .iter()
        .map(|card| card.advert_id.clone())
        .collect();
    let removed = previous_keys.difference(&current_keys).count();
    tx.execute(
        "UPDATE saved_searches SET
         last_run_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),
         last_attempt_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),
         updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),
         last_error=NULL,
         last_delta_json=?2,
         unread_new_results=CASE WHEN ?3>0 THEN 1 ELSE unread_new_results END,
         new_results_at=CASE WHEN ?3>0 THEN strftime('%Y-%m-%dT%H:%M:%fZ','now') ELSE new_results_at END
         WHERE saved_search_id=?1",
        params![id,json!({"new":newly_seen,"removed":removed,"current":result.profiles.len()}).to_string(),newly_seen],
    )?;
    tx.commit()?;
    Ok(())
}

#[derive(Default)]
struct LocalVisibility {
    video_only: bool,
    photos_only: bool,
    hide_dislikes: bool,
    hide_likes: bool,
    image_mode: String,
}

fn local_visibility(definition: &str) -> Result<LocalVisibility, CoreError> {
    let parsed: serde_json::Value = serde_json::from_str(definition)
        .map_err(|_| CoreError::InvalidInput("Malformed saved-search definition".into()))?;
    let Some(local_value) = parsed.get("local") else {
        return Ok(LocalVisibility::default());
    };
    let local = local_value
        .as_object()
        .ok_or_else(|| CoreError::InvalidInput("Unsupported local saved-search filters".into()))?;
    for (key, value) in local {
        let supported = match key.as_str() {
            "videoOnly" | "photosOnly" | "hideDislikes" | "hideLikes" => value.is_boolean(),
            "imageMode" => value.as_str().is_some_and(|mode| {
                matches!(
                    mode.to_ascii_uppercase().as_str(),
                    "ALL" | "AI" | "NATURAL" | "STUDIO" | "FACEAPP"
                )
            }),
            _ => false,
        };
        if !supported {
            return Err(CoreError::InvalidInput(format!(
                "Unsupported local saved-search filter: {key}"
            )));
        }
    }
    let flag = |key: &str| local.get(key).and_then(|v| v.as_bool());
    Ok(LocalVisibility {
        video_only: flag("videoOnly") == Some(true),
        photos_only: flag("photosOnly") == Some(true),
        hide_dislikes: flag("hideDislikes") != Some(false),
        hide_likes: flag("hideLikes") == Some(true),
        image_mode: local
            .get("imageMode")
            .and_then(|v| v.as_str())
            .unwrap_or("ALL")
            .to_uppercase(),
    })
}

fn apply_local_visibility(
    core: &LadiesCore,
    response: &mut SearchResponse,
    rules: LocalVisibility,
) -> Result<(), CoreError> {
    let db = core.connection()?;
    let mut person_query =
        db.prepare("SELECT preference FROM person_user_state WHERE person_id=?1")?;
    let mut marker_query = db.prepare(
        "SELECT markers_json FROM search_results WHERE search_run_id=?1 AND listing_id=?2",
    )?;
    let mut visible = Vec::new();
    for card in response.profiles.drain(..) {
        if (rules.video_only && !card.has_video)
            || (rules.photos_only && card.thumbnail_url.is_none())
        {
            continue;
        }
        let preference: String = person_query.query_row([card.person_id], |r| r.get(0))?;
        if (rules.hide_dislikes && preference == "DISLIKE")
            || (rules.hide_likes && preference == "LIKE")
        {
            continue;
        }
        let image_allowed = match rules.image_mode.as_str() {
            "AI" => card.has_ai_content,
            "NATURAL" => !card.has_ai_content,
            "STUDIO" | "FACEAPP" => {
                let markers: Option<String> = marker_query
                    .query_row(params![response.search_run_id, card.listing_id], |r| {
                        r.get(0)
                    })
                    .optional()?
                    .flatten();
                let marker = markers.unwrap_or_default().to_lowercase();
                marker.contains(if rules.image_mode == "STUDIO" {
                    "studio"
                } else {
                    "face"
                })
            }
            _ => true,
        };
        if image_allowed {
            visible.push(card);
        }
    }
    response.profiles = visible;
    Ok(())
}

pub(crate) fn run(core: &LadiesCore, id: String) -> Result<SearchResponse, CoreError> {
    let (stored_definition, version) = definition(core, &id)?;
    let request = decode_definition(&stored_definition)?;
    let rules = local_visibility(&stored_definition)?;
    let fetched = source::search(core, request);
    match fetched {
        Ok(mut response) => {
            apply_local_visibility(core, &mut response, rules)?;
            if !response.partial && response.radius_verified {
                persist_results(core, &id, &response, &stored_definition, version)?;
            } else {
                core.connection()?.execute(
                    "UPDATE saved_searches
                     SET last_attempt_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),
                         last_error='Search incomplete; saved results not updated'
                     WHERE saved_search_id=?1 AND definition_version=?2 AND filters_json=?3",
                    params![id, version, stored_definition],
                )?;
            }
            Ok(response)
        }
        Err(error) => {
            core.connection()?.execute(
                "UPDATE saved_searches
                 SET last_attempt_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'), last_error=?2
                 WHERE saved_search_id=?1 AND definition_version=?3 AND filters_json=?4",
                params![id, error.to_string(), version, stored_definition],
            )?;
            Err(error)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn native_saved_searches_roundtrip_and_legacy_defs_are_non_destructive() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let req = SearchRequest::for_test();
        let id = save(&core, "Leipzig Zentrum".into(), req).unwrap();
        assert_eq!(load(&core, id.clone()).unwrap().city, "Leipzig");
        assert!(list(&core).unwrap()[0].definition_supported);
        core.connection()
            .unwrap()
            .execute(
                "INSERT INTO saved_searches(saved_search_id,name,filters_json)
             VALUES ('imported','Old','{\"legacy\":true}')",
                [],
            )
            .unwrap();
        assert_eq!(list(&core).unwrap().len(), 2);
        assert!(load(&core, "imported".into()).is_err());
        delete(&core, id).unwrap();
        assert_eq!(list(&core).unwrap().len(), 1);
    }

    #[test]
    fn imported_search_definition_converts_ranges_and_source_filters() {
        let legacy = r#"{"city":"Leipzig","radius":30,"category":"escort","ageMin":22,"ageMax":45,"heightMin":150,"heightMax":190,"selected":{"figur":["schlank"]},"local":{"videoOnly":true}}"#;
        let q = decode_definition(legacy).unwrap();
        assert_eq!(
            (q.city.as_str(), q.radius_km, q.age_min, q.age_max),
            ("Leipzig", 30, 22, 45)
        );
        assert_eq!(q.category, "escort");
        assert!(q.filters_json.contains("schlank"));
    }

    #[test]
    fn unknown_legacy_local_filters_are_not_silently_discarded() {
        let unsupported = r#"{"city":"Leipzig","local":{"businessOnly":true}}"#;
        assert!(decode_definition(unsupported).is_err());
        let core = LadiesCore::new(":memory:".into()).unwrap();
        core.connection()
            .unwrap()
            .execute(
                "INSERT INTO saved_searches(saved_search_id,name,filters_json)
                 VALUES ('unsupported','Imported',?1)",
                [unsupported],
            )
            .unwrap();
        let imported = list(&core).unwrap();
        assert!(!imported[0].definition_supported);
        assert!(load(&core, "unsupported".into()).is_err());
    }

    #[test]
    fn saved_search_deltas_and_rename_are_persistent() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let req = SearchRequest::for_test();
        let id = save(&core, "Leipzig".into(), req.clone()).unwrap();
        let a = core.create_person(Some("A".into())).unwrap();
        let b = core.create_person(Some("B".into())).unwrap();
        let c = core.create_person(Some("C".into())).unwrap();
        let la = core
            .add_listing(a, None, "LADIES".into(), "101".into(), None)
            .unwrap();
        let lb = core
            .add_listing(b, None, "LADIES".into(), "102".into(), None)
            .unwrap();
        let lc = core
            .add_listing(c, None, "LADIES".into(), "103".into(), None)
            .unwrap();
        let card = |listing_id, person_id, advert_id: &str| source::SearchCard {
            listing_id,
            person_id,
            advert_id: advert_id.into(),
            name: advert_id.into(),
            city: "Leipzig".into(),
            category: "".into(),
            profile_url: "https://www.ladies.de/sex-anzeigen/x".into(),
            thumbnail_url: None,
            has_video: false,
            is_premium: false,
            has_ai_content: false,
            has_18plus: false,
            has_stars: false,
        };
        let mut result = SearchResponse {
            search_run_id: 1,
            city: "Leipzig".into(),
            requested_radius_km: 20,
            server_count: Some(2),
            total_pages: 1,
            fetched_pages: 1,
            partial: false,
            radius_boundary_stopped: false,
            radius_verified: true,
            profiles: vec![card(la, a, "101"), card(lb, b, "102")],
        };
        let (stored_definition, version) = definition(&core, &id).unwrap();
        persist_results(&core, &id, &result, &stored_definition, version).unwrap();
        update(&core, id.clone(), "Umbenannt".into(), req.clone()).unwrap();
        assert!(list(&core).unwrap()[0].last_run_at.is_some());
        result.profiles = vec![card(lb, b, "102"), card(lc, c, "103")];
        let (stored_definition, version) = definition(&core, &id).unwrap();
        persist_results(&core, &id, &result, &stored_definition, version).unwrap();
        let stored = results(&core, id.clone()).unwrap();
        assert_eq!(stored.len(), 2);
        assert_eq!(stored[0].listing_id, Some(lb));
        let db = core.connection().unwrap();
        let delta: String = db
            .query_row(
                "SELECT last_delta_json FROM saved_searches WHERE saved_search_id=?1",
                [&id],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&delta).unwrap()["new"],
            1
        );
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&delta).unwrap()["removed"],
            1
        );
        drop(db);
        assert!(list(&core).unwrap()[0].unread_new_results);
        acknowledge(&core, id.clone()).unwrap();
        assert!(!list(&core).unwrap()[0].unread_new_results);
        let mut changed = req;
        changed.radius_km = 30;
        update(&core, id.clone(), "Umbenannt".into(), changed).unwrap();
        assert!(list(&core).unwrap()[0].last_run_at.is_none());
        let remaining: i64 = core
            .connection()
            .unwrap()
            .query_row(
                "SELECT COUNT(*) FROM saved_search_results WHERE saved_search_id=?1",
                [&id],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(remaining, 0);
    }

    #[test]
    fn stale_results_do_not_replace_a_redefined_saved_search() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let id = save(&core, "Original".into(), SearchRequest::for_test()).unwrap();
        let (initial, initial_version) = definition(&core, &id).unwrap();
        let mut changed = SearchRequest::for_test();
        changed.radius_km = 30;
        update(&core, id.clone(), "Changed".into(), changed).unwrap();
        let empty = SearchResponse {
            search_run_id: 1,
            city: "Leipzig".into(),
            requested_radius_km: 20,
            server_count: Some(0),
            total_pages: 1,
            fetched_pages: 1,
            partial: false,
            radius_boundary_stopped: false,
            radius_verified: true,
            profiles: Vec::new(),
        };
        assert!(persist_results(&core, &id, &empty, &initial, initial_version).is_err());
        let db = core.connection().unwrap();
        let (count, last_run): (i64, Option<String>) = db
            .query_row(
                "SELECT (SELECT COUNT(*) FROM saved_search_results WHERE saved_search_id=?1),
                        last_run_at FROM saved_searches WHERE saved_search_id=?1",
                [&id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(count, 0);
        assert_eq!(last_run, None);
    }

    #[test]
    fn saved_result_changes_are_atomic_and_old_absences_need_complete_search() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let id = save(&core, "test".into(), SearchRequest::for_test()).unwrap();
        let response = SearchResponse {
            search_run_id: 1,
            city: "Leipzig".into(),
            requested_radius_km: 20,
            server_count: Some(0),
            total_pages: 1,
            fetched_pages: 1,
            partial: false,
            radius_boundary_stopped: false,
            radius_verified: true,
            profiles: Vec::new(),
        };
        let (stored_definition, version) = definition(&core, &id).unwrap();
        persist_results(&core, &id, &response, &stored_definition, version).unwrap();
        assert!(!list(&core).unwrap()[0].unread_new_results);
        persist_results(&core, &id, &response, &stored_definition, version).unwrap();
        assert!(!list(&core).unwrap()[0].unread_new_results);
    }
}
