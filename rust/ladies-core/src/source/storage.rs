use rusqlite::{Connection, OptionalExtension, params};
use serde_json::json;
use url::Url;

use crate::{CoreError, LadiesCore, history, identity, operation::RequestControl};

use super::{Card, Profile, ProfileResponse, SearchCard, SearchRequest, SearchResponse};
use super::{network::DownloadedSearch, parse};

#[cfg(test)]
pub(super) fn save_search(
    core: &LadiesCore,
    request: &SearchRequest,
    downloaded: DownloadedSearch,
) -> Result<SearchResponse, CoreError> {
    save_search_with_control(core, request, downloaded, None)
}

pub(super) fn save_search_with_control(
    core: &LadiesCore,
    request: &SearchRequest,
    downloaded: DownloadedSearch,
    control: Option<&RequestControl>,
) -> Result<SearchResponse, CoreError> {
    if let Some(control) = control {
        control.check()?;
    }
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    let filters = json!({
        "category": request.category,
        "age_min": request.age_min,
        "age_max": request.age_max,
        "height_min": request.height_min,
        "height_max": request.height_max,
        "selected": serde_json::from_str::<serde_json::Value>(&request.filters_json)
            .map_err(|e| CoreError::InvalidInput(format!("Invalid filters JSON: {e}")))?,
    });
    let metadata = json!({
        "requested_radius_km": request.radius_km,
        "partial": downloaded.partial,
        "radius_boundary_stopped": downloaded.radius_boundary_stopped,
        "server_advert_count": downloaded.server_count,
        "total_pages": downloaded.total_pages,
        "fetched_pages": downloaded.fetched_pages,
        "radius_verified": downloaded.radius_verified,
    });
    tx.execute(
        "INSERT INTO search_runs
         (city, city_slug, radius_km, filters_json, advert_count, distance_searched,
          metadata_json, finished_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, strftime('%Y-%m-%dT%H:%M:%fZ','now'))",
        params![
            downloaded.city,
            downloaded.city_slug,
            request.radius_km,
            filters.to_string(),
            downloaded.server_count,
            downloaded.radius_verified.then_some(request.radius_km),
            metadata.to_string(),
        ],
    )?;
    let run_id = tx.last_insert_rowid();
    let mut profiles = Vec::with_capacity(downloaded.cards.len());
    for (position, card) in downloaded.cards.iter().enumerate() {
        if let Some(control) = control {
            control.check()?;
        }
        let (listing_id, person_id) = upsert_listing(&tx, card)?;
        tx.execute(
            "INSERT INTO search_results
             (search_run_id, listing_id, position, name, city, thumbnail_url,
              has_video, is_premium, has_ai_content, markers_json)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",
            params![
                run_id,
                listing_id,
                (position + 1) as i64,
                card.name,
                card.city,
                card.thumbnail_url,
                card.has_video,
                card.is_premium,
                card.has_ai_content,
                json!({
                    "has_18plus": card.has_18plus,
                    "has_stars": card.has_stars,
                })
                .to_string(),
            ],
        )?;
        tx.execute(
            "INSERT INTO listing_observations
             (listing_id, source, status, search_run_id, details_json)
             VALUES (?1,'SEARCH','SEEN',?2,?3)",
            params![
                listing_id,
                run_id,
                json!({
                    "position": position + 1, "name": card.name, "city": card.city
                })
                .to_string()
            ],
        )?;
        profiles.push(SearchCard {
            listing_id,
            person_id,
            advert_id: card.advert_id.clone(),
            name: card.name.clone(),
            city: card.city.clone(),
            category: card.category.clone(),
            profile_url: card.profile_url.clone(),
            thumbnail_url: card.thumbnail_url.clone(),
            has_video: card.has_video,
            is_premium: card.is_premium,
            has_ai_content: card.has_ai_content,
            has_18plus: card.has_18plus,
            has_stars: card.has_stars,
        });
    }
    if let Some(control) = control {
        control.check()?;
    }
    tx.commit()?;
    Ok(SearchResponse {
        query: request.clone(),
        local: crate::saved_search::LocalVisibility::default(),
        search_run_id: run_id,
        city: downloaded.city,
        requested_radius_km: request.radius_km,
        server_count: downloaded.server_count,
        total_pages: downloaded.total_pages,
        fetched_pages: downloaded.fetched_pages,
        partial: downloaded.partial,
        radius_boundary_stopped: downloaded.radius_boundary_stopped,
        radius_verified: downloaded.radius_verified,
        profiles,
    })
}

fn upsert_listing(db: &Connection, card: &Card) -> Result<(i64, i64), CoreError> {
    let existing: Option<(i64, i64)> = db
        .query_row(
            "SELECT listing_id, person_id FROM listings
             WHERE source='LADIES' AND external_listing_id=?1",
            params![card.advert_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;
    if let Some((listing_id, person_id)) = existing {
        db.execute(
            "UPDATE listings SET
                 last_seen_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),
                 current_url=?1, category=?2
             WHERE listing_id=?3",
            params![card.profile_url, card.category, listing_id],
        )?;
        record_status(db, listing_id, person_id, "ACTIVE")?;
        db.execute(
            "UPDATE persons SET display_name=COALESCE(NULLIF(?1,''), display_name),
                 last_seen_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),
                 updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
             WHERE person_id=?2",
            params![card.name, person_id],
        )?;
        Ok((listing_id, person_id))
    } else {
        db.execute(
            "INSERT INTO persons(display_name) VALUES (NULLIF(?1,''))",
            params![card.name],
        )?;
        let person_id = db.last_insert_rowid();
        db.execute(
            "INSERT INTO listings
             (person_id, source, external_listing_id, current_url, category,
              current_status, status_checked_at, match_decision)
             VALUES (?1,'LADIES',?2,?3,?4,'ACTIVE',strftime('%Y-%m-%dT%H:%M:%fZ','now'),'UNRESOLVED')",
            params![person_id, card.advert_id, card.profile_url, card.category],
        )?;
        Ok((db.last_insert_rowid(), person_id))
    }
}

