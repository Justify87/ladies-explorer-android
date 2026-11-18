use rusqlite::{OptionalExtension, params};

use crate::{CoreError, LadiesCore, Preference};

#[derive(Debug, Clone, uniffi::Record)]
pub struct DiscoveryCard {
    pub listing_id: i64,
    pub person_id: i64,
    pub name: String,
    pub city: String,
    pub thumbnail_url: Option<String>,
    pub description: Option<String>,
    pub preference: Preference,
    pub favorite: bool,
    pub has_video: bool,
    pub has_ai_content: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ProfileMediaItem {
    pub kind: String,
    pub url: String,
    pub local_path: Option<String>,
    pub position: u32,
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

pub(crate) fn list(
    core: &LadiesCore,
    favorites_only: bool,
    limit: u32,
) -> Result<Vec<DiscoveryCard>, CoreError> {
    if !(1..=200).contains(&limit) {
        return Err(CoreError::InvalidInput(
            "Discovery limit must be between 1 and 200".into(),
        ));
    }
    let db = core.connection()?;
    // One latest listing per person. A person's preference belongs to the person, not the ad.
    let mut query = db.prepare(
        "WITH ranked AS (
           SELECT l.listing_id, l.person_id,
                  ROW_NUMBER() OVER (PARTITION BY l.person_id ORDER BY l.last_seen_at DESC,l.listing_id DESC) AS rank
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
                s.description,
                u.preference,u.favorite,
                COALESCE(r.has_video,0),COALESCE(r.has_ai_content,0)
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
           AND (?1=1 OR u.preference!='DISLIKE')
         ORDER BY CASE WHEN u.favorite=1 THEN 0 ELSE 1 END,
                  l.last_seen_at DESC,l.listing_id DESC
         LIMIT ?2"
    )?;
    let rows = query.query_map(params![favorites_only, limit], |r| {
        Ok((
            r.get::<_, i64>(0)?,
            r.get::<_, i64>(1)?,
            r.get::<_, String>(2)?,
            r.get::<_, String>(3)?,
            r.get::<_, Option<String>>(4)?,
            r.get::<_, Option<String>>(5)?,
            r.get::<_, String>(6)?,
            r.get::<_, i64>(7)?,
            r.get::<_, i64>(8)?,
            r.get::<_, i64>(9)?,
        ))
    })?;
    rows.map(|row| {
        let (
            listing_id,
            person_id,
            name,
            city,
            thumbnail_url,
            description,
            preference,
            favorite,
            video,
            ai,
        ) = row?;
        Ok(DiscoveryCard {
            listing_id,
            person_id,
            name,
            city,
            thumbnail_url,
            description,
            preference: Preference::from_db(&preference)?,
            favorite: favorite != 0,
            has_video: video != 0,
            has_ai_content: ai != 0,
        })
    })
    .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

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
        assert_eq!(list(&core, false, 100).unwrap().len(), 2);
        core.set_preference(first, Preference::Dislike).unwrap();
        assert_eq!(list(&core, false, 100).unwrap().len(), 1);
        core.set_favorite(first, true).unwrap();
        let favorites = list(&core, true, 100).unwrap();
        assert_eq!(favorites.len(), 1);
        assert_eq!(favorites[0].person_id, first);
        assert!(favorites[0].favorite);
    }

    #[test]
    fn discovery_limit_is_bounded() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        assert!(list(&core, false, 0).is_err());
        assert!(list(&core, false, 201).is_err());
    }
}
