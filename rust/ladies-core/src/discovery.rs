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

pub(crate) fn cached_profile(
    core: &LadiesCore,
    listing_id: i64,
) -> Result<Option<crate::source::ProfileResponse>, CoreError> {
    let db = core.connection()?;
    cached_profile_from(&db, listing_id)
}

pub(crate) fn cached_profile_from(
    db: &rusqlite::Connection,
    listing_id: i64,
) -> Result<Option<crate::source::ProfileResponse>, CoreError> {
    use crate::source::{MediaReference, ProfileResponse};

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
    let mut query = db.prepare(&format!(
        "{PERSON_CARDS}
         SELECT * FROM projected
         WHERE listing_id IS NOT NULL AND (?1=0 OR favorite=1)
           AND (?1=1 OR preference='NONE')
           AND person_id NOT IN (SELECT value FROM json_each(?5))
           AND (?2 IS NULL OR favorite < ?2
                OR (favorite=?2 AND last_seen_at < ?3)
                OR (favorite=?2 AND last_seen_at=?3 AND listing_id < ?4))
         ORDER BY favorite DESC,last_seen_at DESC,listing_id DESC LIMIT ?6"
    ))?;
    let cursor_favorite = cursor.as_ref().map(|c| i64::from(c.favorite));
    let cursor_time = cursor.as_ref().map(|c| c.last_seen_at.as_str());
    let cursor_id = cursor.as_ref().map(|c| c.listing_id);
    let mut rows = query.query(params![
        favorites_only,
        cursor_favorite,
        cursor_time,
        cursor_id,
        excluded,
        limit + 1
    ])?;
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

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SavedPersonSection {
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
pub struct SavedPersonCursor {
    pub kind: SavedPersonKind,
    pub sort: SavedPersonSort,
    pub section: SavedPersonSection,
    pub as_of: i64,
    pub revision: i64,
    pub key: String,
    pub person_id: i64,
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
    pub next_cursor: Option<SavedPersonCursor>,
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
    cursor: Option<SavedPersonCursor>,
    as_of: Option<i64>,
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
    let as_of = Some(saved_now(&db)?);
    let watchlist = kind == SavedPersonKind::Favorite && sort == SavedPersonSort::Date;
    let read = |section, limit| {
        saved_people_in(
            core,
            &db,
            SavedQuery {
                kind,
                sort,
                section,
                limit,
                cursor: None,
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

#[cfg(test)]
fn saved_people(
    core: &LadiesCore,
    kind: SavedPersonKind,
    sort: SavedPersonSort,
    section: SavedPersonSection,
    limit: u32,
    cursor: Option<SavedPersonCursor>,
    as_of: Option<i64>,
) -> Result<SavedPersonPage, CoreError> {
    if !(1..=100).contains(&limit) {
        return Err(CoreError::InvalidInput(
            "Invalid saved people page size".into(),
        ));
    }
    let db = core.connection()?;
    saved_people_in(
        core,
        &db,
        SavedQuery {
            kind,
            sort,
            section,
            limit,
            cursor,
            as_of,
        },
    )
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
        cursor,
        as_of,
    } = query;
    let revision: i64 = db.query_row("SELECT total_changes()", [], |row| row.get(0))?;
    if limit == 0
        || (section != SavedPersonSection::All
            && (kind != SavedPersonKind::Favorite || sort != SavedPersonSort::Date))
        || cursor.as_ref().is_some_and(|c| {
            c.kind != kind
                || c.sort != sort
                || c.section != section
                || c.revision != revision
                || as_of.is_some_and(|time| time != c.as_of)
        })
    {
        return Err(CoreError::InvalidInput(
            "Invalid or outdated saved people page".into(),
        ));
    }
    let predicate = kind.predicate();
    let as_of = match cursor.as_ref().map(|c| c.as_of).or(as_of) {
        Some(time) => time,
        None => saved_now(db)?,
    };
    // Count persons, including unlisted imports, under one connection guard.
    let total = db.query_row(
        &format!("SELECT COUNT(*) FROM person_user_state WHERE {predicate}"),
        [],
        |row| row.get(0),
    )?;
    let (key, comparison, direction, collation) = match sort {
        SavedPersonSort::Date => (
            "COALESCE(strftime('%Y-%m-%dT%H:%M:%fZ',person_last_seen_at),'')",
            "<",
            "DESC",
            "BINARY",
        ),
        SavedPersonSort::Name => ("name", ">", "ASC", "SAVED_NAME"),
    };
    let section_predicate = section.predicate();
    // Freeze the watchlist boundary across sections and pagination; only the latest change counts.
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
    // Only enum-selected SQL fragments are interpolated; cursor values are bound.
    let mut query = db.prepare(&format!(
        "{eligible}, page_window AS (
            SELECT * FROM eligible
            WHERE {section_predicate}
              AND (:cursor_key IS NULL OR sort_key COLLATE {collation} {comparison} :cursor_key
               OR (sort_key COLLATE {collation} = :cursor_key AND person_id {comparison} :cursor_id))
            ORDER BY sort_key COLLATE {collation} {direction},person_id {direction}
            LIMIT :limit
         )
         SELECT w.*,(SELECT COUNT(*) FROM listings l WHERE l.person_id=w.person_id)
         FROM page_window w
         ORDER BY w.sort_key COLLATE {collation} {direction},w.person_id {direction}"
    ))?;
    let mut rows = query.query(named_params! {
        ":as_of": as_of,
        ":cursor_key": cursor.as_ref().map(|c| c.key.as_str()),
        ":cursor_id": cursor.as_ref().map(|c| c.person_id),
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
        entries.push((
            SavedPersonRow {
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
            },
            row.get::<_, String>(18)?,
        ));
    }
    let has_more = entries.len() > limit as usize;
    entries.truncate(limit as usize);
    let next_cursor = if has_more {
        entries.last().map(|(row, key)| SavedPersonCursor {
            kind,
            sort,
            section,
            as_of,
            revision,
            key: key.clone(),
            person_id: row.person_id,
        })
    } else {
        None
    };
    Ok(SavedPersonPage {
        rows: entries.into_iter().map(|(row, _)| row).collect(),
        total,
        section_total,
        as_of,
        next_cursor,
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
        let saved = |kind, sort, limit, cursor| {
            saved_people(
                &core,
                kind,
                sort,
                SavedPersonSection::All,
                limit,
                cursor,
                None,
            )
        };
        let fav = saved(SavedPersonKind::Favorite, SavedPersonSort::Name, 1, None).unwrap();
        assert_eq!(fav.total, 2);
        assert_eq!(fav.rows[0].person_id, a);
        assert_eq!(fav.rows[0].listing_count, 2);
        assert_eq!(fav.rows[0].card.as_ref().unwrap().listing_id, second);
        let cursor = fav.next_cursor.unwrap();
        assert!(
            saved(
                SavedPersonKind::Like,
                SavedPersonSort::Name,
                1,
                Some(cursor.clone())
            )
            .is_err()
        );
        let next = saved(
            SavedPersonKind::Favorite,
            SavedPersonSort::Name,
            1,
            Some(cursor),
        )
        .unwrap();
        assert_eq!(next.total, 2);
        assert_eq!(next.rows[0].person_id, b);
        assert!(next.next_cursor.is_none());

        let liked = saved(SavedPersonKind::Like, SavedPersonSort::Date, 1, None).unwrap();
        assert_eq!(liked.total, 2);
        assert_eq!(liked.rows[0].person_id, c);
        assert!(liked.rows[0].card.is_none());
        let more = saved(
            SavedPersonKind::Like,
            SavedPersonSort::Date,
            1,
            liked.next_cursor,
        )
        .unwrap();
        assert_eq!(more.rows[0].person_id, a);
        assert!(more.rows[0].favorite);
        core.set_swipe_preference(first, a, Some(Preference::Like), Preference::Dislike)
            .unwrap();
        let dislikes = saved(SavedPersonKind::Dislike, SavedPersonSort::Date, 100, None).unwrap();
        assert_eq!(dislikes.total, 2);
        assert!(dislikes.rows.iter().all(|row| row.favorite));
        assert_eq!(
            saved(SavedPersonKind::Like, SavedPersonSort::Date, 10, None)
                .unwrap()
                .total,
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
        let read = |section, cursor| {
            saved_people(
                &core,
                SavedPersonKind::Favorite,
                SavedPersonSort::Date,
                section,
                3,
                cursor,
                Some(as_of),
            )
            .unwrap()
        };
        let recent = read(SavedPersonSection::Recent, None);
        assert_eq!(recent.total, 7);
        assert_eq!(recent.section_total, 5);
        assert_eq!(recent.rows.len(), 3);
        assert!(recent.rows.iter().all(|row| row.recent));
        let rest = read(SavedPersonSection::Recent, recent.next_cursor);
        let regular = read(SavedPersonSection::Regular, None);
        assert_eq!(regular.section_total, 2);
        let mut recent_ids = recent
            .rows
            .iter()
            .chain(&rest.rows)
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
    fn saved_window_reconciles_changed_membership_and_rejects_old_cursor() {
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
        let first = saved_people(
            &core,
            SavedPersonKind::Favorite,
            SavedPersonSort::Name,
            SavedPersonSection::All,
            1,
            None,
            None,
        )
        .unwrap();
        let cursor = first.next_cursor.unwrap();
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
        assert!(
            saved_people(
                &core,
                SavedPersonKind::Favorite,
                SavedPersonSort::Name,
                SavedPersonSection::All,
                1,
                Some(cursor),
                None,
            )
            .is_err()
        );
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
                page(&core, false, limit, None, &[]),
                Err(CoreError::InvalidInput(reason))
                    if reason == "Discovery page size must be between 1 and 100"
            ));
        }
        assert!(page(&core, false, 1, None, &[]).unwrap().cards.is_empty());
        assert!(page(&core, false, 100, None, &[]).unwrap().cards.is_empty());
    }
}
