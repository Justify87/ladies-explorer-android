use rusqlite::{OptionalExtension, params};

use crate::{CoreError, LadiesCore, Preference};

#[derive(Debug, Clone, uniffi::Record)]
pub struct DiscoveryCard {
    pub listing_id: i64,
    pub person_id: i64,
    pub name: String,
    pub city: String,
    pub thumbnail_url: Option<String>,
    pub local_path: Option<String>,
    pub description: Option<String>,
    pub preference: Preference,
    pub favorite: bool,
    pub has_video: bool,
    pub has_ai_content: bool,
    pub is_premium: bool,
    pub age: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ProfileMediaItem {
    pub kind: String,
    pub url: String,
    pub local_path: Option<String>,
    pub position: u32,
}

pub(crate) fn cached_profile(
    core: &LadiesCore,
    listing_id: i64,
) -> Result<Option<crate::source::ProfileResponse>, CoreError> {
    use crate::source::{MediaReference, ProfileResponse};

    let db = core.connection()?;
    let mut profile: Option<ProfileResponse> = db
        .query_row(
            "SELECT s.snapshot_id,l.external_listing_id,s.name,s.city,s.description,
                    s.postal_code,s.hidden_location,s.external_profile_id,s.canonical_id,
                    s.phone_display,s.published_at,s.traits_json,s.services_json,
                    s.opening_times_json
             FROM listings l
             JOIN listing_snapshots s
               ON s.snapshot_id=l.current_snapshot_id AND s.listing_id=l.listing_id
             WHERE l.listing_id=?1",
            [listing_id],
            |r| {
                Ok(ProfileResponse {
                    listing_id,
                    snapshot_id: r.get(0)?,
                    new_snapshot: false,
                    advert_id: r.get(1)?,
                    name: r.get::<_, Option<String>>(2)?.unwrap_or_default(),
                    city: r.get::<_, Option<String>>(3)?.unwrap_or_default(),
                    description: r.get::<_, Option<String>>(4)?.unwrap_or_default(),
                    postal_code: r.get(5)?,
                    hidden_location: r.get(6)?,
                    source_profile_id: r.get(7)?,
                    canonical_id: r.get(8)?,
                    phone_display: r.get(9)?,
                    published_at: r.get(10)?,
                    traits_json: r
                        .get::<_, Option<String>>(11)?
                        .unwrap_or_else(|| "{}".into()),
                    services_json: r
                        .get::<_, Option<String>>(12)?
                        .unwrap_or_else(|| "{}".into()),
                    opening_times_json: r
                        .get::<_, Option<String>>(13)?
                        .unwrap_or_else(|| "{}".into()),
                    media: Vec::new(),
                })
            },
        )
        .optional()?;
    if let Some(profile) = profile.as_mut() {
        let mut query = db.prepare(
            "SELECT remote_url,kind,position,role,remote_variant,revision_token
             FROM snapshot_media WHERE snapshot_id=?1
             ORDER BY position,snapshot_media_id",
        )?;
        profile.media = query
            .query_map([profile.snapshot_id], |r| {
                Ok(MediaReference {
                    url: r.get(0)?,
                    kind: r.get(1)?,
                    position: r.get(2)?,
                    role: r.get(3)?,
                    variant: r.get(4)?,
                    revision_token: r.get(5)?,
                })
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
    }
    Ok(profile)
}

pub(crate) fn media(
    core: &LadiesCore,
    snapshot_id: i64,
) -> Result<Vec<ProfileMediaItem>, CoreError> {
    let db = core.connection()?;
    let mut query = db.prepare(
        "SELECT sm.kind,sm.remote_url,a.local_path,sm.position
         FROM snapshot_media sm
         LEFT JOIN media_assets a ON a.media_asset_id=sm.media_asset_id
         WHERE sm.snapshot_id=?1 ORDER BY sm.position,sm.snapshot_media_id",
    )?;
    let rows = query.query_map([snapshot_id], |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, String>(1)?,
            r.get::<_, Option<String>>(2)?,
            r.get::<_, u32>(3)?,
        ))
    })?;
    rows.map(|row| {
        let (kind, url, relative, position) = row?;
        let local_path = relative.and_then(|relative| {
            let path = core.media_root.as_ref()?.join(relative);
            path.is_file().then(|| path.to_string_lossy().into_owned())
        });
        Ok(ProfileMediaItem {
            kind,
            url,
            local_path,
            position,
        })
    })
    .collect()
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DiscoveryCursor {
    pub favorite: bool,
    pub last_seen_at: String,
    pub listing_id: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DiscoveryPage {
    pub cards: Vec<DiscoveryCard>,
    pub next_cursor: Option<DiscoveryCursor>,
}

/// Only explicit, unambiguous source traits from an existing snapshot.
fn known_age(traits_json: Option<&str>) -> Option<String> {
    let value: serde_json::Value = serde_json::from_str(traits_json?).ok()?;
    let (_, age) = value
        .as_object()?
        .iter()
        .find(|(key, _)| key.eq_ignore_ascii_case("Alter"))?;
    let text = match age {
        serde_json::Value::String(text) => text.as_str(),
        serde_json::Value::Array(values) if values.len() == 1 => values[0].as_str()?,
        _ => return None,
    };
    let mut parts = text.split_whitespace();
    let digits = parts.next()?;
    if digits.len() != 2 || !digits.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    if let Some(unit) = parts.next() {
        if !matches!(unit.to_ascii_lowercase().as_str(), "jahr" | "jahre")
            || parts.next().is_some()
        {
            return None;
        }
    }
    Some(digits.to_owned())
}

pub(crate) fn page(
    core: &LadiesCore,
    favorites_only: bool,
    limit: u32,
    cursor: Option<DiscoveryCursor>,
    excluded_people: &[i64],
) -> Result<DiscoveryPage, CoreError> {
    if !(1..=100).contains(&limit) {
        return Err(CoreError::InvalidInput(
            "Discovery page size must be between 1 and 100".into(),
        ));
    }
    let excluded =
        serde_json::to_string(excluded_people).map_err(|e| CoreError::Storage(e.to_string()))?;
    let db = core.connection()?;
    // Stable keyset pagination after selecting the newest listing per person.
    // Explicit exclusions keep session-skipped people from exhausting the first page.
    let mut query = db.prepare(
        "WITH ranked AS (
           SELECT l.listing_id, l.person_id,
                  ROW_NUMBER() OVER (
                      PARTITION BY l.person_id
                      ORDER BY l.last_seen_at DESC, l.listing_id DESC
                  ) AS rank
           FROM listings l
         )
         SELECT l.listing_id, l.person_id,
                COALESCE(NULLIF(s.name,''),NULLIF(r.name,''),NULLIF(p.display_name,''),'Inserat'),
                COALESCE(NULLIF(s.city,''),NULLIF(r.city,''),''),
                COALESCE(
                    (SELECT m.remote_url FROM snapshot_media m
                     WHERE m.snapshot_id=l.current_snapshot_id AND m.kind='IMAGE'
                     ORDER BY CASE WHEN m.role='PRIMARY' THEN 0 ELSE 1 END,m.position
                     LIMIT 1),
                    r.thumbnail_url
                ) AS thumbnail_url,
                (SELECT a.local_path FROM snapshot_media m
                 JOIN media_assets a ON a.media_asset_id=m.media_asset_id
                 WHERE m.snapshot_id=l.current_snapshot_id AND m.kind='IMAGE'
                 ORDER BY CASE WHEN m.role='PRIMARY' THEN 0 ELSE 1 END,m.position
                 LIMIT 1) AS local_path,
                s.description,u.preference,u.favorite,
                COALESCE(r.has_video,0),COALESCE(r.has_ai_content,0),
                l.last_seen_at,COALESCE(r.is_premium,0),s.traits_json
         FROM ranked k
         JOIN listings l ON l.listing_id=k.listing_id
         JOIN persons p ON p.person_id=l.person_id
         JOIN person_user_state u ON u.person_id=p.person_id
         LEFT JOIN listing_snapshots s ON s.snapshot_id=l.current_snapshot_id
         LEFT JOIN search_results r ON r.search_result_id=(
             SELECT sr.search_result_id FROM search_results sr
             WHERE sr.listing_id=l.listing_id ORDER BY sr.search_run_id DESC LIMIT 1
         )
         WHERE k.rank=1 AND (?1=0 OR u.favorite=1)
           AND (?1=1 OR u.preference='NONE')
           AND l.person_id NOT IN (SELECT value FROM json_each(?5))
           AND (?2 IS NULL OR u.favorite < ?2
                OR (u.favorite=?2 AND l.last_seen_at < ?3)
                OR (u.favorite=?2 AND l.last_seen_at=?3 AND l.listing_id < ?4))
         ORDER BY u.favorite DESC,l.last_seen_at DESC,l.listing_id DESC
         LIMIT ?6",
    )?;
    let cursor_favorite = cursor.as_ref().map(|c| i64::from(c.favorite));
    let cursor_time = cursor.as_ref().map(|c| c.last_seen_at.as_str());
    let cursor_id = cursor.as_ref().map(|c| c.listing_id);
    let rows = query.query_map(
        params![
            favorites_only,
            cursor_favorite,
            cursor_time,
            cursor_id,
            excluded,
            limit + 1
        ],
        |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, i64>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
                r.get::<_, Option<String>>(4)?,
                r.get::<_, Option<String>>(5)?,
                r.get::<_, Option<String>>(6)?,
                r.get::<_, String>(7)?,
                r.get::<_, i64>(8)?,
                r.get::<_, i64>(9)?,
                r.get::<_, i64>(10)?,
                r.get::<_, String>(11)?,
                r.get::<_, bool>(12)?,
                r.get::<_, Option<String>>(13)?,
            ))
        },
    )?;
    let mut entries = rows
        .map(|row| {
            let (
                listing_id,
                person_id,
                name,
                city,
                thumbnail_url,
                relative,
                description,
                preference,
                favorite,
                video,
                ai,
                last_seen_at,
                is_premium,
                traits_json,
            ) = row?;
            let local_path = relative.and_then(|relative| {
                let path = core.media_root.as_ref()?.join(relative);
                path.is_file().then(|| path.to_string_lossy().into_owned())
            });
            Ok((
                DiscoveryCard {
                    listing_id,
                    person_id,
                    name,
                    city,
                    thumbnail_url,
                    local_path,
                    description,
                    preference: Preference::from_db(&preference)?,
                    favorite: favorite != 0,
                    has_video: video != 0,
                    has_ai_content: ai != 0,
                    is_premium,
                    age: known_age(traits_json.as_deref()),
                },
                last_seen_at,
            ))
        })
        .collect::<Result<Vec<_>, CoreError>>()?;
    let has_more = entries.len() > limit as usize;
    entries.truncate(limit as usize);
    let next_cursor = if has_more {
        entries.last().map(|(card, last_seen_at)| DiscoveryCursor {
            favorite: card.favorite,
            last_seen_at: last_seen_at.clone(),
            listing_id: card.listing_id,
        })
    } else {
        None
    };
    Ok(DiscoveryPage {
        cards: entries.into_iter().map(|(card, _)| card).collect(),
        next_cursor,
    })
}

