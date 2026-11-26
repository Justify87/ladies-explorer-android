use rusqlite::{OptionalExtension, named_params, params};

use crate::{CoreError, LadiesCore, Preference, source::known_age};

// Use the same Unicode name collation for ordering, ties and cursors.
fn saved_name_key(name: &str) -> String {
    static MARKS: std::sync::LazyLock<regex::Regex> =
        std::sync::LazyLock::new(|| regex::Regex::new(r"\p{M}").expect("Unicode marks"));
    let lower = name.to_lowercase().replace('ß', "ss");
    let normalizer = icu_normalizer::DecomposingNormalizer::new_nfd();
    let normalized = normalizer.normalize(&lower);
    MARKS.replace_all(&normalized, "").into_owned()
}

pub(crate) fn register_saved_name_collation(db: &rusqlite::Connection) -> Result<(), CoreError> {
    db.create_collation("SAVED_NAME", |a, b| {
        saved_name_key(a).cmp(&saved_name_key(b))
    })?;
    Ok(())
}

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

pub(crate) fn has_cached_profile(core: &LadiesCore, listing_id: i64) -> Result<bool, CoreError> {
    let db = core.connection()?;
    Ok(db.query_row(
        "SELECT EXISTS(SELECT 1 FROM listings l
         JOIN listing_snapshots s
           ON s.snapshot_id=l.current_snapshot_id AND s.listing_id=l.listing_id
         WHERE l.listing_id=?1)",
        [listing_id],
        |r| r.get(0),
    )?)
}

pub(crate) fn cached_profile_from(
    db: &rusqlite::Connection,
    listing_id: i64,
) -> Result<Option<crate::source::ProfileResponse>, CoreError> {
    use crate::source::ProfileResponse;

    Ok(db
        .query_row(
            "SELECT s.snapshot_id,s.name,s.city,s.description,
                    s.postal_code,s.phone_display,s.published_at
             FROM listings l
             JOIN listing_snapshots s
               ON s.snapshot_id=l.current_snapshot_id AND s.listing_id=l.listing_id
             WHERE l.listing_id=?1",
            [listing_id],
            |r| {
                Ok(ProfileResponse {
                    listing_id,
                    snapshot_id: r.get(0)?,
                    name: r.get::<_, Option<String>>(1)?.unwrap_or_default(),
                    city: r.get::<_, Option<String>>(2)?.unwrap_or_default(),
                    description: r.get::<_, Option<String>>(3)?.unwrap_or_default(),
                    postal_code: r.get(4)?,
                    phone_display: r.get(5)?,
                    published_at: r.get(6)?,
                })
            },
        )
        .optional()?)
}