// Capture target before I/O; reject outdated observations under the write lock.
#[derive(Debug, PartialEq, Eq)]
pub(super) struct ListingTarget {
    pub listing_id: i64,
    pub url: String,
    pub advert_id: String,
    pub name: String,
    pub city: String,
    pub category: String,
    person_id: i64,
    observation_id: i64,
}

pub(super) fn saved_status_targets(core: &LadiesCore) -> Result<Vec<i64>, CoreError> {
    let db = core.connection()?;
    let mut stmt = db.prepare(
        "SELECT l.listing_id FROM listings l
         JOIN person_user_state u ON u.person_id=l.person_id
         WHERE l.source='LADIES' AND (u.favorite=1 OR u.preference!='NONE')
         ORDER BY l.last_seen_at DESC,l.listing_id DESC",
    )?;
    let rows = stmt.query_map([], |row| row.get(0))?;
    Ok(rows.collect::<Result<Vec<_>, _>>()?)
}

fn listing_target_in(
    db: &Connection,
    listing_id: i64,
    saved_only: bool,
) -> Result<ListingTarget, CoreError> {
    let row = db
        .query_row(
            "SELECT l.current_url,l.external_listing_id,l.person_id,
                    COALESCE((SELECT MAX(o.observation_id) FROM listing_observations o
                              WHERE o.listing_id=l.listing_id),0),
                    COALESCE(p.display_name,''),
                    COALESCE((SELECT city FROM search_results WHERE listing_id=l.listing_id
                              ORDER BY observed_at DESC,search_result_id DESC LIMIT 1),''),
                    COALESCE(l.category,'')
             FROM listings l JOIN person_user_state u ON u.person_id=l.person_id
             JOIN persons p ON p.person_id=l.person_id
             WHERE l.listing_id=?1 AND l.source='LADIES'
               AND (?2=0 OR u.favorite=1 OR u.preference!='NONE')",
            params![listing_id, saved_only],
            |r| {
                Ok((
                    r.get::<_, Option<String>>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, i64>(2)?,
                    r.get::<_, i64>(3)?,
                    r.get::<_, String>(4)?,
                    r.get::<_, String>(5)?,
                    r.get::<_, String>(6)?,
                ))
            },
        )
        .optional()?
        .ok_or(CoreError::ListingNotFound(listing_id))?;
    let url = row
        .0
        .and_then(|url| parse::valid_profile_url(&url))
        .ok_or_else(|| {
            CoreError::Source("Stored listing has no recognized source profile URL".into())
        })?;
    Ok(ListingTarget {
        listing_id,
        url,
        advert_id: row.1,
        person_id: row.2,
        observation_id: row.3,
        name: row.4,
        city: row.5,
        category: row.6,
    })
}

pub(super) fn listing_target(
    core: &LadiesCore,
    listing_id: i64,
    saved_only: bool,
) -> Result<ListingTarget, CoreError> {
    let db = core.connection()?;
    listing_target_in(&db, listing_id, saved_only)
}

fn validate_target(
    db: &Connection,
    target: &ListingTarget,
    saved_only: bool,
) -> Result<(), CoreError> {
    let current = listing_target_in(db, target.listing_id, saved_only)?;
    if current.person_id != target.person_id
        || current.url != target.url
        || current.advert_id != target.advert_id
        || current.observation_id != target.observation_id
    {
        return Err(CoreError::Source(
            "Listing changed during source request".into(),
        ));
    }
    Ok(())
}

// Status-only observations never change content observation order.
fn record_status(
    db: &Connection,
    listing_id: i64,
    person_id: i64,
    status: &str,
) -> Result<(), CoreError> {
    let previous: String = db.query_row(
        "SELECT current_status FROM listings WHERE listing_id=?1",
        [listing_id],
        |r| r.get(0),
    )?;
    db.execute(
        "UPDATE listings SET current_status=?1,
             status_checked_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),
             ended_at=CASE WHEN ?1='ACTIVE' THEN NULL
                           ELSE COALESCE(ended_at,strftime('%Y-%m-%dT%H:%M:%fZ','now')) END
         WHERE listing_id=?2",
        params![status, listing_id],
    )?;
    if previous != status {
        db.execute(
            "INSERT INTO change_events(person_id,listing_id,event_type,field_key,old_json,new_json)
             VALUES (?1,?2,'STATUS_CHANGED','status',?3,?4)",
            params![
                person_id,
                listing_id,
                json!(previous).to_string(),
                json!(status).to_string()
            ],
        )?;
    }
    Ok(())
}

