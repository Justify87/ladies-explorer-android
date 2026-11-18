use rusqlite::{OptionalExtension, params};
use serde_json::json;

use crate::{CoreError, LadiesCore, source::{self, SearchRequest, SearchResponse}};

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

pub(crate) fn save(core: &LadiesCore, name: String, request: SearchRequest) -> Result<String, CoreError> {
    source::filter::validate_request(&request)?;
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 100 {
        return Err(CoreError::InvalidInput("Search name must have 1–100 characters".into()));
    }
    let definition = serde_json::to_string(&request).map_err(|e| CoreError::Storage(e.to_string()))?;
    let db = core.connection()?;
    db.execute(
        "INSERT INTO saved_searches (saved_search_id,name,filters_json,definition_version)
         VALUES (lower(hex(randomblob(16))),?1,?2,2)",
        params![name,definition],
    )?;
    db.query_row(
        "SELECT saved_search_id FROM saved_searches WHERE rowid=last_insert_rowid()",
        [], |r| r.get(0),
    ).map_err(Into::into)
}

fn request(core: &LadiesCore, id: &str) -> Result<SearchRequest, CoreError> {
    let db = core.connection()?;
    let definition: String = db.query_row(
        "SELECT filters_json FROM saved_searches WHERE saved_search_id=?1",
        [id], |r| r.get(0),
    ).optional()?.ok_or_else(|| CoreError::InvalidInput("Saved search not found".into()))?;
    let request: SearchRequest = serde_json::from_str(&definition).map_err(|_| {
        CoreError::InvalidInput(
            "Imported search definition requires manual recreation with native filters".into()
        )
    })?;
    source::filter::validate_request(&request)?;
    Ok(request)
}

pub(crate) fn load(core: &LadiesCore, id: String) -> Result<SearchRequest, CoreError> {
    request(core,&id)
}

pub(crate) fn list(core: &LadiesCore) -> Result<Vec<SavedSearchInfo>, CoreError> {
    let db = core.connection()?;
    let mut query = db.prepare(
        "SELECT saved_search_id,name,filters_json,last_run_at,last_error,unread_new_results
         FROM saved_searches ORDER BY updated_at DESC,saved_search_id DESC"
    )?;
    let rows = query.query_map([],|r| {
        Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,
            r.get::<_,String>(2)?,r.get::<_,Option<String>>(3)?,
            r.get::<_,Option<String>>(4)?,r.get::<_,i64>(5)?))
    })?;
    rows.map(|row| {
        let (search_id,name,definition,last_run_at,last_error,unread) = row?;
        let parsed = serde_json::from_str::<SearchRequest>(&definition).ok();
        Ok(SavedSearchInfo {
            search_id,name,city:parsed.as_ref().map_or(String::new(),|r|r.city.clone()),
            radius_km:parsed.as_ref().map_or(0,|r|r.radius_km),
            last_run_at,last_error,unread_new_results:unread!=0,
            definition_supported:parsed.is_some(),
        })
    }).collect()
}

pub(crate) fn delete(core: &LadiesCore, id: String) -> Result<(), CoreError> {
    let db = core.connection()?;
    db.execute("DELETE FROM saved_searches WHERE saved_search_id=?1",[id])?;
    Ok(())
}

pub(crate) fn acknowledge(core: &LadiesCore, id: String) -> Result<(), CoreError> {
    let db = core.connection()?;
    db.execute(
        "UPDATE saved_searches SET unread_new_results=0 WHERE saved_search_id=?1",[id]
    )?;
    Ok(())
}