pub(crate) fn media_from(
    core: &LadiesCore,
    db: &rusqlite::Connection,
    snapshot_id: i64,
) -> Result<Vec<ProfileMediaItem>, CoreError> {
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

// One representative per canonical person; saved and neutral feeds share archive-first images.
const PERSON_CARDS: &str = "
    WITH ranked AS (
        SELECT listing_id, person_id,
               ROW_NUMBER() OVER (
                   PARTITION BY person_id ORDER BY last_seen_at DESC, listing_id DESC
               ) AS rank
        FROM listings
    ), projected AS (
        SELECT l.listing_id,p.person_id,
               COALESCE(NULLIF(s.name,''),NULLIF(r.name,''),
                   (SELECT sr.name FROM search_results sr JOIN listings owner
                    ON owner.listing_id=sr.listing_id WHERE owner.person_id=p.person_id AND sr.name<>''
                    ORDER BY julianday(sr.observed_at) DESC,sr.search_result_id DESC LIMIT 1),
                   NULLIF(p.display_name,''),
                   (SELECT ps.name FROM listing_snapshots ps JOIN listings owner
                    ON owner.listing_id=ps.listing_id WHERE owner.person_id=p.person_id AND ps.name<>''
                    ORDER BY julianday(ps.captured_at) DESC,ps.snapshot_id DESC LIMIT 1),
                   'Person #' || p.person_id) AS name,
               COALESCE(NULLIF(s.city,''),NULLIF(r.city,''),
                   (SELECT ps.city FROM listing_snapshots ps JOIN listings owner
                    ON owner.listing_id=ps.listing_id WHERE owner.person_id=p.person_id AND ps.city<>''
                    ORDER BY julianday(ps.captured_at) DESC,ps.snapshot_id DESC LIMIT 1),
                   (SELECT sr.city FROM search_results sr JOIN listings owner
                    ON owner.listing_id=sr.listing_id WHERE owner.person_id=p.person_id AND sr.city<>''
                    ORDER BY julianday(sr.observed_at) DESC,sr.search_result_id DESC LIMIT 1),'') AS city,
               COALESCE(NULLIF(image.remote_url,''),NULLIF(r.thumbnail_url,''),
                   (SELECT sr.thumbnail_url FROM search_results sr JOIN listings owner
                    ON owner.listing_id=sr.listing_id WHERE owner.person_id=p.person_id AND sr.thumbnail_url<>''
                    ORDER BY julianday(sr.observed_at) DESC,sr.search_result_id DESC LIMIT 1)) AS thumbnail_url,
               asset.local_path AS local_path,
               s.description,u.preference,u.favorite,
               COALESCE(r.has_video,0) AS has_video,COALESCE(r.has_ai_content,0) AS has_ai_content,
               l.last_seen_at AS last_seen_at,COALESCE(r.is_premium,0) AS is_premium,s.traits_json,
               u.updated_at,p.last_seen_at AS person_last_seen_at,
               COALESCE(l.current_status,'UNKNOWN') AS current_status,l.status_checked_at
        FROM persons p
        JOIN person_user_state u ON u.person_id=p.person_id
        LEFT JOIN ranked k ON k.person_id=p.person_id AND k.rank=1
        LEFT JOIN listings l ON l.listing_id=k.listing_id
        LEFT JOIN listing_snapshots s
          ON s.snapshot_id=l.current_snapshot_id AND s.listing_id=l.listing_id
        LEFT JOIN search_results r ON r.search_result_id=(
            SELECT sr.search_result_id FROM search_results sr
            WHERE sr.listing_id=l.listing_id ORDER BY julianday(sr.observed_at) DESC,sr.search_result_id DESC LIMIT 1
        )
        LEFT JOIN snapshot_media image ON image.snapshot_media_id=COALESCE(
            (SELECT m.snapshot_media_id FROM snapshot_media m
             WHERE m.snapshot_id=l.current_snapshot_id AND m.kind='IMAGE'
             ORDER BY CASE WHEN m.role='PRIMARY' THEN 0 ELSE 1 END,m.position,m.snapshot_media_id LIMIT 1),
            CASE WHEN NULLIF(r.thumbnail_url,'') IS NULL THEN
            (SELECT m.snapshot_media_id FROM snapshot_media m
             JOIN listing_snapshots ps ON ps.snapshot_id=m.snapshot_id
             JOIN listings owner ON owner.listing_id=ps.listing_id
             WHERE owner.person_id=p.person_id AND m.kind='IMAGE'
             ORDER BY julianday(ps.captured_at) DESC,ps.snapshot_id DESC,
               CASE WHEN m.role='PRIMARY' THEN 0 ELSE 1 END,m.position,m.snapshot_media_id LIMIT 1)
            END
        )
        LEFT JOIN media_assets asset ON asset.media_asset_id=image.media_asset_id
    )";

fn person_card(core: &LadiesCore, row: &rusqlite::Row<'_>) -> Result<DiscoveryCard, CoreError> {
    let relative: Option<String> = row.get(5)?;
    let local_path = relative.and_then(|relative| {
        let path = core.media_root.as_ref()?.join(relative);
        path.is_file().then(|| path.to_string_lossy().into_owned())
    });
    let preference: String = row.get(7)?;
    let traits: Option<String> = row.get(13)?;
    Ok(DiscoveryCard {
        listing_id: row.get(0)?,
        person_id: row.get(1)?,
        name: row.get(2)?,
        city: row.get(3)?,
        thumbnail_url: row.get(4)?,
        local_path,
        description: row.get(6)?,
        preference: Preference::from_db(&preference)?,
        favorite: row.get(8)?,
        has_video: row.get(9)?,
        has_ai_content: row.get(10)?,
        is_premium: row.get(12)?,
        age: known_age(traits.as_deref()),
    })
}

pub(crate) fn page(
    core: &LadiesCore,
    limit: u32,
    cursor: Option<DiscoveryCursor>,
) -> Result<DiscoveryPage, CoreError> {
    if !(1..=100).contains(&limit) {
        return Err(CoreError::InvalidInput(
            "Discovery page size must be between 1 and 100".into(),
        ));
    }
    let db = core.connection()?;
    let mut query = db.prepare(&format!(
        "{PERSON_CARDS}
         SELECT * FROM projected
         WHERE listing_id IS NOT NULL AND preference='NONE'
           AND (?1 IS NULL OR favorite < ?1
                OR (favorite=?1 AND last_seen_at < ?2)
                OR (favorite=?1 AND last_seen_at=?2 AND listing_id < ?3))
         ORDER BY favorite DESC,last_seen_at DESC,listing_id DESC LIMIT ?4"
    ))?;
    let cursor_favorite = cursor.as_ref().map(|c| i64::from(c.favorite));
    let cursor_time = cursor.as_ref().map(|c| c.last_seen_at.as_str());
    let cursor_id = cursor.as_ref().map(|c| c.listing_id);
    let mut rows = query.query(params![cursor_favorite, cursor_time, cursor_id, limit + 1])?;
    let mut entries = Vec::new();
    while let Some(row) = rows.next()? {
        entries.push((person_card(core, row)?, row.get::<_, String>(11)?));
    }
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

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SavedPersonKind {
    Favorite,
    Like,
    Dislike,
}

impl SavedPersonKind {
    fn predicate(self) -> &'static str {
        match self {
            Self::Favorite => "favorite=1",
            Self::Like => "preference='LIKE'",
            Self::Dislike => "preference='DISLIKE'",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SavedPersonSort {
    Date,
    Name,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SavedPersonSection {
    All,
    Recent,
    Regular,
}

impl SavedPersonSection {
    fn predicate(self) -> &'static str {
        match self {
            Self::All => "1=1",
            Self::Recent => "recent=1",
            Self::Regular => "recent=0",
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SavedPersonRow {
    pub person_id: i64,
    pub name: String,
    pub preference: Preference,
    pub favorite: bool,
    pub card: Option<DiscoveryCard>,
    pub listing_count: u32,
    pub updated_at: String,
    pub last_seen_at: String,
    pub status: String,
    pub status_checked_at: Option<String>,
    pub latest_change_at: Option<String>,
    pub latest_change_type: Option<String>,
    pub recent: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SavedPersonPage {
    pub rows: Vec<SavedPersonRow>,
    pub total: u32,
    pub section_total: u32,
    pub as_of: i64,
    pub has_more: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SavedPeopleWindow {
    pub regular: SavedPersonPage,
    pub recent: Option<SavedPersonPage>,
}

struct SavedQuery {
    kind: SavedPersonKind,
    sort: SavedPersonSort,
    section: SavedPersonSection,
    limit: u32,
    as_of: i64,
}

fn saved_now(db: &rusqlite::Connection) -> Result<i64, CoreError> {
    Ok(db.query_row(
        "SELECT CAST(round(unixepoch('now','subsec')*1000) AS INTEGER)",
        [],
        |row| row.get(0),
    )?)
}

pub(crate) fn saved_window(
    core: &LadiesCore,
    kind: SavedPersonKind,
    sort: SavedPersonSort,
    regular_limit: u32,
    recent_limit: u32,
) -> Result<SavedPeopleWindow, CoreError> {
    let db = core.connection()?;
    let as_of = saved_now(&db)?;
    saved_window_at(core, &db, kind, sort, regular_limit, recent_limit, as_of)
}

fn saved_window_at(
    core: &LadiesCore,
    db: &rusqlite::Connection,
    kind: SavedPersonKind,
    sort: SavedPersonSort,
    regular_limit: u32,
    recent_limit: u32,
    as_of: i64,
) -> Result<SavedPeopleWindow, CoreError> {
    let watchlist = kind == SavedPersonKind::Favorite && sort == SavedPersonSort::Date;
    let read = |section, limit| {
        saved_people_in(
            core,
            db,
            SavedQuery {
                kind,
                sort,
                section,
                limit,
                as_of,
            },
        )
    };
    let regular = read(
        if watchlist {
            SavedPersonSection::Regular
        } else {
            SavedPersonSection::All
        },
        regular_limit,
    )?;
    let recent = if watchlist {
        Some(read(SavedPersonSection::Recent, recent_limit)?)
    } else {
        None
    };
    Ok(SavedPeopleWindow { regular, recent })
}

fn saved_people_in(
    core: &LadiesCore,
    db: &rusqlite::Connection,
    query: SavedQuery,
) -> Result<SavedPersonPage, CoreError> {
    let SavedQuery {
        kind,
        sort,
        section,
        limit,
        as_of,
    } = query;
    if limit == 0
        || (section != SavedPersonSection::All
            && (kind != SavedPersonKind::Favorite || sort != SavedPersonSort::Date))
    {
        return Err(CoreError::InvalidInput(
            "Invalid saved people page".into(),
        ));
    }
    let predicate = kind.predicate();
    // Count persons, including unlisted imports, under one connection guard.
    let total = db.query_row(
        &format!("SELECT COUNT(*) FROM person_user_state WHERE {predicate}"),
        [],
        |row| row.get(0),
    )?;
    let (key, direction, collation) = match sort {
        SavedPersonSort::Date => (
            "COALESCE(strftime('%Y-%m-%dT%H:%M:%fZ',person_last_seen_at),'')",
            "DESC",
            "BINARY",
        ),
        SavedPersonSort::Name => ("name", "ASC", "SAVED_NAME"),
    };
    let section_predicate = section.predicate();
    // Freeze the watchlist boundary across sections of one window; only the latest change counts.
    let eligible = format!(
        "{PERSON_CARDS}, eligible AS (
           SELECT p.*, {key} AS sort_key, e.created_at AS change_at,
                  e.event_type AS change_type,
                  COALESCE(CAST(round(unixepoch(e.created_at,'subsec')*1000) AS INTEGER) > :as_of - 129600000,0) AS recent
           FROM projected p LEFT JOIN change_events e ON e.change_event_id=(
               SELECT c.change_event_id FROM change_events c
               WHERE c.person_id=p.person_id
                 AND CAST(round(unixepoch(c.created_at,'subsec')*1000) AS INTEGER) <= :as_of
               ORDER BY julianday(c.created_at) DESC,c.change_event_id DESC LIMIT 1
           )
           WHERE {predicate}
         )"
    );
    let section_total = if section == SavedPersonSection::All {
        total
    } else {
        db.query_row(
            &format!("{eligible} SELECT COUNT(*) FROM eligible WHERE {section_predicate}"),
            named_params! { ":as_of": as_of },
            |row| row.get(0),
        )?
    };
    // Only enum-selected SQL fragments are interpolated; window limits are bound.
    let mut query = db.prepare(&format!(
        "{eligible}, page_window AS (
            SELECT * FROM eligible
            WHERE {section_predicate}
            ORDER BY sort_key COLLATE {collation} {direction},person_id {direction}
            LIMIT :limit
         )
         SELECT w.*,(SELECT COUNT(*) FROM listings l WHERE l.person_id=w.person_id)
         FROM page_window w
         ORDER BY w.sort_key COLLATE {collation} {direction},w.person_id {direction}"
    ))?;
    let mut rows = query.query(named_params! {
        ":as_of": as_of,
        ":limit": i64::from(limit) + 1,
    })?;
    let mut entries = Vec::new();
    while let Some(row) = rows.next()? {
        let card = if row.get::<_, Option<i64>>(0)?.is_some() {
            Some(person_card(core, row)?)
        } else {
            None
        };
        let raw: String = row.get(7)?;
        entries.push(SavedPersonRow {
            person_id: row.get(1)?,
            name: row.get(2)?,
            preference: Preference::from_db(&raw)?,
            favorite: row.get(8)?,
            card,
            listing_count: row.get(22)?,
            updated_at: row.get(14)?,
            last_seen_at: row.get(15)?,
            status: row.get(16)?,
            status_checked_at: row.get(17)?,
            latest_change_at: row.get(19)?,
            latest_change_type: row.get(20)?,
            recent: row.get(21)?,
        });
    }
    let has_more = entries.len() > limit as usize;
    entries.truncate(limit as usize);
    Ok(SavedPersonPage {
        rows: entries,
        total,
        section_total,
        as_of,
        has_more,
    })
}

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
        assert_eq!(known_age(Some(r#"{"alter":"28"}"#)).as_deref(), Some("28"));
        assert_eq!(
            known_age(Some(r#"{"Alter":"28Jahre"}"#)).as_deref(),
            Some("28")
        );
        assert!(known_age(Some(r#"{"Alter":["28","30"]}"#)).is_none());
        assert!(known_age(Some(r#"{"Alter":["ca. 30"]}"#)).is_none());
        assert!(known_age(Some(r#"{"Alter":"28","alter":"30"}"#)).is_none());
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
        assert!(rows[0].age.is_none());
        {
            let db = core.connection().unwrap();
            db.execute(
                "INSERT INTO listing_snapshots (listing_id,fingerprint,traits_json)
                 VALUES (?1,'grid-age',?2)",
                params![lb, r#"{"Alter":["32 Jahre"]}"#],
            )
            .unwrap();
            db.execute(
                "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
                params![db.last_insert_rowid(), lb],
            )
            .unwrap();
        }
        let updated = active_search_cards(&core, 1, &all).unwrap();
        assert_eq!(updated[0].age.as_deref(), Some("32"));
        assert_eq!(core.profile_identity(lb).unwrap().age, updated[0].age);
        core.set_swipe_preference(la, a, Some(Preference::None), Preference::Like)
            .unwrap();
        let liked = active_search_cards(&core, 1, &all).unwrap();
        assert_eq!(liked[1].listing_id, la);
        assert_eq!(liked[1].preference, Preference::Like);
        let hide_likes = crate::saved_search::LocalVisibility {
            hide_likes: true,
            ..all.clone()
        };
        let visible = active_search_cards(&core, 1, &hide_likes).unwrap();
        assert_eq!(visible.len(), 1);
        assert_eq!(visible[0].listing_id, lb);
        core.set_swipe_preference(la, a, Some(Preference::Like), Preference::None)
            .unwrap();
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
        let unmarked = crate::saved_search::LocalVisibility {
            image_mode: "NATURAL".into(),
            ..all.clone()
        };
        let rows = active_search_cards(&core, 1, &unmarked).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].listing_id, la);
        let unsupported = crate::saved_search::LocalVisibility {
            image_mode: "STUDIO".into(),
            ..all
        };
        assert!(active_search_cards(&core, 1, &unsupported).is_err());
    }

    #[test]
    fn active_run_visibility_matrix_is_local_and_reversible() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let mut owners = Vec::new();
        let mut listings = Vec::new();
        for index in 1..=3 {
            let owner = core
                .create_person(Some(format!("Fixture {index}")))
                .unwrap();
            let listing = core
                .add_listing(owner, None, "LADIES".into(), index.to_string(), None)
                .unwrap();
            owners.push(owner);
            listings.push(listing);
        }
        {
            let db = core.connection().unwrap();
            db.execute("INSERT INTO search_runs (search_run_id) VALUES (1)", [])
                .unwrap();
            for (position, listing) in listings.iter().enumerate() {
                db.execute(
                    "INSERT INTO search_results
                     (search_run_id,listing_id,position,name,has_video,has_ai_content,is_premium)
                     VALUES (1,?1,?2,'Fixture',0,0,0)",
                    params![listing, position as i64],
                )
                .unwrap();
            }
        }
        core.set_preference(owners[1], Preference::Like).unwrap();
        core.set_preference(owners[2], Preference::Dislike).unwrap();
        core.set_favorite(owners[1], true).unwrap();
        for hide_likes in [false, true] {
            for hide_dislikes in [false, true] {
                let rules = crate::saved_search::LocalVisibility {
                    hide_likes,
                    hide_dislikes,
                    ..Default::default()
                };
                let mut expected = vec![listings[0]];
                if !hide_likes {
                    expected.push(listings[1]);
                }
                if !hide_dislikes {
                    expected.push(listings[2]);
                }
                let cards = active_search_cards(&core, 1, &rules).unwrap();
                assert_eq!(
                    cards.iter().map(|card| card.listing_id).collect::<Vec<_>>(),
                    expected
                );
            }
        }
        let rules = crate::saved_search::LocalVisibility {
            hide_likes: true,
            hide_dislikes: true,
            ..Default::default()
        };
        core.set_swipe_preference(
            listings[1],
            owners[1],
            Some(Preference::Like),
            Preference::None,
        )
        .unwrap();
        let cards = active_search_cards(&core, 1, &rules).unwrap();
        assert_eq!(cards.len(), 2);
        assert_eq!(cards[1].listing_id, listings[1]);
        assert!(cards[1].favorite);
        assert_eq!(
            active_search_cards(&core, 1, &Default::default())
                .unwrap()
                .len(),
            3
        );
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
            page(&core, 100, None)
                .map(|p| p.cards)
                .unwrap()
                .len(),
            2
        );
        core.set_preference(first, Preference::Dislike).unwrap();
        assert_eq!(
            page(&core, 100, None)
                .map(|p| p.cards)
                .unwrap()
                .len(),
            1
        );
        core.set_preference(second, Preference::Like).unwrap();
        assert!(
            page(&core, 100, None)
                .map(|p| p.cards)
                .unwrap()
                .is_empty()
        );
        core.set_favorite(first, true).unwrap();
        assert!(page(&core, 100, None).unwrap().cards.is_empty());
        let saved = core
            .saved_people_window(SavedPersonKind::Favorite, SavedPersonSort::Name, 100, 100)
            .unwrap();
        let favorites = saved.regular.rows;
        assert_eq!(favorites.len(), 1);
        assert_eq!(favorites[0].person_id, first);
        assert!(favorites[0].favorite);
        core.set_preference(first, Preference::None).unwrap();
        let neutral = page(&core, 100, None).unwrap().cards;
        assert_eq!(neutral.len(), 1);
        assert_eq!(neutral[0].person_id, first);
        assert!(neutral[0].favorite);
    }

    #[test]
    fn archived_profile_is_available_without_network() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(Some("Ada".into())).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "123".into(), None)
            .unwrap();
        assert!(!core.has_cached_profile(listing).unwrap());
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
        assert!(core.has_cached_profile(listing).unwrap());
        let projection = core.profile_projection(listing).unwrap().unwrap();
        assert_eq!(projection.profile.name, "Ada");
        assert_eq!(projection.profile.city, "Leipzig");
        assert_eq!(projection.profile.description, "archiviert");
        assert_eq!(projection.media.len(), 1);
        assert!(!core.has_cached_profile(-1).unwrap());
    }

    #[test]
    fn profile_projection_reads_only_current_snapshot_of_selected_listing() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let owner = core.create_person(Some("Fixture".into())).unwrap();
        let other = core.create_person(Some("Other".into())).unwrap();
        let selected = core
            .add_listing(owner, None, "LADIES".into(), "101".into(), None)
            .unwrap();
        let sibling = core
            .add_listing(owner, None, "LADIES".into(), "102".into(), None)
            .unwrap();
        let foreign = core
            .add_listing(other, None, "LADIES".into(), "103".into(), None)
            .unwrap();
        let current = {
            let db = core.connection().unwrap();
            let insert = |listing: i64, fingerprint: &str, city: &str, hidden: Option<&str>| {
                db.execute(
                    "INSERT INTO listing_snapshots(listing_id,fingerprint,name,city,hidden_location)
                     VALUES (?1,?2,'Fixture',?3,?4)",
                    params![listing, fingerprint, city, hidden],
                )
                .unwrap();
                db.last_insert_rowid()
            };
            let old = insert(selected, "old", "Berlin", None);
            let current = insert(selected, "current", "Dresden", Some("Offenbach am Main"));
            let sibling_snapshot = insert(sibling, "sibling", "Hamburg", None);
            let foreign_snapshot = insert(foreign, "foreign", "Munich", None);
            assert_ne!(old, current);
            for (listing, snapshot) in [
                (selected, current),
                (sibling, sibling_snapshot),
                (foreign, foreign_snapshot),
            ] {
                db.execute(
                    "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
                    params![snapshot, listing],
                )
                .unwrap();
            }
            current
        };
        let projection = core.profile_projection(selected).unwrap().unwrap();
        assert_eq!(projection.profile.snapshot_id, current);
        assert_eq!(projection.profile.city, "Dresden");
        assert_eq!(projection.identity.person_id, owner);
        let db = core.connection().unwrap();
        let hidden: Option<String> = db
            .query_row(
                "SELECT hidden_location FROM listing_snapshots WHERE snapshot_id=?1",
                [current],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(hidden.as_deref(), Some("Offenbach am Main"));
        assert_eq!(projection.profile.postal_code, None);
    }

    #[test]
    fn discovery_keyset_pages_beyond_old_limit_without_duplicates() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        for id in 1..=215 {
            let person = core.create_person(Some(format!("Person {id}"))).unwrap();
            core.add_listing(person, None, "LADIES".into(), id.to_string(), None)
                .unwrap();
        }
        let mut cursor = None;
        let mut ids = std::collections::HashSet::new();
        loop {
            let current = page(&core, 35, cursor).unwrap();
            for card in current.cards {
                assert!(ids.insert(card.person_id));
            }
            match current.next_cursor {
                Some(next) => cursor = Some(next),
                None => break,
            }
        }
        assert_eq!(ids.len(), 215);
        assert!(ids.contains(&214));
        assert!(ids.contains(&215));
    }

    #[test]
    fn saved_people_pages_count_canonical_people_and_keep_preferences_independent() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let a = core.create_person(Some("Anna".into())).unwrap();
        let b = core.create_person(Some("anna".into())).unwrap();
        let c = core.create_person(Some("Zoe".into())).unwrap();
        let first = core
            .add_listing(a, None, "LADIES".into(), "saved-1".into(), None)
            .unwrap();
        let second = core
            .add_listing(a, None, "LADIES".into(), "saved-2".into(), None)
            .unwrap();
        core.add_listing(b, None, "LADIES".into(), "saved-3".into(), None)
            .unwrap();
        core.set_preference(a, Preference::Like).unwrap();
        core.set_preference(b, Preference::Dislike).unwrap();
        core.set_preference(c, Preference::Like).unwrap();
        core.set_favorite(a, true).unwrap();
        core.set_favorite(b, true).unwrap();
        {
            let db = core.connection().unwrap();
            db.execute(
                "UPDATE listings SET last_seen_at='2026-01-01T00:00:00Z'",
                [],
            )
            .unwrap();
            db.execute("UPDATE persons SET last_seen_at='2026-01-01T00:00:00Z'", [])
                .unwrap();
        }
        let saved = |kind, sort, limit| {
            core.saved_people_window(kind, sort, limit, limit)
                .unwrap()
                .regular
        };
        let fav = saved(SavedPersonKind::Favorite, SavedPersonSort::Name, 1);
        assert_eq!(fav.total, 2);
        assert_eq!(fav.rows[0].person_id, a);
        assert_eq!(fav.rows[0].listing_count, 2);
        assert_eq!(fav.rows[0].card.as_ref().unwrap().listing_id, second);
        assert!(fav.has_more);
        let next = saved(SavedPersonKind::Favorite, SavedPersonSort::Name, 2);
        assert_eq!(next.total, 2);
        assert_eq!(next.rows[0].person_id, a);
        assert_eq!(next.rows[1].person_id, b);
        assert!(!next.has_more);

        let liked = saved(SavedPersonKind::Like, SavedPersonSort::Date, 1);
        assert_eq!(liked.total, 2);
        assert_eq!(liked.rows[0].person_id, c);
        assert!(liked.rows[0].card.is_none());
        assert!(liked.has_more);
        let more = saved(SavedPersonKind::Like, SavedPersonSort::Date, 2);
        assert_eq!(more.rows[0].person_id, c);
        assert_eq!(more.rows[1].person_id, a);
        assert!(more.rows[1].favorite);
        assert!(!more.has_more);
        core.set_swipe_preference(first, a, Some(Preference::Like), Preference::Dislike)
            .unwrap();
        let dislikes = saved(SavedPersonKind::Dislike, SavedPersonSort::Date, 100);
        assert_eq!(dislikes.total, 2);
        assert!(dislikes.rows.iter().all(|row| row.favorite));
        assert_eq!(
            saved(SavedPersonKind::Like, SavedPersonSort::Date, 10).total,
            1
        );
    }

    #[test]
    fn watchlist_partitions_all_favorites_at_the_same_boundary() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let as_of = 1_800_000_000_000i64;
        let mut ids = Vec::new();
        for age in [0, 1, 2, 3, 129599, 129600, 129601] {
            let person = core.create_person(None).unwrap();
            core.set_favorite(person, true).unwrap();
            let db = core.connection().unwrap();
            db.execute(
                "INSERT INTO change_events(person_id,event_type,created_at)
                 VALUES (?1,'PROFILE_CREATED',datetime(?2,'unixepoch'))",
                params![person, as_of / 1000 - age],
            )
            .unwrap();
            ids.push(person);
        }
        let db = core.connection().unwrap();
        let read = |recent_limit| {
            saved_window_at(
                &core,
                &db,
                SavedPersonKind::Favorite,
                SavedPersonSort::Date,
                3,
                recent_limit,
                as_of,
            )
            .unwrap()
        };
        let first = read(3);
        let recent = first.recent.unwrap();
        assert_eq!(recent.total, 7);
        assert_eq!(recent.section_total, 5);
        assert_eq!(recent.rows.len(), 3);
        assert!(recent.rows.iter().all(|row| row.recent));
        assert!(recent.has_more);
        let expanded = read(5);
        let all_recent = expanded.recent.unwrap();
        let regular = expanded.regular;
        assert_eq!(regular.section_total, 2);
        assert_eq!(all_recent.as_of, regular.as_of);
        assert!(!all_recent.has_more);
        let mut recent_ids = all_recent
            .rows
            .iter()
            .map(|row| row.person_id)
            .collect::<Vec<_>>();
        recent_ids.sort_unstable();
        assert_eq!(recent_ids, ids[..5]);
        assert!(regular.rows.iter().all(|row| !row.recent));
        assert!(
            regular
                .rows
                .iter()
                .all(|row| ids[5..].contains(&row.person_id))
        );
    }

    #[test]
    fn saved_person_preserves_known_metadata_when_new_listing_is_sparse() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(None).unwrap();
        let old = core
            .add_listing(person, None, "LADIES".into(), "old".into(), None)
            .unwrap();
        let new = core
            .add_listing(person, None, "LADIES".into(), "new".into(), None)
            .unwrap();
        core.set_favorite(person, true).unwrap();
        {
            let db = core.connection().unwrap();
            db.execute(
                "UPDATE listings SET last_seen_at='2026-01-01T00:00:00Z' WHERE listing_id=?1",
                [old],
            )
            .unwrap();
            db.execute(
                "INSERT INTO listing_snapshots(listing_id,fingerprint,name,city)
                 VALUES (?1,'fixture','Änne','Leipzig')",
                [old],
            )
            .unwrap();
            let snapshot = db.last_insert_rowid();
            db.execute(
                "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
                params![snapshot, old],
            )
            .unwrap();
            db.execute(
                "INSERT INTO snapshot_media(snapshot_id,position,kind,role,remote_url)
                 VALUES (?1,1,'IMAGE','PRIMARY','https://bilder1.ladies-cdn.de/fixture.jpg')",
                [snapshot],
            )
            .unwrap();
        }
        let window = core
            .saved_people_window(SavedPersonKind::Favorite, SavedPersonSort::Name, 50, 50)
            .unwrap();
        let row = &window.regular.rows[0];
        let card = row.card.as_ref().unwrap();
        assert_eq!(card.listing_id, new);
        assert_eq!(row.listing_count, 2);
        assert_eq!(row.name, "Änne");
        assert_eq!(card.city, "Leipzig");
        assert_eq!(
            card.thumbnail_url.as_deref(),
            Some("https://bilder1.ladies-cdn.de/fixture.jpg")
        );
    }

    #[test]
    fn saved_window_reconciles_changed_membership_and_counts() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let a = core.create_person(Some("Änne".into())).unwrap();
        let b = core.create_person(Some("anne".into())).unwrap();
        let c = core.create_person(Some("Éva".into())).unwrap();
        let d = core.create_person(Some("Zoe".into())).unwrap();
        for id in [a, b, c, d] {
            core.set_favorite(id, true).unwrap();
        }
        let window = core
            .saved_people_window(SavedPersonKind::Favorite, SavedPersonSort::Name, 50, 50)
            .unwrap();
        assert_eq!(
            window
                .regular
                .rows
                .iter()
                .map(|r| r.person_id)
                .collect::<Vec<_>>(),
            vec![a, b, c, d]
        );
        let first = core
            .saved_people_window(SavedPersonKind::Favorite, SavedPersonSort::Name, 1, 1)
            .unwrap();
        assert!(first.regular.has_more);
        assert_eq!(first.regular.total, 4);
        let initial = core
            .saved_people_window(SavedPersonKind::Favorite, SavedPersonSort::Date, 1, 1)
            .unwrap();
        assert_eq!(initial.regular.section_total, 4);
        {
            let db = core.connection().unwrap();
            db.execute(
                "INSERT INTO change_events(person_id,event_type) VALUES (?1,'PROFILE_CREATED')",
                [a],
            )
            .unwrap();
        }
        core.set_favorite(b, false).unwrap();
        let new_name_window = core
            .saved_people_window(SavedPersonKind::Favorite, SavedPersonSort::Name, 1, 1)
            .unwrap();
        assert!(new_name_window.regular.has_more);
        assert_eq!(new_name_window.regular.total, 3);
        assert_eq!(new_name_window.regular.rows[0].person_id, a);
        let fresh = core
            .saved_people_window(SavedPersonKind::Favorite, SavedPersonSort::Date, 50, 50)
            .unwrap();
        let recent = fresh.recent.unwrap();
        assert_eq!(fresh.regular.total, 3);
        assert_eq!(recent.as_of, fresh.regular.as_of);
        assert_eq!(recent.section_total, 1);
        assert_eq!(recent.rows[0].person_id, a);
        assert_eq!(fresh.regular.section_total, 2);
        let mut regular = fresh
            .regular
            .rows
            .iter()
            .map(|r| r.person_id)
            .collect::<Vec<_>>();
        regular.sort_unstable();
        assert_eq!(regular, vec![c, d]);
    }

    #[test]
    fn discovery_limit_is_bounded() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        for limit in [0, 101] {
            assert!(matches!(
                page(&core, limit, None),
                Err(CoreError::InvalidInput(reason))
                    if reason == "Discovery page size must be between 1 and 100"
            ));
        }
        assert!(page(&core, 1, None).unwrap().cards.is_empty());
        assert!(page(&core, 100, None).unwrap().cards.is_empty());
    }
}
