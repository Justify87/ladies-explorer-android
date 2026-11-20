use rusqlite::{OptionalExtension, params};
use serde_json::json;
use std::collections::HashSet;

use crate::{
    CoreError, LadiesCore,
    operation::RequestControl,
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
    pub local_filter_summary: String,
    pub delta: Option<SavedSearchDelta>,
    pub revision: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SavedSearchDelta {
    pub new_count: u32,
    pub removed_count: u32,
    pub current_count: u32,
    pub initial: Option<bool>,
}

fn read_delta(raw: Option<&str>) -> Option<SavedSearchDelta> {
    let value: serde_json::Value = serde_json::from_str(raw?).ok()?;
    let count = |native: &str, legacy: &str| {
        u32::try_from(value.get(native).or_else(|| value.get(legacy))?.as_u64()?).ok()
    };
    Some(SavedSearchDelta {
        new_count: count("new", "new_results_count")?,
        removed_count: count("removed", "removed_results_count")?,
        current_count: count("current", "current_result_count")?,
        initial: value.get("initial").and_then(|v| v.as_bool()),
    })
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct SavedSearchDefinition {
    pub query: SearchRequest,
    pub local: LocalVisibility,
}

fn encode_definition(definition: &SavedSearchDefinition) -> Result<String, CoreError> {
    serde_json::to_string(definition).map_err(|e| CoreError::Storage(e.to_string()))
}

#[cfg(test)]
fn save(core: &LadiesCore, name: String, request: SearchRequest) -> Result<String, CoreError> {
    save_with_local(core, name, request, LocalVisibility::default())
}

pub(crate) fn save_with_local(
    core: &LadiesCore,
    name: String,
    request: SearchRequest,
    local: LocalVisibility,
) -> Result<String, CoreError> {
    validate_local(&local)?;
    let request = source::filter::canonicalize_request(request)?;
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 100 {
        return Err(CoreError::InvalidInput(
            "Search name must have 1–100 characters".into(),
        ));
    }
    let definition = encode_definition(&SavedSearchDefinition {
        query: request,
        local,
    })?;
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

#[cfg(test)]
fn update(
    core: &LadiesCore,
    id: String,
    name: String,
    request: SearchRequest,
) -> Result<(), CoreError> {
    let local = load_definition(core, id.clone())?.local;
    update_with_local(core, id, name, request, local)
}

pub(crate) fn update_with_local(
    core: &LadiesCore,
    id: String,
    name: String,
    request: SearchRequest,
    local: LocalVisibility,
) -> Result<(), CoreError> {
    validate_local(&local)?;
    let request = source::filter::canonicalize_request(request)?;
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 100 {
        return Err(CoreError::InvalidInput(
            "Search name must have 1–100 characters".into(),
        ));
    }
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
    let previous = decode_definition(&previous)?;
    let next = SavedSearchDefinition {
        query: request,
        local,
    };
    let changed = previous != next;
    let definition = encode_definition(&next)?;
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
            "UPDATE saved_searches SET name=?2,
             updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
             WHERE saved_search_id=?1",
            params![id, name],
        )?;
    }
    tx.commit()?;
    Ok(())
}

fn require_keys(value: &serde_json::Value, allowed: &[&str]) -> Result<(), CoreError> {
    let object = value
        .as_object()
        .ok_or_else(|| CoreError::InvalidInput("Invalid saved-search object".into()))?;
    if let Some(key) = object.keys().find(|key| !allowed.contains(&key.as_str())) {
        return Err(CoreError::InvalidInput(format!(
            "Unsupported saved-search field: {key}"
        )));
    }
    Ok(())
}

fn decode_definition(definition: &str) -> Result<SavedSearchDefinition, CoreError> {
    let legacy: serde_json::Value = serde_json::from_str(definition)
        .map_err(|_| CoreError::InvalidInput("Malformed saved-search definition".into()))?;
    const QUERY_KEYS: &[&str] = &[
        "city",
        "radius_km",
        "category",
        "age_min",
        "age_max",
        "height_min",
        "height_max",
        "filters_json",
        "max_pages",
    ];
    // Reject malformed native definitions; never silently fall back to legacy defaults.
    if legacy.get("query").is_some() {
        require_keys(&legacy, &["query", "local"])?;
        require_keys(&legacy["query"], QUERY_KEYS)?;
        require_keys(
            &legacy["local"],
            &[
                "video_only",
                "photos_only",
                "hide_dislikes",
                "hide_likes",
                "image_mode",
            ],
        )?;
        let mut parsed: SavedSearchDefinition = serde_json::from_value(legacy).map_err(|_| {
            CoreError::InvalidInput("Malformed native saved-search definition".into())
        })?;
        parsed.query = source::filter::canonicalize_request(parsed.query)?;
        validate_local(&parsed.local)?;
        return Ok(parsed);
    }
    if legacy.get("filters_json").is_some() {
        require_keys(&legacy, QUERY_KEYS)?;
        let request: SearchRequest = serde_json::from_value(legacy)
            .map_err(|_| CoreError::InvalidInput("Malformed native search request".into()))?;
        return Ok(SavedSearchDefinition {
            query: source::filter::canonicalize_request(request)?,
            local: LocalVisibility::default(),
        });
    }
    require_keys(
        &legacy,
        &[
            "city",
            "radius",
            "radius_km",
            "category",
            "ageMin",
            "age_min",
            "ageMax",
            "age_max",
            "heightMin",
            "height_min",
            "heightMax",
            "height_max",
            "selected",
            "local",
        ],
    )?;
    if legacy
        .get("selected")
        .is_some_and(|value| !value.is_object())
    {
        return Err(CoreError::InvalidInput(
            "Invalid legacy selected filters".into(),
        ));
    }
    let local = local_visibility(definition)?;
    let number = |camel: &str, snake: &str, default: u32| -> Result<u32, CoreError> {
        let parse = |value: &serde_json::Value| {
            value
                .as_u64()
                .and_then(|n| u32::try_from(n).ok())
                .or_else(|| value.as_str()?.parse().ok())
                .ok_or_else(|| CoreError::InvalidInput(format!("Invalid legacy field: {camel}")))
        };
        match (legacy.get(camel), legacy.get(snake)) {
            (Some(a), Some(b)) => {
                let a = parse(a)?;
                if a != parse(b)? {
                    return Err(CoreError::InvalidInput(format!(
                        "Conflicting legacy field: {camel}"
                    )));
                }
                Ok(a)
            }
            (Some(v), None) | (None, Some(v)) => parse(v),
            (None, None) => Ok(default),
        }
    };
    let string = |key: &str| -> Result<String, CoreError> {
        match legacy.get(key) {
            Some(v) => v
                .as_str()
                .map(str::to_owned)
                .ok_or_else(|| CoreError::InvalidInput(format!("Invalid legacy field: {key}"))),
            None => Ok(String::new()),
        }
    };
    let request = SearchRequest {
        city: string("city")?,
        radius_km: number("radius", "radius_km", 20)?,
        category: string("category")?,
        age_min: number("ageMin", "age_min", 18)?,
        age_max: number("ageMax", "age_max", 60)?,
        height_min: number("heightMin", "height_min", 140)?,
        height_max: number("heightMax", "height_max", 200)?,
        filters_json: legacy
            .get("selected")
            .filter(|v| v.is_object())
            .map_or_else(|| "{}".into(), |v| v.to_string()),
        max_pages: 50,
    };
    Ok(SavedSearchDefinition {
        query: source::filter::canonicalize_request(request)?,
        local,
    })
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

pub(crate) fn load_definition(
    core: &LadiesCore,
    id: String,
) -> Result<SavedSearchDefinition, CoreError> {
    let (stored, _) = definition(core, &id)?;
    decode_definition(&stored)
}

#[cfg(test)]
fn load(core: &LadiesCore, id: String) -> Result<SearchRequest, CoreError> {
    Ok(load_definition(core, id)?.query)
}

fn revision(db: &rusqlite::Connection) -> Result<i64, CoreError> {
    Ok(db.query_row("SELECT total_changes()", [], |r| r.get(0))?)
}

pub(crate) fn list(core: &LadiesCore) -> Result<Vec<SavedSearchInfo>, CoreError> {
    let db = core.connection()?;
    list_using(&db)
}

fn list_using(db: &rusqlite::Connection) -> Result<Vec<SavedSearchInfo>, CoreError> {
    let revision = revision(db)?;
    let mut query = db.prepare(
        "SELECT saved_search_id,name,filters_json,last_run_at,last_error,unread_new_results,last_delta_json
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
            r.get::<_, Option<String>>(6)?,
        ))
    })?;
    rows.map(|row| {
        let (search_id, name, definition, last_run_at, last_error, unread, delta) = row?;
        let parsed = decode_definition(&definition).ok();
        Ok(SavedSearchInfo {
            search_id,
            name,
            city: parsed
                .as_ref()
                .map_or(String::new(), |r| r.query.city.clone()),
            radius_km: parsed.as_ref().map_or(0, |r| r.query.radius_km),
            last_run_at,
            last_error,
            unread_new_results: unread != 0,
            definition_supported: parsed.is_some(),
            local_filter_summary: parsed.as_ref().map_or(String::new(), |r| r.local.summary()),
            delta: read_delta(delta.as_deref()),
            revision,
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
    pub local_path: Option<String>,
    pub age: Option<String>,
}

#[derive(Debug, Clone)]
struct SavedResultCursor {
    pub position: i64,
    pub result_key: String,
    pub search_id: String,
    pub revision: i64,
}

#[derive(Debug, Clone)]
struct SavedSearchResultPage {
    pub cards: Vec<SavedSearchCard>,
    pub next_cursor: Option<SavedResultCursor>,
}

#[cfg(test)]
fn results(
    core: &LadiesCore,
    id: String,
    limit: u32,
    cursor: Option<SavedResultCursor>,
) -> Result<SavedSearchResultPage, CoreError> {
    if !(1..=200).contains(&limit) {
        return Err(CoreError::InvalidInput(
            "Saved-result page size must be between 1 and 200".into(),
        ));
    }
    let db = core.connection()?;
    results_using(core, &db, &id, limit, cursor)
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SavedSearchWindow {
    pub cards: Vec<SavedSearchCard>,
    pub has_more: bool,
    pub searches: Vec<SavedSearchInfo>,
}

// Read prefix in one snapshot; acknowledge separately after UI acceptance using revision CAS.
pub(crate) fn window(
    core: &LadiesCore,
    id: String,
    limit: u32,
) -> Result<SavedSearchWindow, CoreError> {
    if limit == 0 {
        return Err(CoreError::InvalidInput(
            "Saved window must not be empty".into(),
        ));
    }
    let db = core.connection()?;
    let page = results_using(core, &db, &id, limit, None)?;
    Ok(SavedSearchWindow {
        cards: page.cards,
        has_more: page.next_cursor.is_some(),
        searches: list_using(&db)?,
    })
}

fn results_using(
    core: &LadiesCore,
    db: &rusqlite::Connection,
    id: &str,
    limit: u32,
    cursor: Option<SavedResultCursor>,
) -> Result<SavedSearchResultPage, CoreError> {
    let revision = revision(db)?;
    if cursor
        .as_ref()
        .is_some_and(|c| c.search_id != id || c.revision != revision)
    {
        return Err(CoreError::InvalidInput(
            "Saved result cursor expired; reload the window".into(),
        ));
    }
    let exists: bool = db.query_row(
        "SELECT EXISTS(SELECT 1 FROM saved_searches WHERE saved_search_id=?1)",
        [&id],
        |row| row.get(0),
    )?;
    if !exists {
        return Err(CoreError::InvalidInput("Saved search not found".into()));
    }
    let mut query = db.prepare(
        "SELECT r.position,r.result_key,r.snapshot_json,r.first_seen_at,
                l.listing_id,l.person_id,s.traits_json,s.name,s.city,
                image.remote_url,a.local_path,image.snapshot_media_id
         FROM saved_search_results r
         LEFT JOIN listings l ON l.source='LADIES' AND l.external_listing_id=r.result_key
         LEFT JOIN listing_snapshots s ON s.snapshot_id=l.current_snapshot_id AND s.listing_id=l.listing_id
         LEFT JOIN snapshot_media image ON image.snapshot_media_id=COALESCE(
             (SELECT m.snapshot_media_id FROM snapshot_media m
              WHERE m.snapshot_id=s.snapshot_id AND m.kind='IMAGE'
              ORDER BY CASE WHEN m.role='PRIMARY' THEN 0 ELSE 1 END,m.position,m.snapshot_media_id LIMIT 1),
             (SELECT m.snapshot_media_id FROM snapshot_media m JOIN listing_snapshots old ON old.snapshot_id=m.snapshot_id
              WHERE old.listing_id=l.listing_id AND m.kind='IMAGE'
                AND m.remote_url=CASE WHEN json_valid(r.snapshot_json) THEN json_extract(r.snapshot_json,'$.thumbnail_url') ELSE NULL END
              ORDER BY julianday(old.captured_at) DESC,old.snapshot_id DESC,m.position,m.snapshot_media_id LIMIT 1))
         LEFT JOIN media_assets a ON a.media_asset_id=image.media_asset_id
         WHERE r.saved_search_id=?1 AND r.present=1 AND r.visible=1
           AND (?2 IS NULL OR r.position > ?2
                OR (r.position=?2 AND r.result_key > ?3))
         ORDER BY r.position ASC,r.result_key ASC
         LIMIT ?4",
    )?;
    let cursor_position = cursor.as_ref().map(|c| c.position);
    let cursor_key = cursor.as_ref().map(|c| c.result_key.as_str());
    let rows = query.query_map(
        params![id, cursor_position, cursor_key, i64::from(limit) + 1],
        |row| {
            Ok((
                row.get::<_, i64>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, String>(3)?,
                row.get::<_, Option<i64>>(4)?,
                row.get::<_, Option<i64>>(5)?,
                row.get::<_, Option<String>>(6)?,
                row.get::<_, Option<String>>(7)?,
                row.get::<_, Option<String>>(8)?,
                row.get::<_, Option<String>>(9)?,
                row.get::<_, Option<String>>(10)?,
                row.get::<_, Option<i64>>(11)?,
            ))
        },
    )?;
    let mut records = rows
        .map(|row| {
            let (
                position,
                result_key,
                snapshot,
                first_seen_at,
                listing_id,
                person_id,
                traits,
                current_name,
                current_city,
                remote,
                relative,
                media_id,
            ) = row?;
            let value = serde_json::from_str::<serde_json::Value>(&snapshot).unwrap_or_default();
            Ok((
                SavedSearchCard {
                    name: current_name.filter(|v| !v.is_empty()).unwrap_or_else(|| {
                        value
                            .get("name")
                            .and_then(|v| v.as_str())
                            .unwrap_or(&result_key)
                            .to_owned()
                    }),
                    city: current_city.filter(|v| !v.is_empty()).unwrap_or_else(|| {
                        value
                            .get("city")
                            .and_then(|v| v.as_str())
                            .unwrap_or_default()
                            .to_owned()
                    }),
                    // Both URLs must refer to the same selected image.
                    thumbnail_url: if media_id.is_some() {
                        remote.filter(|v| !v.is_empty())
                    } else {
                        value
                            .get("thumbnail_url")
                            .and_then(|v| v.as_str())
                            .map(str::to_owned)
                    },
                    result_key,
                    listing_id,
                    person_id,
                    first_seen_at,
                    age: source::known_age(traits.as_deref()),
                    local_path: relative.and_then(|relative| {
                        let path = core.media_root.as_ref()?.join(relative);
                        path.is_file().then(|| path.to_string_lossy().into_owned())
                    }),
                },
                position,
            ))
        })
        .collect::<Result<Vec<_>, CoreError>>()?;
    let has_more = records.len() > limit as usize;
    records.truncate(limit as usize);
    let next_cursor = if has_more {
        records.last().map(|(card, position)| SavedResultCursor {
            position: *position,
            result_key: card.result_key.clone(),
            search_id: id.to_owned(),
            revision,
        })
    } else {
        None
    };
    Ok(SavedSearchResultPage {
        cards: records.into_iter().map(|(card, _)| card).collect(),
        next_cursor,
    })
}

// Rename must preserve imported definition bytes and result baseline.
pub(crate) fn rename(core: &LadiesCore, id: String, name: String) -> Result<(), CoreError> {
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 100 {
        return Err(CoreError::InvalidInput(
            "Search name must have 1–100 characters".into(),
        ));
    }
    let db = core.connection()?;
    if db.execute(
        "UPDATE saved_searches SET name=?2,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
         WHERE saved_search_id=?1",
        params![id, name],
    )? != 1
    {
        return Err(CoreError::InvalidInput("Saved search not found".into()));
    }
    Ok(())
}

pub(crate) fn delete(core: &LadiesCore, id: String) -> Result<(), CoreError> {
    let db = core.connection()?;
    db.execute("DELETE FROM saved_searches WHERE saved_search_id=?1", [id])?;
    Ok(())
}

pub(crate) fn acknowledge_revision(
    core: &LadiesCore,
    id: String,
    expected: i64,
) -> Result<bool, CoreError> {
    let db = core.connection()?;
    if revision(&db)? != expected {
        return Ok(false);
    }
    Ok(db.execute(
        "UPDATE saved_searches SET unread_new_results=0 WHERE saved_search_id=?1 AND unread_new_results=1",
        [id],
    )? == 1)
}

#[cfg(test)]
fn persist_results(
    core: &LadiesCore,
    id: &str,
    result: &SearchResponse,
    expected_definition: &str,
    expected_version: i64,
) -> Result<(), CoreError> {
    persist_results_with_control(
        core,
        id,
        result,
        expected_definition,
        expected_version,
        None,
    )
}

fn persist_results_with_control(
    core: &LadiesCore,
    id: &str,
    result: &SearchResponse,
    expected_definition: &str,
    expected_version: i64,
    control: Option<&RequestControl>,
) -> Result<(), CoreError> {
    if let Some(control) = control {
        control.check()?;
    }
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
        if let Some(control) = control {
            control.check()?;
        }
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
        params![id,json!({"new":newly_seen,"removed":removed,"current":result.profiles.len(),"initial":existing.is_none()}).to_string(),newly_seen],
    )?;
    if let Some(control) = control {
        control.check()?;
    }
    tx.commit()?;
    Ok(())
}

#[derive(
    Default, Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record,
)]
pub struct LocalVisibility {
    pub video_only: bool,
    pub photos_only: bool,
    pub hide_dislikes: bool,
    pub hide_likes: bool,
    pub image_mode: String,
}

impl LocalVisibility {
    pub(crate) fn allows(
        &self,
        has_video: bool,
        has_photo: bool,
        has_ai: bool,
        preference: &str,
    ) -> bool {
        (!self.video_only || has_video)
            && (!self.photos_only || has_photo)
            && (!self.hide_dislikes || preference != "DISLIKE")
            && (!self.hide_likes || preference != "LIKE")
            && match self.image_mode.as_str() {
                "AI" => has_ai,
                "NATURAL" => !has_ai,
                _ => true,
            }
    }

    fn summary(&self) -> String {
        let mut active = Vec::new();
        if self.video_only {
            active.push("Nur Video");
        }
        if self.photos_only {
            active.push("Nur Fotos");
        }
        if self.hide_dislikes {
            active.push("Ohne Dislikes");
        }
        if self.hide_likes {
            active.push("Ohne Likes");
        }
        match self.image_mode.as_str() {
            "AI" => active.push("KI-Bilder"),
            "NATURAL" => active.push("Ohne KI-Bilder"),
            _ => {}
        }
        active.join(" · ")
    }
}

pub(crate) fn validate_local(local: &LocalVisibility) -> Result<(), CoreError> {
    if !matches!(local.image_mode.as_str(), "" | "ALL" | "AI" | "NATURAL") {
        return Err(CoreError::InvalidInput(
            "Image classification is not available in this native search".into(),
        ));
    }
    Ok(())
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
                matches!(mode.to_ascii_uppercase().as_str(), "ALL" | "AI" | "NATURAL")
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
    let rules = LocalVisibility {
        video_only: flag("videoOnly") == Some(true),
        photos_only: flag("photosOnly") == Some(true),
        hide_dislikes: flag("hideDislikes") != Some(false),
        hide_likes: flag("hideLikes") == Some(true),
        image_mode: local
            .get("imageMode")
            .and_then(|v| v.as_str())
            .unwrap_or("ALL")
            .to_ascii_uppercase(),
    };
    validate_local(&rules)?;
    Ok(rules)
}

pub(crate) fn apply_local_visibility(
    core: &LadiesCore,
    response: &mut SearchResponse,
    rules: LocalVisibility,
) -> Result<(), CoreError> {
    let db = core.connection()?;
    let mut person_query = db.prepare(
        "SELECT l.person_id,u.preference FROM listings l
         JOIN person_user_state u ON u.person_id=l.person_id WHERE l.listing_id=?1",
    )?;
    let mut visible = Vec::new();
    validate_local(&rules)?;
    for mut card in response.profiles.drain(..) {
        let (person_id, preference): (i64, String) =
            person_query.query_row([card.listing_id], |r| Ok((r.get(0)?, r.get(1)?)))?;
        card.person_id = person_id;
        if rules.allows(
            card.has_video,
            card.thumbnail_url.is_some(),
            card.has_ai_content,
            &preference,
        ) {
            visible.push(card);
        }
    }
    response.local = rules;
    response.profiles = visible;
    Ok(())
}

pub(crate) fn run(
    core: &LadiesCore,
    id: String,
    control: Option<&RequestControl>,
) -> Result<SearchResponse, CoreError> {
    run_with_source(core, id, control, |request| {
        source::search(core, request, control)
    })
}

fn run_with_source(
    core: &LadiesCore,
    id: String,
    control: Option<&RequestControl>,
    fetch: impl FnOnce(SearchRequest) -> Result<SearchResponse, CoreError>,
) -> Result<SearchResponse, CoreError> {
    let (stored_definition, version) = definition(core, &id)?;
    let definition = decode_definition(&stored_definition)?;
    match fetch(definition.query) {
        Ok(mut response) => {
            if let Some(control) = control {
                control.check()?;
            }
            apply_local_visibility(core, &mut response, definition.local)?;
            if let Some(control) = control {
                control.check()?;
            }
            if !response.partial && response.radius_verified {
                persist_results_with_control(
                    core,
                    &id,
                    &response,
                    &stored_definition,
                    version,
                    control,
                )?;
            } else {
                if let Some(control) = control {
                    control.check()?;
                }
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
            if let Some(control) = control {
                control.check()?;
            }
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
    fn strict_native_shape_and_unknown_legacy_fields_are_not_rewritten() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let id = save(&core, "Native".into(), SearchRequest::for_test()).unwrap();
        let (raw, _) = definition(&core, &id).unwrap();
        let mut value: serde_json::Value = serde_json::from_str(&raw).unwrap();
        value["local"]["future_rule"] = json!(true);
        assert!(decode_definition(&value.to_string()).is_err());
        assert!(decode_definition(r#"{"city":"Leipzig","future_rule":true}"#).is_err());
        assert!(decode_definition(r#"{"city":"Leipzig","radius":"bad"}"#).is_err());
        assert!(decode_definition(r#"{"city":"Leipzig","radius":20,"radius_km":30}"#).is_err());
        value["local"]
            .as_object_mut()
            .unwrap()
            .remove("future_rule");
        value["query"]["future_rule"] = json!(true);
        assert!(decode_definition(&value.to_string()).is_err());
    }

    #[test]
    fn person_writes_advance_ack_revision_without_changing_saved_membership() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let id = save(&core, "Window".into(), SearchRequest::for_test()).unwrap();
        let person = core.create_person(None).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "card".into(), None)
            .unwrap();
        {
            let db = core.connection().unwrap();
            db.execute(
                "INSERT INTO saved_search_results(saved_search_id,result_key,snapshot_json,position,present,visible)
                 VALUES (?1,'card','{}',1,1,1)",
                [&id],
            )
            .unwrap();
            db.execute(
                "UPDATE saved_searches SET unread_new_results=1 WHERE saved_search_id=?1",
                [&id],
            )
            .unwrap();
        }
        let first = window(&core, id.clone(), 10).unwrap();
        assert_eq!(first.cards[0].listing_id, Some(listing));
        core.set_preference(person, crate::Preference::Like)
            .unwrap();
        let second = window(&core, id.clone(), 10).unwrap();
        assert_ne!(first.searches[0].revision, second.searches[0].revision);
        assert!(!acknowledge_revision(&core, id.clone(), first.searches[0].revision).unwrap());
        core.set_favorite(person, true).unwrap();
        let third = window(&core, id.clone(), 10).unwrap();
        assert_ne!(second.searches[0].revision, third.searches[0].revision);
        assert!(!acknowledge_revision(&core, id.clone(), second.searches[0].revision).unwrap());
        core.save_person_note(listing, person, None, Some("fixture".into()))
            .unwrap();
        let fourth = window(&core, id.clone(), 10).unwrap();
        assert_ne!(third.searches[0].revision, fourth.searches[0].revision);
        assert!(!acknowledge_revision(&core, id.clone(), third.searches[0].revision).unwrap());
        assert_eq!(fourth.cards.len(), 1);
        assert_eq!(fourth.cards[0].listing_id, Some(listing));
        assert_eq!(fourth.cards[0].person_id, Some(person));
        assert!(fourth.searches[0].unread_new_results);
    }

    #[test]
    fn changed_baseline_rejects_cursor_and_stale_acknowledgement() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let id = save(&core, "Window".into(), SearchRequest::for_test()).unwrap();
        let other = save(&core, "Other".into(), SearchRequest::for_test()).unwrap();
        {
            let db = core.connection().unwrap();
            for (position, key) in ["a", "b", "c", "d"].iter().enumerate() {
                db.execute("INSERT INTO saved_search_results(saved_search_id,result_key,snapshot_json,position,present,visible) VALUES (?1,?2,'{}',?3,1,1)",
                    params![id, key, position as i64]).unwrap();
            }
            db.execute(
                "UPDATE saved_searches SET unread_new_results=1 WHERE saved_search_id=?1",
                [&id],
            )
            .unwrap();
        }
        let page = results(&core, id.clone(), 2, None).unwrap();
        let cursor = page.next_cursor.unwrap();
        assert!(matches!(
            results(&core, other, 2, Some(cursor.clone())),
            Err(CoreError::InvalidInput(reason))
                if reason == "Saved result cursor expired; reload the window"
        ));
        let window_before = window(&core, id.clone(), 2).unwrap();
        let version = window_before
            .searches
            .iter()
            .find(|s| s.search_id == id)
            .unwrap()
            .revision;
        core.connection()
            .unwrap()
            .execute(
                "UPDATE saved_search_results SET position=3-position WHERE saved_search_id=?1",
                [&id],
            )
            .unwrap();
        assert!(matches!(
            results(&core, id.clone(), 2, Some(cursor)),
            Err(CoreError::InvalidInput(reason))
                if reason == "Saved result cursor expired; reload the window"
        ));
        assert!(!acknowledge_revision(&core, id.clone(), version).unwrap());
        let fresh = window(&core, id.clone(), 4).unwrap();
        assert_eq!(
            fresh
                .cards
                .iter()
                .map(|c| c.result_key.as_str())
                .collect::<Vec<_>>(),
            vec!["d", "c", "b", "a"]
        );
        let info = fresh.searches.iter().find(|s| s.search_id == id).unwrap();
        assert!(info.unread_new_results);
        assert!(acknowledge_revision(&core, id.clone(), info.revision).unwrap());
        assert!(
            !list(&core)
                .unwrap()
                .iter()
                .find(|s| s.search_id == id)
                .unwrap()
                .unread_new_results
        );
    }

    #[test]
    fn rename_unsupported_preserves_raw_definition_and_imported_delta() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let raw = r#"{"city":"Leipzig","local":{"imageMode":"STUDIO"}}"#;
        let delta = r#"{"new_results_count":2,"removed_results_count":1,"current_result_count":7}"#;
        core.connection().unwrap().execute(
            "INSERT INTO saved_searches(saved_search_id,name,filters_json,last_delta_json,unread_new_results)
             VALUES ('legacy','Old',?1,?2,1)",
            params![raw, delta],
        ).unwrap();
        let before = definition(&core, "legacy").unwrap();
        rename(&core, "legacy".into(), "New".into()).unwrap();
        assert_eq!(definition(&core, "legacy").unwrap(), before);
        let info = list(&core).unwrap().remove(0);
        assert_eq!(info.name, "New");
        assert!(!info.definition_supported);
        assert!(info.unread_new_results);
        let delta = info.delta.unwrap();
        assert_eq!(
            (delta.new_count, delta.removed_count, delta.current_count),
            (2, 1, 7)
        );
    }

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
        let q = decode_definition(legacy).unwrap().query;
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
    fn explicit_local_rules_round_trip_and_change_definition_version() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let query = SearchRequest::for_test();
        let rules = LocalVisibility {
            hide_dislikes: true,
            ..LocalVisibility::default()
        };
        let id = save_with_local(&core, "Test".into(), query.clone(), rules.clone()).unwrap();
        let (_, initial) = definition(&core, &id).unwrap();
        assert_eq!(load_definition(&core, id.clone()).unwrap().local, rules);
        update_with_local(
            &core,
            id.clone(),
            "Renamed".into(),
            query.clone(),
            rules.clone(),
        )
        .unwrap();
        let (_, renamed) = definition(&core, &id).unwrap();
        assert_eq!(initial, renamed);
        let changed = LocalVisibility {
            hide_likes: true,
            ..rules
        };
        update_with_local(&core, id.clone(), "Renamed".into(), query, changed.clone()).unwrap();
        assert_eq!(load_definition(&core, id.clone()).unwrap().local, changed);
        assert_eq!(definition(&core, &id).unwrap().1, initial + 1);
    }

    #[test]
    fn imported_local_filters_survive_rename_and_source_edits() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let legacy = r#"{"city":"Leipzig","radius":20,"local":{"videoOnly":true,"hideDislikes":true,"imageMode":"AI"}}"#;
        core.connection().unwrap().execute(
            "INSERT INTO saved_searches (saved_search_id,name,filters_json) VALUES ('legacy','Imported',?1)",
            [legacy],
        ).unwrap();
        let loaded = load(&core, "legacy".into()).unwrap();
        update(&core, "legacy".into(), "Renamed".into(), loaded.clone()).unwrap();
        let (raw, _) = definition(&core, "legacy").unwrap();
        let renamed = decode_definition(&raw).unwrap();
        assert!(renamed.local.video_only);
        assert!(renamed.local.hide_dislikes);
        assert_eq!(renamed.local.image_mode, "AI");
        assert!(
            list(&core).unwrap()[0]
                .local_filter_summary
                .contains("Nur Video")
        );
        let mut changed = loaded;
        changed.radius_km = 30;
        update(&core, "legacy".into(), "Edited".into(), changed).unwrap();
        let (raw, _) = definition(&core, "legacy").unwrap();
        assert_eq!(decode_definition(&raw).unwrap().local, renamed.local);
    }

    #[test]
    fn imported_slug_filters_are_visible_and_stable_when_saved_again() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let legacy = r#"{"city":"Leipzig","radius":20,"category":"locations","selected":{"treffpunkt":["treffen-haus"],"service":["zungenkuss"]}}"#;
        core.connection().unwrap().execute(
            "INSERT INTO saved_searches (saved_search_id,name,filters_json) VALUES ('aliases','Legacy',?1)",
            [legacy],
        ).unwrap();
        let shown = load(&core, "aliases".into()).unwrap();
        assert_eq!(shown.category, "bordelle-laufhaeuser");
        let selected: serde_json::Value = serde_json::from_str(&shown.filters_json).unwrap();
        assert_eq!(selected["treff"], serde_json::json!(["Hausbesuche"]));
        assert_eq!(selected["service"], serde_json::json!(["ZK"]));
        update(&core, "aliases".into(), "Renamed".into(), shown.clone()).unwrap();
        let stored = load(&core, "aliases".into()).unwrap();
        assert_eq!(stored.filters_json, shown.filters_json);
        assert_eq!(stored.category, shown.category);
        assert!(list(&core).unwrap()[0].definition_supported);
    }

    #[test]
    fn unsupported_image_classifiers_cannot_be_applied_to_unclassified_cards() {
        for mode in ["STUDIO", "FACEAPP"] {
            let legacy = format!(r#"{{"city":"Leipzig","local":{{"imageMode":"{mode}"}}}}"#);
            assert!(decode_definition(&legacy).is_err(), "{mode}");
        }
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
            query: SearchRequest::for_test(),
            local: LocalVisibility::default(),
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
        let stored = results(&core, id.clone(), 100, None).unwrap().cards;
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
        let info = list(&core).unwrap().remove(0);
        assert!(acknowledge_revision(&core, id.clone(), info.revision).unwrap());
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
            query: SearchRequest::for_test(),
            local: LocalVisibility::default(),
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
        assert!(matches!(
            persist_results(&core, &id, &empty, &initial, initial_version),
            Err(CoreError::InvalidInput(reason))
                if reason == "Saved search changed during execution; stale results discarded"
        ));
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
    fn only_complete_radius_verified_search_replaces_saved_baseline() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let id = save(&core, "Leipzig".into(), SearchRequest::for_test()).unwrap();
        {
            let db = core.connection().unwrap();
            db.execute(
                "INSERT INTO saved_search_results(saved_search_id,result_key,snapshot_json,position,present,visible)
                 VALUES (?1,'old','{}',0,1,1)",
                [&id],
            )
            .unwrap();
            db.execute(
                "UPDATE saved_searches
                 SET last_run_at='2000-01-01',last_attempt_at='2000-01-01',
                     last_delta_json='{\"new\":1,\"removed\":0,\"current\":1,\"initial\":false}',
                     unread_new_results=1,new_results_at='2000-01-01'
                 WHERE saved_search_id=?1",
                [&id],
            )
            .unwrap();
        }
        let incomplete_error = "Search incomplete; saved results not updated";
        for (partial, radius_verified) in
            [(true, true), (false, false), (true, false), (false, true)]
        {
            let response = run_with_source(&core, id.clone(), None, |query| {
                Ok(SearchResponse {
                    query,
                    local: LocalVisibility::default(),
                    search_run_id: 1,
                    city: "Leipzig".into(),
                    requested_radius_km: 20,
                    server_count: Some(0),
                    total_pages: 1,
                    fetched_pages: 1,
                    partial,
                    radius_boundary_stopped: false,
                    radius_verified,
                    profiles: Vec::new(),
                })
            })
            .unwrap();
            assert_eq!(response.partial, partial);
            assert_eq!(response.radius_verified, radius_verified);
            let info = list(&core).unwrap().remove(0);
            let cards = results(&core, id.clone(), 100, None).unwrap().cards;
            let delta = info.delta.unwrap();
            assert!(info.unread_new_results);
            if partial || !radius_verified {
                assert_eq!(info.last_run_at.as_deref(), Some("2000-01-01"));
                assert_eq!(info.last_error.as_deref(), Some(incomplete_error));
                assert_eq!(cards.len(), 1);
                assert_eq!(cards[0].result_key, "old");
                assert_eq!(
                    (delta.new_count, delta.removed_count, delta.current_count),
                    (1, 0, 1)
                );
            } else {
                assert_ne!(info.last_run_at.as_deref(), Some("2000-01-01"));
                assert!(info.last_run_at.is_some());
                assert!(info.last_error.is_none());
                assert!(cards.is_empty());
                assert_eq!(
                    (delta.new_count, delta.removed_count, delta.current_count),
                    (0, 1, 0)
                );
            }
            let db = core.connection().unwrap();
            let (present, attempt, new_at): (bool, String, String) = db
                .query_row(
                    "SELECT r.present,s.last_attempt_at,s.new_results_at
                     FROM saved_search_results r JOIN saved_searches s USING(saved_search_id)
                     WHERE r.saved_search_id=?1 AND r.result_key='old'",
                    [&id],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                )
                .unwrap();
            assert_eq!(present, partial || !radius_verified);
            assert_ne!(attempt, "2000-01-01");
            assert_eq!(new_at, "2000-01-01");
        }
    }

    #[test]
    fn cancelled_or_failed_result_write_preserves_membership_and_baseline() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let id = save(&core, "Leipzig".into(), SearchRequest::for_test()).unwrap();
        let (definition, version) = definition(&core, &id).unwrap();
        {
            let db = core.connection().unwrap();
            db.execute(
                "INSERT INTO saved_search_results(saved_search_id,result_key,snapshot_json,position,present,visible)
                 VALUES (?1,'old','{}',0,1,1)",
                [&id],
            )
            .unwrap();
            db.execute(
                "UPDATE saved_searches SET last_run_at='2000-01-01',unread_new_results=1 WHERE saved_search_id=?1",
                [&id],
            )
            .unwrap();
        }
        let response = SearchResponse {
            query: SearchRequest::for_test(),
            local: LocalVisibility::default(),
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
        let baseline = || {
            let info = list(&core).unwrap().remove(0);
            assert_eq!(info.last_run_at.as_deref(), Some("2000-01-01"));
            assert!(info.unread_new_results);
            let cards = results(&core, id.clone(), 100, None).unwrap().cards;
            assert_eq!(cards.len(), 1);
            assert_eq!(cards[0].result_key, "old");
        };
        baseline();
        let control = RequestControl::default();
        control.cancel();
        assert!(matches!(
            persist_results_with_control(&core, &id, &response, &definition, version, Some(&control)),
            Err(CoreError::Source(reason)) if reason == "Operation cancelled"
        ));
        baseline();

        // Abort after result rows were changed, not before the transaction began.
        core.connection()
            .unwrap()
            .execute_batch(
                "CREATE TEMP TRIGGER reject_baseline_update BEFORE UPDATE OF last_run_at ON saved_searches
                 BEGIN SELECT RAISE(ABORT, 'fixture baseline rejection'); END;",
            )
            .unwrap();
        assert!(matches!(
            persist_results(&core, &id, &response, &definition, version),
            Err(CoreError::Storage(reason)) if reason.contains("fixture baseline rejection")
        ));
        baseline();
    }

    #[test]
    fn saved_result_image_is_a_coherent_local_remote_pair_with_archived_fallback() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("ladies_data");
        std::fs::create_dir(&root).unwrap();
        std::fs::write(root.join("current.jpg"), b"fixture").unwrap();
        std::fs::write(root.join("archived.jpg"), b"fixture").unwrap();
        let core = LadiesCore::new(
            tmp.path()
                .join("ladies.sqlite")
                .to_string_lossy()
                .into_owned(),
        )
        .unwrap();
        let person = core.create_person(Some("Fixture".into())).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "card".into(), None)
            .unwrap();
        let id = save(&core, "Saved".into(), SearchRequest::for_test()).unwrap();
        let db = core.connection().unwrap();
        for (fingerprint, url, file) in [
            (
                "old",
                "https://bilder1.ladies-cdn.de/old.jpg",
                "archived.jpg",
            ),
            (
                "new",
                "https://bilder1.ladies-cdn.de/new.jpg",
                "current.jpg",
            ),
        ] {
            db.execute("INSERT INTO listing_snapshots(listing_id,fingerprint,name,city) VALUES (?1,?2,'Fixture','Leipzig')", params![listing, fingerprint]).unwrap();
            let snapshot = db.last_insert_rowid();
            db.execute(
                "INSERT INTO media_assets(sha256,kind,local_path) VALUES (?1,'IMAGE',?2)",
                params![fingerprint, file],
            )
            .unwrap();
            let asset = db.last_insert_rowid();
            db.execute("INSERT INTO snapshot_media(snapshot_id,media_asset_id,position,kind,role,remote_url) VALUES (?1,?2,1,'IMAGE','PRIMARY',?3)", params![snapshot, asset, url]).unwrap();
            db.execute(
                "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
                params![snapshot, listing],
            )
            .unwrap();
        }
        db.execute("INSERT INTO saved_search_results(saved_search_id,result_key,snapshot_json,present,visible) VALUES (?1,'card',?2,1,1)",
            params![id, r#"{"name":"Old","thumbnail_url":"https://bilder1.ladies-cdn.de/old.jpg"}"#]).unwrap();
        drop(db);
        let current = window(&core, id.clone(), 100).unwrap().cards.remove(0);
        assert_eq!(
            current.thumbnail_url.as_deref(),
            Some("https://bilder1.ladies-cdn.de/new.jpg")
        );
        assert!(current.local_path.unwrap().ends_with("current.jpg"));
        core.connection()
            .unwrap()
            .execute(
                "DELETE FROM snapshot_media WHERE remote_url LIKE '%/new.jpg'",
                [],
            )
            .unwrap();
        let archived = window(&core, id, 100).unwrap().cards.remove(0);
        assert_eq!(
            archived.thumbnail_url.as_deref(),
            Some("https://bilder1.ladies-cdn.de/old.jpg")
        );
        assert!(archived.local_path.unwrap().ends_with("archived.jpg"));
    }

    #[test]
    fn saved_results_can_be_paged_beyond_the_previous_fixed_limit() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let id = save(&core, "Paging".into(), SearchRequest::for_test()).unwrap();
        let db = core.connection().unwrap();
        for position in 0..1105 {
            db.execute(
                "INSERT INTO saved_search_results
                 (saved_search_id,result_key,snapshot_json,position,present,visible)
                 VALUES (?1,?2,'{}',?3,1,1)",
                params![id, format!("item-{position:04}"), position],
            )
            .unwrap();
        }
        drop(db);
        let mut cursor = None;
        let mut keys = Vec::new();
        loop {
            let page = results(&core, id.clone(), 100, cursor).unwrap();
            assert!(!page.cards.is_empty());
            assert!(page.cards.len() <= 100);
            keys.extend(page.cards.into_iter().map(|card| card.result_key));
            assert!(keys.len() <= 1105);
            match page.next_cursor {
                Some(next) => cursor = Some(next),
                None => break,
            }
        }
        let expected: Vec<_> = (0..1105)
            .map(|position| format!("item-{position:04}"))
            .collect();
        assert_eq!(keys, expected);
    }

    #[test]
    fn repeated_empty_complete_results_do_not_create_new_alerts() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let id = save(&core, "test".into(), SearchRequest::for_test()).unwrap();
        let response = SearchResponse {
            query: SearchRequest::for_test(),
            local: LocalVisibility::default(),
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