pub(super) fn save_status(
    core: &LadiesCore,
    target: &ListingTarget,
    status: &str,
    http_status: u16,
    saved_only: bool,
    control: Option<&RequestControl>,
) -> Result<(), CoreError> {
    if let Some(control) = control {
        control.check()?;
    }
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    validate_target(&tx, target, saved_only)?;
    record_status(&tx, target.listing_id, target.person_id, status)?;
    tx.execute(
        "INSERT INTO listing_observations(listing_id,source,status,http_status)
         VALUES (?1,?2,?3,?4)",
        params![
            target.listing_id,
            if saved_only { "STATUS" } else { "PROFILE" },
            status,
            http_status
        ],
    )?;
    if let Some(control) = control {
        control.check()?;
    }
    tx.commit()?;
    Ok(())
}

fn record_profile_identity_conflict(
    db: &Connection,
    person_id: i64,
    listing_id: i64,
    external_id: &str,
    candidate_person_id: Option<i64>,
    kind: &str,
) -> Result<(), CoreError> {
    let already_recorded: bool = db.query_row(
        "SELECT EXISTS(
            SELECT 1 FROM identity_evidence
            WHERE listing_id = ?1 AND person_id = ?2
              AND candidate_person_id IS ?3 AND kind = ?4 AND value = ?5
        )",
        params![
            listing_id,
            person_id,
            candidate_person_id,
            kind,
            external_id
        ],
        |row| row.get(0),
    )?;
    if !already_recorded {
        db.execute(
            "INSERT INTO identity_evidence
             (listing_id, person_id, candidate_person_id, kind, value)
             VALUES (?1,?2,?3,?4,?5)",
            params![
                listing_id,
                person_id,
                candidate_person_id,
                kind,
                external_id
            ],
        )?;
    }
    Ok(())
}