// Search membership and order come from the executed run, not the global feed.
pub(crate) fn active_search_cards(
    core: &LadiesCore,
    run_id: i64,
    local: &crate::saved_search::LocalVisibility,
) -> Result<Vec<DiscoveryCard>, CoreError> {
    crate::saved_search::validate_local(local)?;
    let db = core.connection()?;
    let mut stmt = db.prepare(
        "SELECT l.listing_id,l.person_id,COALESCE(r.name,''),COALESCE(r.city,''),
                r.thumbnail_url,
                (SELECT a.local_path FROM snapshot_media m
                 JOIN media_assets a ON a.media_asset_id=m.media_asset_id
                 WHERE m.snapshot_id=l.current_snapshot_id AND m.kind='IMAGE'
                 ORDER BY CASE WHEN m.role='PRIMARY' THEN 0 ELSE 1 END,m.position
                 LIMIT 1),
                s.description,u.preference,u.favorite,r.has_video,r.has_ai_content,r.is_premium,s.traits_json
         FROM search_results r
         JOIN listings l ON l.listing_id=r.listing_id
         JOIN person_user_state u ON u.person_id=l.person_id
         LEFT JOIN listing_snapshots s ON s.snapshot_id=l.current_snapshot_id
         WHERE r.search_run_id=?1 ORDER BY r.position,r.search_result_id",
    )?;
    let rows = stmt.query_map([run_id], |r| {
        Ok((
            r.get::<_, i64>(0)?,
            r.get::<_, i64>(1)?,
            r.get::<_, String>(2)?,
            r.get::<_, String>(3)?,
            r.get::<_, Option<String>>(4)?,
            r.get::<_, Option<String>>(5)?,
            r.get::<_, Option<String>>(6)?,
            r.get::<_, String>(7)?,
            r.get::<_, bool>(8)?,
            r.get::<_, bool>(9)?,
            r.get::<_, bool>(10)?,
            r.get::<_, bool>(11)?,
            r.get::<_, Option<String>>(12)?,
        ))
    })?;
    let mut cards = Vec::new();
    for row in rows {
        let (
            listing_id,
            person_id,
            name,
            city,
            thumbnail_url,
            relative,
            description,
            preference,
            favorite,
            has_video,
            has_ai_content,
            is_premium,
            traits_json,
        ) = row?;
        if !local.allows(
            has_video,
            thumbnail_url.is_some(),
            has_ai_content,
            &preference,
        ) {
            continue;
        }
        let local_path = relative.and_then(|relative| {
            let path = core.media_root.as_ref()?.join(relative);
            path.is_file().then(|| path.to_string_lossy().into_owned())
        });
        cards.push(DiscoveryCard {
            listing_id,
            person_id,
            name,
            city,
            thumbnail_url,
            local_path,
            description,
            preference: Preference::from_db(&preference)?,
            favorite,
            has_video,
            has_ai_content,
            is_premium,
            age: known_age(traits_json.as_deref()),
        });
    }
    Ok(cards)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn grid_age_uses_only_an_unambiguous_persisted_trait() {
        assert_eq!(
            known_age(Some(r#"{"Alter":["32 Jahre"]}"#)).as_deref(),
            Some("32")
        );
        assert_eq!(
            known_age(Some(r#"{"alter":"28"}"#)).as_deref(),
            Some("28")
        );
        assert!(known_age(Some(r#"{"Alter":["28","30"]}"#)).is_none());
        assert!(known_age(Some(r#"{"Alter":["ca. 30"]}"#)).is_none());
        assert!(known_age(None).is_none());
    }

    #[test]
    fn active_run_keeps_membership_and_order_and_projects_current_preferences() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let a = core.create_person(Some("A".into())).unwrap();
        let b = core.create_person(Some("B".into())).unwrap();
        let outside = core.create_person(Some("Outside".into())).unwrap();
        let la = core
            .add_listing(a, None, "LADIES".into(), "1".into(), None)
            .unwrap();
        let lb = core
            .add_listing(b, None, "LADIES".into(), "2".into(), None)
            .unwrap();
        let lc = core
            .add_listing(outside, None, "LADIES".into(), "3".into(), None)
            .unwrap();
        {
            let db = core.connection().unwrap();
            db.execute("INSERT INTO search_runs (search_run_id) VALUES (1),(2)", [])
                .unwrap();
            db.execute(
                "INSERT INTO search_results
                 (search_run_id,listing_id,position,name,thumbnail_url,has_video,has_ai_content,is_premium)
                 VALUES (1,?1,2,'A',NULL,0,0,0),(1,?2,1,'B','fixture.png',1,1,1),
                        (2,?3,1,'Outside',NULL,0,0,0)",
                params![la, lb, lc],
            )
            .unwrap();
        }
        let all = crate::saved_search::LocalVisibility::default();
        let rows = active_search_cards(&core, 1, &all).unwrap();
        assert_eq!(
            rows.iter().map(|c| c.listing_id).collect::<Vec<_>>(),
            vec![lb, la]
        );
        assert!(rows[0].is_premium);
        core.set_preference(b, Preference::Dislike).unwrap();
        core.set_favorite(b, true).unwrap();
        let rows = active_search_cards(&core, 1, &all).unwrap();
        assert_eq!(rows[0].preference, Preference::Dislike);
        assert!(rows[0].favorite);
        let hidden = crate::saved_search::LocalVisibility {
            hide_dislikes: true,
            ..all.clone()
        };
        let rows = active_search_cards(&core, 1, &hidden).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].listing_id, la);
        let media = crate::saved_search::LocalVisibility {
            video_only: true,
            photos_only: true,
            image_mode: "AI".into(),
            ..all.clone()
        };
        let rows = active_search_cards(&core, 1, &media).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].listing_id, lb);
        let unsupported = crate::saved_search::LocalVisibility {
            image_mode: "STUDIO".into(),
            ..all
        };
        assert!(active_search_cards(&core, 1, &unsupported).is_err());
    }

    #[test]
    fn discovery_deduplicates_people_and_preserves_favorites() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let first = core.create_person(Some("Anna".into())).unwrap();
        let second = core.create_person(Some("Eva".into())).unwrap();
        core.add_listing(first, None, "LADIES".into(), "1".into(), None)
            .unwrap();
        core.add_listing(first, None, "LADIES".into(), "2".into(), None)
            .unwrap();
        core.add_listing(second, None, "LADIES".into(), "3".into(), None)
            .unwrap();
        assert_eq!(
            page(&core, false, 100, None, &[])
                .map(|p| p.cards)
                .unwrap()
                .len(),
            2
        );
        core.set_preference(first, Preference::Dislike).unwrap();
        assert_eq!(
            page(&core, false, 100, None, &[])
                .map(|p| p.cards)
                .unwrap()
                .len(),
            1
        );
        core.set_preference(second, Preference::Like).unwrap();
        assert!(
            page(&core, false, 100, None, &[])
                .map(|p| p.cards)
                .unwrap()
                .is_empty()
        );
        core.set_favorite(first, true).unwrap();
        let favorites = page(&core, true, 100, None, &[]).map(|p| p.cards).unwrap();
        assert_eq!(favorites.len(), 1);
        assert_eq!(favorites[0].person_id, first);
        assert!(favorites[0].favorite);
    }

    #[test]
    fn archived_profile_is_available_without_network() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(Some("Ada".into())).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "123".into(), None)
            .unwrap();
        assert!(cached_profile(&core, listing).unwrap().is_none());
        {
            let db = core.connection().unwrap();
            db.execute(
                "INSERT INTO listing_snapshots
                 (listing_id,fingerprint,name,city,description,traits_json)
                 VALUES (?1,'cached','Ada','Leipzig','archiviert','{}')",
                [listing],
            )
            .unwrap();
            let snapshot = db.last_insert_rowid();
            db.execute(
                "INSERT INTO snapshot_media
                 (snapshot_id,position,kind,role,remote_url)
                 VALUES (?1,1,'IMAGE','PRIMARY','https://bilder1.ladies-cdn.de/Bilder/1/1-A1.JPG')",
                [snapshot],
            )
            .unwrap();
            db.execute(
                "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
                params![snapshot, listing],
            )
            .unwrap();
        }
        let profile = cached_profile(&core, listing).unwrap().unwrap();
        assert_eq!(profile.name, "Ada");
        assert_eq!(profile.city, "Leipzig");
        assert_eq!(profile.description, "archiviert");
        assert_eq!(profile.advert_id, "123");
        assert_eq!(profile.media.len(), 1);
        assert!(!profile.new_snapshot);
        assert!(cached_profile(&core, -1).unwrap().is_none());
    }

    #[test]
    fn discovery_keyset_pages_beyond_old_limit_and_honors_skips() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        for id in 1..=215 {
            let person = core.create_person(Some(format!("Person {id}"))).unwrap();
            core.add_listing(person, None, "LADIES".into(), id.to_string(), None)
                .unwrap();
        }
        let excluded = vec![214, 215];
        let mut cursor = None;
        let mut ids = std::collections::HashSet::new();
        loop {
            let current = page(&core, false, 35, cursor, &excluded).unwrap();
            for card in current.cards {
                assert!(ids.insert(card.person_id));
            }
            match current.next_cursor {
                Some(next) => cursor = Some(next),
                None => break,
            }
        }
        assert_eq!(ids.len(), 213);
        assert!(!ids.contains(&214));
        assert!(!ids.contains(&215));
    }

    #[test]
    fn discovery_limit_is_bounded() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        assert!(page(&core, false, 0, None, &[]).map(|p| p.cards).is_err());
        assert!(page(&core, false, 201, None, &[]).map(|p| p.cards).is_err());
    }
}