fn persist_results(core: &LadiesCore, id: &str, result: &SearchResponse) -> Result<(), CoreError> {
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    let existing: Option<String> = tx.query_row(
        "SELECT last_run_at FROM saved_searches WHERE saved_search_id=?1",
        [id], |r| r.get(0),
    ).optional()?.ok_or_else(|| CoreError::InvalidInput("Saved search was deleted".into()))?;
    let previous_present: i64 = tx.query_row(
        "SELECT COUNT(*) FROM saved_search_results WHERE saved_search_id=?1 AND present=1",
        [id], |r|r.get(0),
    )?;
    tx.execute(
        "UPDATE saved_search_results SET present=0 WHERE saved_search_id=?1",[id]
    )?;
    let mut newly_seen = 0u32;
    for (position, card) in result.profiles.iter().enumerate() {
        let key = format!("LADIES:{}",card.advert_id);
        let known: bool = tx.query_row(
            "SELECT EXISTS(SELECT 1 FROM saved_search_results
             WHERE saved_search_id=?1 AND result_key=?2)",
            params![id,key],|r|r.get(0),
        )?;
        if !known && existing.is_some() {
            newly_seen += 1;
        }
        let snapshot = json!({
            "listing_id":card.listing_id,
            "person_id":card.person_id,
            "name":card.name,
            "city":card.city,
            "profile_url":card.profile_url,
            "thumbnail_url":card.thumbnail_url,
        }).to_string();
        tx.execute(
            "INSERT INTO saved_search_results
             (saved_search_id,result_key,snapshot_json,position,present,visible,first_visible_at)
             VALUES (?1,?2,?3,?4,1,1,strftime('%Y-%m-%dT%H:%M:%fZ','now'))
             ON CONFLICT(saved_search_id,result_key) DO UPDATE SET
             snapshot_json=excluded.snapshot_json,position=excluded.position,
             present=1,visible=1,
             last_seen_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')",
            params![id,key,snapshot,position as i64],
        )?;
    }
    let removed = previous_present.saturating_sub(result.profiles.len() as i64 - newly_seen as i64);
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

pub(crate) fn run(core: &LadiesCore, id: String) -> Result<SearchResponse, CoreError> {
    let definition = request(core,&id)?;
    let fetched = source::search(core,definition);
    match fetched {
        Ok(response) => {
            // An incomplete search cannot be used to infer removals.
            if !response.partial && response.radius_verified {
                persist_results(core,&id,&response)?;
            } else {
                core.connection()?.execute(
                    "UPDATE saved_searches SET last_attempt_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),
                     last_error='Search incomplete; saved results not updated'
                     WHERE saved_search_id=?1",[&id]
                )?;
            }
            Ok(response)
        }
        Err(error) => {
            core.connection()?.execute(
                "UPDATE saved_searches SET last_attempt_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),
                 last_error=?2 WHERE saved_search_id=?1",
                params![id,error.to_string()],
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
        let id = save(&core,"Leipzig Zentrum".into(),req).unwrap();
        assert_eq!(load(&core,id.clone()).unwrap().city,"Leipzig");
        assert!(list(&core).unwrap()[0].definition_supported);
        core.connection().unwrap().execute(
            "INSERT INTO saved_searches(saved_search_id,name,filters_json)
             VALUES ('imported','Old','{\"legacy\":true}')",[],
        ).unwrap();
        assert_eq!(list(&core).unwrap().len(),2);
        assert!(load(&core,"imported".into()).is_err());
        delete(&core,id).unwrap();
        assert_eq!(list(&core).unwrap().len(),1);
    }

    #[test]
    fn saved_result_changes_are_atomic_and_old_absences_need_complete_search() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let id = save(&core,"test".into(),SearchRequest::for_test()).unwrap();
        let response = SearchResponse {
            search_run_id:1,city:"Leipzig".into(),requested_radius_km:20,
            server_count:Some(0),total_pages:1,fetched_pages:1,
            partial:false,radius_boundary_stopped:false,radius_verified:true,
            profiles:Vec::new(),
        };
        persist_results(&core,&id,&response).unwrap();
        assert!(!list(&core).unwrap()[0].unread_new_results);
        persist_results(&core,&id,&response).unwrap();
        assert!(!list(&core).unwrap()[0].unread_new_results);
    }
}