/// Identity evidence may be ambiguous; extraction must still persist independently.
fn link_source_profile_or_record_conflict(
    db: &Connection,
    person_id: i64,
    listing_id: i64,
    external_id: &str,
    canonical_id: Option<&str>,
) -> Result<(), CoreError> {
    let existing: Option<(i64, i64)> = db
        .query_row(
            "SELECT source_profile_id, person_id FROM source_profiles
             WHERE source='LADIES' AND external_profile_id=?1",
            [external_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;
    let current_profile: Option<i64> = db.query_row(
        "SELECT source_profile_id FROM listings WHERE listing_id=?1",
        [listing_id],
        |row| row.get(0),
    )?;

    if let Some((_, another_person)) = existing.filter(|(_, owner)| *owner != person_id) {
        return record_profile_identity_conflict(
            db,
            person_id,
            listing_id,
            external_id,
            Some(another_person),
            "SOURCE_PROFILE_OWNER_CONFLICT",
        );
    }
    if let (Some(current), Some((new_profile, _))) = (current_profile, existing) {
        if current != new_profile {
            return record_profile_identity_conflict(
                db,
                person_id,
                listing_id,
                external_id,
                None,
                "SOURCE_PROFILE_REASSIGNMENT_CONFLICT",
            );
        }
    } else if current_profile.is_some() && existing.is_none() {
        return record_profile_identity_conflict(
            db,
            person_id,
            listing_id,
            external_id,
            None,
            "SOURCE_PROFILE_REASSIGNMENT_CONFLICT",
        );
    }
    let profile_id = if let Some((id, _)) = existing {
        db.execute(
            "UPDATE source_profiles
             SET canonical_id = COALESCE(?1, canonical_id),
                 last_seen_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
             WHERE source_profile_id = ?2",
            params![canonical_id, id],
        )?;
        id
    } else {
        db.execute(
            "INSERT INTO source_profiles
             (person_id,source,external_profile_id,canonical_id,link_reason)
             VALUES (?1,'LADIES',?2,?3,'SITE_INTERNAL_ID')",
            params![person_id, external_id, canonical_id],
        )?;
        db.last_insert_rowid()
    };
    db.execute(
        "UPDATE listings SET source_profile_id=?1 WHERE listing_id=?2",
        params![profile_id, listing_id],
    )?;
    Ok(())
}

#[cfg(test)]
pub(super) fn save_profile(
    core: &LadiesCore,
    listing_id: i64,
    url: &str,
    profile: Profile,
) -> Result<ProfileResponse, CoreError> {
    let target = listing_target(core, listing_id, false)?;
    assert_eq!(target.url, url);
    save_observed_profile(core, &target, profile, true, None)
}

pub(super) fn save_observed_profile(
    core: &LadiesCore,
    target: &ListingTarget,
    profile: Profile,
    verified: bool,
    control: Option<&RequestControl>,
) -> Result<ProfileResponse, CoreError> {
    if let Some(control) = control {
        control.check()?;
    }
    let listing_id = target.listing_id;
    let url = &target.url;
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    validate_target(&tx, target, false)?;
    let row: Option<(i64, Option<String>)> = tx
        .query_row(
            "SELECT person_id, category FROM listings WHERE listing_id=?1 AND source='LADIES'
         AND external_listing_id=?2",
            params![listing_id, profile.advert_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;
    let Some((person_id, category)) = row else {
        return Err(CoreError::ListingNotFound(listing_id));
    };
    let fingerprint = parse::fingerprint(&profile, category.as_deref());
    if let Some(external_id) = profile.source_profile_id.as_deref() {
        link_source_profile_or_record_conflict(
            &tx,
            person_id,
            listing_id,
            external_id,
            profile.canonical_id.as_deref(),
        )?;
    }
    let active_snapshot_before: Option<i64> = tx.query_row(
        "SELECT current_snapshot_id FROM listings WHERE listing_id=?1",
        [listing_id],
        |row| row.get(0),
    )?;
    let previous: Option<i64> = tx
        .query_row(
            "SELECT snapshot_id FROM listing_snapshots WHERE listing_id=?1 AND fingerprint=?2",
            params![listing_id, fingerprint],
            |row| row.get(0),
        )
        .optional()?;
    let new_snapshot = previous.is_none();
    let snapshot_id = if let Some(id) = previous {
        id
    } else {
        tx.execute(
            "INSERT INTO listing_snapshots
             (listing_id, fingerprint, source_url, name, city, postal_code,
              hidden_location, category, description, published_at, external_profile_id,
              canonical_id, phone_display, traits_json, services_json, opening_times_json)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16)",
            params![
                listing_id,
                fingerprint,
                url,
                profile.name,
                profile.city,
                profile.postal_code,
                profile.hidden_location,
                category,
                profile.description,
                profile.published_at,
                profile.source_profile_id,
                profile.canonical_id,
                profile.phone_display,
                profile.traits_json,
                profile.services_json,
                profile.opening_times_json,
            ],
        )?;
        let id = tx.last_insert_rowid();
        for (kind, json_source) in [
            ("TRAIT", &profile.traits_json),
            ("SERVICE", &profile.services_json),
        ] {
            let parsed: serde_json::Value = serde_json::from_str(json_source)
                .map_err(|e| CoreError::Source(format!("Invalid parsed attributes: {e}")))?;
            if let Some(groups) = parsed.as_object() {
                let mut position = 0;
                for (label, values) in groups {
                    if let Some(values) = values.as_array() {
                        for value in values.iter().filter_map(|v| v.as_str()) {
                            position += 1;
                            tx.execute(
                                "INSERT INTO snapshot_values
                                 (snapshot_id,kind,group_name,label,value,position)
                                 VALUES (?1,?2,?3,?3,?4,?5)",
                                params![id, kind, label, value, position],
                            )?;
                        }
                    }
                }
            }
        }
        let opening: serde_json::Value = serde_json::from_str(&profile.opening_times_json)
            .map_err(|e| CoreError::Source(format!("Invalid parsed opening times: {e}")))?;
        if let Some(map) = opening.as_object() {
            for (idx, (day, value)) in map.iter().enumerate() {
                if let Some(value) = value.as_str() {
                    tx.execute(
                        "INSERT INTO snapshot_values
                         (snapshot_id,kind,group_name,label,value,position,provenance,confidence_rank)
                         VALUES (?1,'OPENING','Öffnungszeiten',?2,?3,?4,'DOM_VISIBLE_OR_MICRODATA',95)",
                        params![id, day, value, (idx + 1) as i64],
                    )?;
                }
            }
        }
        // Only remote references in M03. Asset downloads and historic image comparison belong to M04.
        for reference in &profile.media {
            let mut media_base = Url::parse(&reference.url)
                .map_err(|_| CoreError::Source("Invalid parsed media URL".into()))?;
            media_base.set_query(None);
            media_base.set_fragment(None);
            tx.execute(
                "INSERT INTO snapshot_media
                 (snapshot_id,position,kind,role,remote_url,remote_base_url,
                  remote_variant,revision_token)
                 VALUES (?1,?2,?3,?4,?5,?6,?7,?8)",
                params![
                    id,
                    reference.position,
                    reference.kind,
                    reference.role,
                    reference.url,
                    media_base.as_str(),
                    reference.variant,
                    reference.revision_token,
                ],
            )?;
        }
        id
    };
    tx.execute(
        "UPDATE listing_snapshots SET normalized_phone=?1 WHERE snapshot_id=?2",
        params![
            identity::normalize_phone(profile.phone_display.as_deref()),
            snapshot_id
        ],
    )?;
    identity::record_phone(&tx, person_id, listing_id, profile.phone_display.as_deref())?;
    if verified {
        record_status(&tx, listing_id, person_id, "ACTIVE")?;
    }
    history::record_transition(
        &tx,
        person_id,
        listing_id,
        active_snapshot_before,
        snapshot_id,
    )?;
    tx.execute(
        "UPDATE listings
         SET current_snapshot_id=?1, current_url=?2, last_seen_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
         WHERE listing_id=?3",
        params![snapshot_id, url, listing_id],
    )?;
    tx.execute(
        "UPDATE persons SET display_name=COALESCE(NULLIF(?1,''), display_name),
          last_seen_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),
          updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
         WHERE person_id=?2",
        params![profile.name, person_id],
    )?;
    identity::refresh(&tx, listing_id)?;
    tx.execute(
        "INSERT INTO listing_observations
         (listing_id, source, status, http_status, snapshot_id, details_json)
         VALUES (?1,'PROFILE',?2,200,?3,?4)",
        params![
            listing_id,
            if verified { "ACTIVE" } else { "UNKNOWN" },
            snapshot_id,
            json!({
                "snapshot_changed": active_snapshot_before != Some(snapshot_id),
                "new_snapshot_created": new_snapshot,
                "fingerprint": fingerprint,
            })
            .to_string()
        ],
    )?;
    if let Some(control) = control {
        control.check()?;
    }
    tx.commit()?;
    Ok(ProfileResponse {
        listing_id,
        snapshot_id,
        new_snapshot,
        advert_id: profile.advert_id,
        name: profile.name,
        city: profile.city,
        description: profile.description,
        postal_code: profile.postal_code,
        hidden_location: profile.hidden_location,
        source_profile_id: profile.source_profile_id,
        canonical_id: profile.canonical_id,
        phone_display: profile.phone_display,
        published_at: profile.published_at,
        traits_json: profile.traits_json,
        services_json: profile.services_json,
        opening_times_json: profile.opening_times_json,
        media: profile.media,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::source::{MediaReference, SearchRequest, network::DownloadedSearch};
    use std::sync::Arc;

    fn core() -> Arc<LadiesCore> {
        LadiesCore::new(":memory:".into()).unwrap()
    }
    fn card(id: &str, name: &str) -> Card {
        Card {
            advert_id: id.into(),
            name: name.into(),
            city: "Leipzig".into(),
            category: "huren hostessen".into(),
            profile_url: format!("https://www.ladies.de/sex-anzeigen/huren-hostessen/{id}"),
            thumbnail_url: None,
            has_video: false,
            is_premium: false,
            has_ai_content: false,
            has_18plus: false,
            has_stars: false,
        }
    }

    fn save_cards(core: &LadiesCore, cards: Vec<Card>) -> SearchResponse {
        save_search(
            core,
            &SearchRequest::for_test(),
            DownloadedSearch {
                cards,
                city: "Leipzig".into(),
                city_slug: "leipzig".into(),
                server_count: Some(1),
                total_pages: 1,
                fetched_pages: 1,
                partial: false,
                radius_boundary_stopped: false,
                radius_verified: true,
            },
        )
        .unwrap()
    }

    #[test]
    fn saved_status_records_observations_and_rejects_stale_updates() {
        let core = core();
        let found = save_cards(&core, vec![card("101", "Fixture"), card("102", "Other")]);
        let listing = found.profiles[0].listing_id;
        let person = found.profiles[0].person_id;
        core.set_favorite(person, true).unwrap();
        core.set_preference(person, crate::Preference::Like)
            .unwrap();
        assert_eq!(saved_status_targets(&core).unwrap(), vec![listing]);
        let target = listing_target(&core, listing, true).unwrap();
        let control = RequestControl::default();
        save_status(&core, &target, "UNAVAILABLE", 404, true, Some(&control)).unwrap();
        assert!(save_status(&core, &target, "ACTIVE", 200, true, Some(&control)).is_err());
        let new_target = listing_target(&core, listing, true).unwrap();
        let cancelled = RequestControl::default();
        cancelled.cancel();
        assert!(save_status(&core, &new_target, "ACTIVE", 200, true, Some(&cancelled)).is_err());
        {
            let db = core.connection().unwrap();
            let status: (String, Option<String>, Option<String>) = db
                .query_row(
                    "SELECT current_status,status_checked_at,ended_at FROM listings
                     WHERE listing_id=?1",
                    [listing],
                    |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
                )
                .unwrap();
            assert_eq!(status.0, "UNAVAILABLE");
            assert!(status.1.is_some() && status.2.is_some());
            let observations: i64 = db
                .query_row(
                    "SELECT COUNT(*) FROM listing_observations WHERE listing_id=?1 AND source='STATUS'",
                    [listing],
                    |r| r.get(0),
                )
                .unwrap();
            assert_eq!(observations, 1);
        }
        save_status(&core, &new_target, "ACTIVE", 200, true, Some(&control)).unwrap();
        let db = core.connection().unwrap();
        let count: i64 = db
            .query_row(
                "SELECT COUNT(*) FROM change_events WHERE listing_id=?1 AND event_type='STATUS_CHANGED'",
                [listing],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(count, 2);
    }

    #[test]
    fn status_only_checks_do_not_reorder_and_detail_evidence_is_consistent() {
        let core = core();
        let found = save_cards(&core, vec![card("101", "Fixture")]);
        let listing = found.profiles[0].listing_id;
        let person = found.profiles[0].person_id;
        let older = core
            .add_listing(
                person,
                None,
                "LADIES".into(),
                "102".into(),
                Some("https://www.ladies.de/sex-anzeigen/huren-hostessen/102".into()),
            )
            .unwrap();
        core.set_favorite(person, true).unwrap();
        {
            let db = core.connection().unwrap();
            db.execute(
                "UPDATE listings SET last_seen_at='2026-01-01T00:00:00Z' WHERE listing_id=?1",
                [older],
            )
            .unwrap();
        }
        let control = RequestControl::default();
        for _ in 0..2 {
            for id in saved_status_targets(&core).unwrap() {
                let target = listing_target(&core, id, true).unwrap();
                save_status(&core, &target, "ACTIVE", 200, true, Some(&control)).unwrap();
            }
            let page = core
                .saved_people_window(
                    crate::SavedPersonKind::Favorite,
                    crate::SavedPersonSort::Name,
                    50,
                    50,
                )
                .unwrap();
            assert_eq!(
                page.regular.rows[0].card.as_ref().unwrap().listing_id,
                listing
            );
        }
        let stale = listing_target(&core, listing, false).unwrap();
        save_status(&core, &stale, "UNAVAILABLE", 404, false, None).unwrap();
        assert!(save_observed_profile(&core, &stale, profile("Fixture"), true, None).is_err());
        let target = listing_target(&core, listing, false).unwrap();
        save_observed_profile(&core, &target, profile("Fixture"), false, None).unwrap();
        {
            let db = core.connection().unwrap();
            let state: String = db
                .query_row(
                    "SELECT current_status FROM listings WHERE listing_id=?1",
                    [listing],
                    |r| r.get(0),
                )
                .unwrap();
            assert_eq!(state, "UNAVAILABLE");
        }
        let target = listing_target(&core, listing, false).unwrap();
        save_observed_profile(&core, &target, profile("Fixture"), true, None).unwrap();
        {
            let db = core.connection().unwrap();
            let state: (String, Option<String>, Option<String>) = db.query_row(
                "SELECT current_status,status_checked_at,ended_at FROM listings WHERE listing_id=?1",
                [listing], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?)),
            ).unwrap();
            assert_eq!(state.0, "ACTIVE");
            assert!(state.1.is_some() && state.2.is_none());
            let count: i64 = db.query_row(
                "SELECT COUNT(*) FROM change_events WHERE listing_id=?1 AND event_type='STATUS_CHANGED'",
                [listing], |r| r.get(0),
            ).unwrap();
            assert_eq!(count, 2);
        }
    }

    fn profile(name: &str) -> Profile {
        Profile {
            advert_id: "101".into(),
            name: name.into(),
            city: "Leipzig".into(),
            description: "Hello".into(),
            postal_code: Some("04229".into()),
            hidden_location: None,
            source_profile_id: Some("source-id-101".into()),
            canonical_id: None,
            phone_display: Some("+49 123".into()),
            published_at: None,
            traits_json: r#"{"Figur":["Schlank"]}"#.into(),
            services_json: r#"{"Service":["ZK"]}"#.into(),
            opening_times_json: r#"{"Montag":"10-18"}"#.into(),
            media: vec![MediaReference {
                url: "https://bilder1.ladies-cdn.de/Bilder/1/100-A1.JPG?h=1".into(),
                position: 1,
                kind: "IMAGE".into(),
                role: "PRIMARY".into(),
                variant: Some("A".into()),
                revision_token: Some("1".into()),
            }],
        }
    }

    #[test]
    fn search_is_transactional_and_preserves_user_state() {
        let core = core();
        let first = save_cards(&core, vec![card("101", "Anna"), card("102", "Mia")]);
        assert_eq!(first.profiles.len(), 2);
        assert_eq!(core.status().unwrap().person_count, 2);
        core.set_favorite(first.profiles[0].person_id, true)
            .unwrap();
        let again = save_cards(&core, vec![card("101", "Anna new")]);
        assert_eq!(again.profiles[0].listing_id, first.profiles[0].listing_id);
        assert_eq!(again.profiles[0].person_id, first.profiles[0].person_id);
        assert!(
            core.get_person(first.profiles[0].person_id)
                .unwrap()
                .unwrap()
                .favorite
        );
        assert_eq!(core.status().unwrap().person_count, 2);
        let db = core.connection().unwrap();
        let count: i64 = db
            .query_row("SELECT COUNT(*) FROM search_runs", [], |r| r.get(0))
            .unwrap();
        assert_eq!(count, 2);
    }

    #[test]
    fn category_only_change_produces_snapshot_and_history() {
        let core = core();
        let found = save_cards(&core, vec![card("101", "Anna")]);
        let listing = found.profiles[0].listing_id;
        let first = save_profile(
            &core,
            listing,
            &found.profiles[0].profile_url,
            profile("Anna"),
        )
        .unwrap();
        {
            let db = core.connection().unwrap();
            db.execute(
                "UPDATE listings SET category='new-category' WHERE listing_id=?1",
                [listing],
            )
            .unwrap();
        }
        let second = save_profile(
            &core,
            listing,
            &found.profiles[0].profile_url,
            profile("Anna"),
        )
        .unwrap();
        assert_ne!(first.snapshot_id, second.snapshot_id);
        let changes = core
            .list_change_events(found.profiles[0].person_id, 20)
            .unwrap();
        assert!(
            changes
                .iter()
                .any(|c| c.field_key.as_deref() == Some("category"))
        );
    }

    #[test]
    fn profile_snapshot_is_idempotent_and_creates_history_for_change() {
        let core = core();
        let found = save_cards(&core, vec![card("101", "Anna")]);
        let listing = found.profiles[0].listing_id;
        let first = save_profile(
            &core,
            listing,
            &found.profiles[0].profile_url,
            profile("Anna"),
        )
        .unwrap();
        assert!(first.new_snapshot);
        let same = save_profile(
            &core,
            listing,
            &found.profiles[0].profile_url,
            profile("Anna"),
        )
        .unwrap();
        assert_eq!(same.snapshot_id, first.snapshot_id);
        assert!(!same.new_snapshot);
        let changed = save_profile(
            &core,
            listing,
            &found.profiles[0].profile_url,
            profile("Anna 2"),
        )
        .unwrap();
        assert_ne!(first.snapshot_id, changed.snapshot_id);
        assert!(changed.new_snapshot);
        let db = core.connection().unwrap();
        let count: i64 = db
            .query_row("SELECT COUNT(*) FROM snapshot_media", [], |r| r.get(0))
            .unwrap();
        assert_eq!(count, 2);
        let events: (i64, i64, i64) = db
            .query_row(
                "SELECT COUNT(*),
                SUM(CASE WHEN event_type='PROFILE_CREATED' THEN 1 ELSE 0 END),
                SUM(CASE WHEN event_type='FIELD_CHANGED' AND field_key='name' THEN 1 ELSE 0 END)
             FROM change_events WHERE listing_id=?1",
                [listing],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .unwrap();
        assert_eq!(events, (2, 1, 1));
    }

    #[test]
    fn conflicting_source_identity_preserves_snapshots_and_person_ownership() {
        let core = core();
        let found = save_cards(&core, vec![card("101", "Anna"), card("102", "Mia")]);
        let first = found.profiles[0].listing_id;
        let second = found.profiles[1].listing_id;
        let second_person = found.profiles[1].person_id;
        core.set_favorite(second_person, true).unwrap();
        save_profile(
            &core,
            first,
            &found.profiles[0].profile_url,
            profile("Anna"),
        )
        .unwrap();

        let mut other = profile("Mia");
        other.advert_id = "102".into();
        let saved =
            save_profile(&core, second, &found.profiles[1].profile_url, other.clone()).unwrap();
        assert!(saved.new_snapshot);
        let again = save_profile(&core, second, &found.profiles[1].profile_url, other).unwrap();
        assert_eq!(saved.snapshot_id, again.snapshot_id);
        assert!(!again.new_snapshot);

        let db = core.connection().unwrap();
        let (owner, link): (i64, Option<i64>) = db
            .query_row(
                "SELECT person_id, source_profile_id FROM listings WHERE listing_id=?1",
                [second],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(owner, second_person);
        assert!(link.is_none());
        let snapshot_count: i64 = db
            .query_row(
                "SELECT COUNT(*) FROM listing_snapshots WHERE listing_id=?1",
                [second],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(snapshot_count, 1);
        let evidence: (i64, i64) = db
            .query_row(
                "SELECT COUNT(*), MAX(candidate_person_id)
             FROM identity_evidence WHERE listing_id=?1 AND kind='SOURCE_PROFILE_OWNER_CONFLICT'",
                [second],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(evidence, (1, found.profiles[0].person_id));
        drop(db);
        assert!(core.get_person(second_person).unwrap().unwrap().favorite);
    }

    #[test]
    fn every_successful_profile_check_has_observation_without_duplicate_snapshot() {
        let core = core();
        let found = save_cards(&core, vec![card("101", "Anna")]);
        let listing = found.profiles[0].listing_id;
        let first = save_profile(
            &core,
            listing,
            &found.profiles[0].profile_url,
            profile("Anna"),
        )
        .unwrap();
        let repeated = save_profile(
            &core,
            listing,
            &found.profiles[0].profile_url,
            profile("Anna"),
        )
        .unwrap();
        assert_eq!(first.snapshot_id, repeated.snapshot_id);
        let db = core.connection().unwrap();
        let (count, changed): (i64,i64) = db.query_row(
            "SELECT COUNT(*), SUM(CASE WHEN json_extract(details_json, '$.snapshot_changed')=1 THEN 1 ELSE 0 END)
             FROM listing_observations WHERE listing_id=?1 AND source='PROFILE'",
            [listing], |r| Ok((r.get(0)?,r.get(1)?)),
        ).unwrap();
        assert_eq!((count, changed), (2, 1));
    }

    #[test]
    fn reverting_to_historical_snapshot_counts_as_actual_change() {
        let core = core();
        let found = save_cards(&core, vec![card("101", "Anna")]);
        let listing = found.profiles[0].listing_id;
        let url = &found.profiles[0].profile_url;
        let original = save_profile(&core, listing, url, profile("Anna")).unwrap();
        let changed = save_profile(&core, listing, url, profile("Anna 2")).unwrap();
        let restored = save_profile(&core, listing, url, profile("Anna")).unwrap();
        assert!(original.new_snapshot);
        assert!(changed.new_snapshot);
        assert!(!restored.new_snapshot);
        assert_eq!(restored.snapshot_id, original.snapshot_id);
        let db = core.connection().unwrap();
        let last: (i64, i64, i64) = db
            .query_row(
                "SELECT json_extract(details_json,'$.snapshot_changed'),
                    json_extract(details_json,'$.new_snapshot_created'), snapshot_id
             FROM listing_observations WHERE listing_id=?1 AND source='PROFILE'
             ORDER BY observation_id DESC LIMIT 1",
                [listing],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .unwrap();
        assert_eq!(last, (1, 0, original.snapshot_id));
        drop(db);
        let history = core
            .list_change_events(found.profiles[0].person_id, 50)
            .unwrap();
        let changes = history
            .iter()
            .filter(|event| {
                event.event_type == "FIELD_CHANGED" && event.field_key.as_deref() == Some("name")
            })
            .count();
        assert_eq!(changes, 2);
        assert_eq!(
            history.first().unwrap().new_json.as_deref(),
            Some("\"Anna\"")
        );
    }

    #[test]
    fn card_markers_are_persisted_as_search_history() {
        let core = core();
        let mut entry = card("101", "Anna");
        entry.has_18plus = true;
        entry.has_stars = true;
        save_cards(&core, vec![entry]);
        let db = core.connection().unwrap();
        let flags: (i64, i64) = db
            .query_row(
                "SELECT json_extract(markers_json,'$.has_18plus'),
                    json_extract(markers_json,'$.has_stars') FROM search_results",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(flags, (1, 1));
    }

    #[test]
    fn late_profile_response_cannot_overwrite_newer_observation() {
        let core = core();
        let found = save_cards(&core, vec![card("101", "Fixture")]);
        let listing = found.profiles[0].listing_id;
        let old_target = listing_target(&core, listing, false).unwrap();
        let new = save_observed_profile(&core, &old_target, profile("New"), true, None).unwrap();
        let late = save_observed_profile(&core, &old_target, profile("Late"), true, None);
        assert!(matches!(late, Err(CoreError::Source(_))));
        let current = core.cached_source_profile(listing).unwrap().unwrap();
        assert_eq!(current.snapshot_id, new.snapshot_id);
        assert_eq!(current.name, "New");
        let db = core.connection().unwrap();
        let observations: i64 = db
            .query_row(
                "SELECT COUNT(*) FROM listing_observations WHERE listing_id=?1 AND source='PROFILE'",
                [listing],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(observations, 1);
    }

    #[test]
    fn merge_reprojects_existing_run_saved_people_saved_window_and_map_without_source() {
        let core = core();
        let found = save_cards(&core, vec![card("101", "Source"), card("102", "Target")]);
        let source = &found.profiles[0];
        let target = &found.profiles[1];
        let listing = source.listing_id;
        let owner = source.person_id;
        let new_owner = target.person_id;
        let mut target_profile = profile("Target");
        target_profile.advert_id = "102".into();
        target_profile.source_profile_id = None;
        target_profile.phone_display = Some("0170 1234567".into());
        save_profile(
            &core,
            target.listing_id,
            &target.profile_url,
            target_profile,
        )
        .unwrap();
        let mut source_profile = profile("Source");
        source_profile.phone_display = Some("0170 1234567".into());
        let snapshot = save_profile(&core, listing, &source.profile_url, source_profile).unwrap();
        core.set_favorite(owner, true).unwrap();
        core.set_preference(owner, crate::Preference::Like).unwrap();
        assert!(
            core.list_identity_candidates(listing)
                .unwrap()
                .iter()
                .any(|c| c.person_id == new_owner)
        );

        // Seed only the saved-search baseline; use actual projection and ack APIs.
        let saved_id = core
            .save_native_search("Fixture".into(), SearchRequest::for_test())
            .unwrap();
        {
            let db = core.connection().unwrap();
            db.execute(
                "INSERT INTO saved_search_results
                 (saved_search_id,result_key,snapshot_json,position,present,visible)
                 VALUES (?1,'101','{}',1,1,1),(?1,'102','{}',2,1,1)",
                [&saved_id],
            )
            .unwrap();
            db.execute(
                "UPDATE saved_searches SET unread_new_results=1 WHERE saved_search_id=?1",
                [&saved_id],
            )
            .unwrap();
        }
        let before = core.saved_search_window(saved_id.clone(), 100).unwrap();
        assert_eq!(before.cards[0].person_id, Some(owner));
        assert!(before.searches[0].unread_new_results);
        assert_eq!(core.map_overview(false).unwrap().total_profiles, 2);
        let in_flight = listing_target(&core, listing, false).unwrap();

        // Commit through the real identity transaction, not a simulated owner change.
        core.confirm_identity_match(listing, new_owner).unwrap();
        let rejected = save_observed_profile(&core, &in_flight, profile("Late"), true, None);
        assert!(matches!(rejected, Err(CoreError::Source(_))));
        assert_eq!(core.profile_identity(listing).unwrap().person_id, new_owner);
        assert_eq!(
            core.cached_source_profile(listing)
                .unwrap()
                .unwrap()
                .snapshot_id,
            snapshot.snapshot_id
        );
        let grid = core
            .active_search_cards(
                found.search_run_id,
                crate::saved_search::LocalVisibility::default(),
            )
            .unwrap();
        assert_eq!(
            grid.iter().map(|c| c.listing_id).collect::<Vec<_>>(),
            vec![listing, target.listing_id]
        );
        assert!(grid.iter().all(|c| c.person_id == new_owner
            && c.favorite
            && c.preference == crate::Preference::Like));
        let saved = core
            .saved_people(
                crate::SavedPersonKind::Favorite,
                crate::SavedPersonSort::Date,
                crate::SavedPersonSection::All,
                50,
                None,
                None,
            )
            .unwrap();
        assert_eq!(saved.total, 1);
        assert_eq!(saved.rows[0].person_id, new_owner);
        assert_eq!(saved.rows[0].listing_count, 2);
        let window = core.saved_search_window(saved_id.clone(), 100).unwrap();
        assert_eq!(window.cards.len(), 2);
        assert!(window.cards.iter().all(|c| c.person_id == Some(new_owner)));
        assert!(window.searches[0].unread_new_results);
        assert_ne!(before.searches[0].revision, window.searches[0].revision);
        assert!(
            !core
                .acknowledge_saved_search_revision(saved_id.clone(), before.searches[0].revision)
                .unwrap()
        );
        assert!(core.list_saved_searches().unwrap()[0].unread_new_results);
        assert_eq!(core.map_overview(false).unwrap().total_profiles, 1);
        assert_eq!(core.map_overview(true).unwrap().total_profiles, 1);
        assert_eq!(core.person_detail(new_owner).unwrap().listings.len(), 2);
        assert_eq!(core.profile_media(snapshot.snapshot_id).unwrap().len(), 1);
        assert!(
            core.acknowledge_saved_search_revision(saved_id, window.searches[0].revision)
                .unwrap()
        );
    }
}
