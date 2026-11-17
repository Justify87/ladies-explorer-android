use rusqlite::{Connection, OptionalExtension, params};
use serde_json::json;
use url::Url;

use crate::{CoreError, LadiesCore};

use super::{Card, Profile, ProfileResponse, SearchCard, SearchRequest, SearchResponse};
use super::{network::DownloadedSearch, parse};

pub(super) fn save_search(
    core: &LadiesCore,
    request: &SearchRequest,
    downloaded: DownloadedSearch,
) -> Result<SearchResponse, CoreError> {
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
    tx.commit()?;
    Ok(SearchResponse {
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
                 current_url=?1, category=?2, current_status='ACTIVE', ended_at=NULL
             WHERE listing_id=?3",
            params![card.profile_url, card.category, listing_id],
        )?;
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
              current_status, match_decision)
             VALUES (?1,'LADIES',?2,?3,?4,'ACTIVE','UNRESOLVED')",
            params![person_id, card.advert_id, card.profile_url, card.category],
        )?;
        Ok((db.last_insert_rowid(), person_id))
    }
}

pub(super) fn listing_target(
    core: &LadiesCore,
    listing_id: i64,
) -> Result<(String, String, String, String, String), CoreError> {
    let db = core.connection()?;
    let existing: Option<(Option<String>, String, Option<String>, Option<String>, Option<String>)> = db
        .query_row(
            "SELECT l.current_url, l.external_listing_id, p.display_name,
                 (SELECT city FROM search_results
                  WHERE listing_id=l.listing_id ORDER BY observed_at DESC, search_result_id DESC LIMIT 1),
                 l.category
             FROM listings l JOIN persons p ON p.person_id=l.person_id
             WHERE l.listing_id=?1 AND l.source='LADIES'",
            [listing_id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?, row.get(4)?)),
        )
        .optional()?;
    let Some((url, advert_id, name, city, category)) = existing else {
        return Err(CoreError::ListingNotFound(listing_id));
    };
    let url = url
        .and_then(|url| parse::valid_profile_url(&url))
        .ok_or_else(|| {
            CoreError::Source("Stored listing has no recognized source profile URL".into())
        })?;
    Ok((
        url,
        advert_id,
        name.unwrap_or_default(),
        city.unwrap_or_default(),
        category.unwrap_or_default(),
    ))
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

pub(super) fn save_profile(
    core: &LadiesCore,
    listing_id: i64,
    url: &str,
    profile: Profile,
) -> Result<ProfileResponse, CoreError> {
    let fingerprint = parse::fingerprint(&profile);
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    let person_id: Option<i64> = tx
        .query_row(
            "SELECT person_id FROM listings WHERE listing_id=?1 AND source='LADIES'
         AND external_listing_id=?2",
            params![listing_id, profile.advert_id],
            |row| row.get(0),
        )
        .optional()?;
    let Some(person_id) = person_id else {
        return Err(CoreError::ListingNotFound(listing_id));
    };
    if let Some(external_id) = profile.source_profile_id.as_deref() {
        link_source_profile_or_record_conflict(
            &tx,
            person_id,
            listing_id,
            external_id,
            profile.canonical_id.as_deref(),
        )?;
    }
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
             VALUES (?1,?2,?3,?4,?5,?6,?7,
                (SELECT category FROM listings WHERE listing_id=?1),
                ?8,?9,?10,?11,?12,?13,?14,?15)",
            params![
                listing_id,
                fingerprint,
                url,
                profile.name,
                profile.city,
                profile.postal_code,
                profile.hidden_location,
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
        "UPDATE listings
         SET current_snapshot_id=?1, current_url=?2, last_seen_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),
             current_status='ACTIVE', ended_at=NULL
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
    tx.execute(
        "INSERT INTO listing_observations
         (listing_id, source, status, http_status, snapshot_id, details_json)
         VALUES (?1,'PROFILE','ACTIVE',200,?2,?3)",
        params![
            listing_id,
            snapshot_id,
            json!({
                "snapshot_changed": new_snapshot,
                "fingerprint": fingerprint,
            })
            .to_string()
        ],
    )?;
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
}
