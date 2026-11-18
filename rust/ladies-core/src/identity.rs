use std::collections::{HashMap, HashSet};

use rusqlite::{Connection, OptionalExtension, params};

use crate::{CoreError, LadiesCore};

const STRONG_DISTANCE: u32 = 5;
const CANDIDATE_DISTANCE: u32 = 8;

#[derive(Debug, Clone, uniffi::Record)]
pub struct IdentityCandidate {
    pub person_id: i64,
    pub decision: String,
    pub source_profile_match: bool,
    pub phone_match: bool,
    pub image_matches: u32,
    pub strong_image_matches: u32,
    pub best_image_distance: Option<u32>,
}

#[derive(Default)]
struct Evidence {
    source: bool,
    phone: bool,
    image_matches: u32,
    strong: u32,
    best: Option<u32>,
}

pub(crate) fn normalize_phone(input: Option<&str>) -> Option<String> {
    let raw = input?.trim();
    if raw.is_empty() {
        return None;
    }
    let value = raw
        .strip_prefix("tel:")
        .or_else(|| raw.strip_prefix("sms:"))
        .unwrap_or(raw);
    let value = if let Some(index) = value.to_ascii_lowercase().find("wa.me/") {
        &value[index + "wa.me/".len()..]
    } else {
        value
    };
    let mut digits = value
        .chars()
        .filter(char::is_ascii_digit)
        .collect::<String>();
    if digits.starts_with("0049") {
        digits.replace_range(..4, "49");
    } else if digits.starts_with('0') {
        digits.replace_range(..1, "49");
    }
    if digits.is_empty() {
        None
    } else {
        Some(format!("+{digits}"))
    }
}

pub(crate) fn record_phone(
    db: &Connection,
    person_id: i64,
    listing_id: i64,
    raw: Option<&str>,
) -> Result<(), CoreError> {
    let Some(normalized) = normalize_phone(raw) else {
        return Ok(());
    };
    db.execute(
        "INSERT INTO person_identifiers
         (person_id,listing_id,kind,normalized_value,raw_value)
         VALUES (?1,?2,'PHONE',?3,?4)
         ON CONFLICT(person_id,kind,normalized_value)
         DO UPDATE SET last_seen_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')",
        params![person_id, listing_id, normalized, raw],
    )?;
    Ok(())
}

pub(crate) fn record_image_hash(
    db: &Connection,
    person_id: i64,
    listing_id: i64,
    dhash: &str,
) -> Result<(), CoreError> {
    db.execute(
        "INSERT INTO person_identifiers
         (person_id,listing_id,kind,normalized_value)
         VALUES (?1,?2,'IMAGE_DHASH',?3)
         ON CONFLICT(person_id,kind,normalized_value)
         DO UPDATE SET last_seen_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')",
        params![person_id, listing_id, dhash],
    )?;
    Ok(())
}

fn current_owner(db: &Connection, listing: i64) -> Result<(i64, Option<i64>, String), CoreError> {
    db.query_row(
        "SELECT person_id, current_snapshot_id, match_decision FROM listings WHERE listing_id=?1",
        [listing],
        |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
    )
    .optional()?
    .ok_or(CoreError::ListingNotFound(listing))
}

fn hashes(db: &Connection, snapshot_id: i64) -> Result<Vec<u64>, CoreError> {
    let mut query = db.prepare(
        "SELECT DISTINCT a.dhash FROM snapshot_media m
         JOIN media_assets a ON a.media_asset_id=m.media_asset_id
         WHERE m.snapshot_id=?1 AND m.kind='IMAGE' AND a.dhash IS NOT NULL",
    )?;
    let rows = query.query_map([snapshot_id], |row| row.get::<_, String>(0))?;
    Ok(rows
        .filter_map(|row| {
            row.ok()
                .and_then(|hash| u64::from_str_radix(&hash, 16).ok())
        })
        .collect::<HashSet<_>>()
        .into_iter()
        .collect())
}

fn compare_images(new_hashes: &[u64], old_hashes: &[u64]) -> (u32, u32, Option<u32>) {
    let mut candidate = 0;
    let mut strong = 0;
    let mut best: Option<u32> = None;
    // An old picture cannot count as two independent strong image matches.
    let mut matched_old = HashSet::new();
    for new_hash in new_hashes {
        let nearest = old_hashes
            .iter()
            .enumerate()
            .map(|(index, old)| ((new_hash ^ old).count_ones(), index))
            .min();
        if let Some((distance, index)) = nearest {
            best = Some(best.map_or(distance, |current| current.min(distance)));
            if distance <= CANDIDATE_DISTANCE {
                candidate += 1;
                if distance <= STRONG_DISTANCE && matched_old.insert(index) {
                    strong += 1;
                }
            }
        }
    }
    (candidate, strong, best)
}

fn decision(e: &Evidence) -> &'static str {
    if e.source || e.strong >= 2 || (e.phone && e.strong >= 1) {
        "MATCH"
    } else if e.phone || e.strong >= 1 {
        "STRONG_CANDIDATE"
    } else {
        "CANDIDATE"
    }
}

fn rank(c: &IdentityCandidate) -> (u8, u8, u8, u32, u32, u32) {
    (
        match c.decision.as_str() {
            "MATCH" => 3,
            "STRONG_CANDIDATE" => 2,
            _ => 1,
        },
        u8::from(c.source_profile_match),
        u8::from(c.phone_match),
        c.strong_image_matches,
        c.image_matches,
        64 - c.best_image_distance.unwrap_or(64),
    )
}

fn candidates(db: &Connection, listing_id: i64) -> Result<Vec<IdentityCandidate>, CoreError> {
    let (owner, snapshot_id, _) = current_owner(db, listing_id)?;
    let Some(snapshot_id) = snapshot_id else {
        return Ok(Vec::new());
    };
    let (source_id, phone): (Option<String>, Option<String>) = db.query_row(
        "SELECT external_profile_id, normalized_phone FROM listing_snapshots WHERE snapshot_id=?1",
        [snapshot_id],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )?;
    let mut matches: HashMap<i64, Evidence> = HashMap::new();
    if let Some(source) = source_id {
        let mut q = db.prepare(
            "SELECT DISTINCT person_id FROM source_profiles
             WHERE source='LADIES' AND external_profile_id=?1 AND person_id!=?2",
        )?;
        for id in q.query_map(params![source, owner], |r| r.get::<_, i64>(0))? {
            matches.entry(id?).or_default().source = true;
        }
    }
    if let Some(phone) = phone {
        let mut q = db.prepare(
            "SELECT DISTINCT person_id FROM person_identifiers
             WHERE kind='PHONE' AND normalized_value=?1 AND person_id!=?2",
        )?;
        for id in q.query_map(params![phone, owner], |r| r.get::<_, i64>(0))? {
            matches.entry(id?).or_default().phone = true;
        }
    }
    let new_hashes = hashes(db, snapshot_id)?;
    if !new_hashes.is_empty() {
        let mut old: HashMap<i64, Vec<u64>> = HashMap::new();
        let mut q = db.prepare(
            "SELECT person_id, normalized_value FROM person_identifiers
             WHERE kind='IMAGE_DHASH' AND person_id!=?1",
        )?;
        for row in q.query_map([owner], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?))
        })? {
            let (person_id, value) = row?;
            if let Ok(hash) = u64::from_str_radix(&value, 16) {
                old.entry(person_id).or_default().push(hash);
            }
        }
        for (person, old_hashes) in old {
            let (image_matches, strong, best) = compare_images(&new_hashes, &old_hashes);
            if image_matches > 0 {
                let entry = matches.entry(person).or_default();
                entry.image_matches = image_matches;
                entry.strong = strong;
                entry.best = best;
            }
        }
    }
    let mut found = matches
        .into_iter()
        .map(|(person_id, e)| IdentityCandidate {
            person_id,
            decision: decision(&e).into(),
            source_profile_match: e.source,
            phone_match: e.phone,
            image_matches: e.image_matches,
            strong_image_matches: e.strong,
            best_image_distance: e.best,
        })
        .collect::<Vec<_>>();
    found.sort_by(|a, b| rank(b).cmp(&rank(a)).then(a.person_id.cmp(&b.person_id)));
    Ok(found)
}

fn insert_evidence(
    db: &Connection,
    listing_id: i64,
    person_id: i64,
    candidate_id: i64,
    kind: &str,
    score: Option<f64>,
    distance: Option<u32>,
) -> Result<(), CoreError> {
    if !db.query_row(
        "SELECT EXISTS(SELECT 1 FROM identity_evidence
         WHERE listing_id=?1 AND person_id=?2 AND candidate_person_id=?3 AND kind=?4)",
        params![listing_id, person_id, candidate_id, kind],
        |r| r.get::<_, bool>(0),
    )? {
        db.execute(
            "INSERT INTO identity_evidence
             (listing_id,person_id,candidate_person_id,kind,score,best_distance)
             VALUES (?1,?2,?3,?4,?5,?6)",
            params![listing_id, person_id, candidate_id, kind, score, distance],
        )?;
    }
    Ok(())
}

pub(crate) fn refresh(db: &Connection, listing_id: i64) -> Result<(), CoreError> {
    let (owner, _, previous_decision) = current_owner(db, listing_id)?;
    if previous_decision == "MANUAL_SPLIT" || previous_decision == "MANUAL_MATCH" {
        return Ok(());
    }
    let candidates = candidates(db, listing_id)?;
    for c in &candidates {
        if c.source_profile_match {
            insert_evidence(
                db,
                listing_id,
                owner,
                c.person_id,
                "SOURCE_PROFILE",
                Some(1.0),
                None,
            )?;
        }
        if c.phone_match {
            insert_evidence(db, listing_id, owner, c.person_id, "PHONE", Some(1.0), None)?;
        }
        if c.image_matches > 0 {
            insert_evidence(
                db,
                listing_id,
                owner,
                c.person_id,
                "IMAGE_DHASH",
                Some(f64::from(c.strong_image_matches)),
                c.best_image_distance,
            )?;
        }
    }
    let winner = candidates
        .first()
        .filter(|top| candidates.get(1).is_none_or(|next| rank(top) > rank(next)));
    db.execute(
        "UPDATE listings SET match_decision=?1, candidate_person_id=?2,
         match_reason=?3, match_best_distance=?4, match_image_count=?5,
         match_strong_image_count=?6, match_phone=?7, match_source_profile=?8
         WHERE listing_id=?9",
        params![
            winner
                .map(|c| c.decision.as_str())
                .unwrap_or(if candidates.is_empty() {
                    "NO_MATCH"
                } else {
                    "AMBIGUOUS"
                }),
            winner.map(|c| c.person_id),
            if candidates.is_empty() {
                "No matching evidence"
            } else if winner.is_some() {
                "Unique identity candidate"
            } else {
                "Multiple competing identity candidates"
            },
            winner.and_then(|c| c.best_image_distance),
            winner.map(|c| c.image_matches).unwrap_or_default(),
            winner.map(|c| c.strong_image_matches).unwrap_or_default(),
            winner.is_some_and(|c| c.phone_match),
            winner.is_some_and(|c| c.source_profile_match),
            listing_id
        ],
    )?;
    Ok(())
}

pub(crate) fn list(
    core: &LadiesCore,
    listing_id: i64,
) -> Result<Vec<IdentityCandidate>, CoreError> {
    let db = core.connection()?;
    candidates(&db, listing_id)
}

pub(crate) fn confirm(core: &LadiesCore, listing_id: i64, target_id: i64) -> Result<(), CoreError> {
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    let (source_id, _, state) = current_owner(&tx, listing_id)?;
    if source_id == target_id || state == "MANUAL_SPLIT" {
        return Err(CoreError::IdentityConflict("Identity merge refused".into()));
    }
    let matching = candidates(&tx, listing_id)?;
    if !matching
        .iter()
        .any(|candidate| candidate.person_id == target_id)
    {
        return Err(CoreError::IdentityConflict(
            "Target has no supporting identity evidence".into(),
        ));
    }
    let source: (Option<String>, String, String) = tx.query_row(
        "SELECT display_name,first_seen_at,last_seen_at FROM persons WHERE person_id=?1",
        [source_id],
        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
    )?;
    let (pref, favorite, interest, note): (String, i64, String, Option<String>) = tx.query_row(
        "SELECT preference,favorite,repeat_interest,private_note
         FROM person_user_state WHERE person_id=?1",
        [source_id],
        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
    )?;
    let (target_pref, target_note): (String, Option<String>) = tx
        .query_row(
            "SELECT preference,private_note FROM person_user_state WHERE person_id=?1",
            [target_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?
        .ok_or(CoreError::PersonNotFound(target_id))?;
    let combined_note = [target_note.as_deref(), note.as_deref()]
        .into_iter()
        .flatten()
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join("\n\n");
    tx.execute(
        "UPDATE person_user_state SET
         preference=CASE WHEN preference='NONE' THEN ?1 ELSE preference END,
         favorite=MAX(favorite,?2),
         repeat_interest=CASE WHEN repeat_interest='UNKNOWN' THEN ?3 ELSE repeat_interest END,
         private_note=?4,
         updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE person_id=?5",
        params![
            if target_pref == "NONE" {
                pref.as_str()
            } else {
                target_pref.as_str()
            },
            favorite,
            interest,
            if combined_note.is_empty() {
                None
            } else {
                Some(combined_note)
            },
            target_id
        ],
    )?;
    let mut stmt =
        tx.prepare("SELECT listing_id,source_profile_id FROM listings WHERE person_id=?1")?;
    let prior = stmt
        .query_map([source_id], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, Option<i64>>(1)?))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    drop(stmt);
    // The schema triggers require unhooking the source profile before ownership changes.
    tx.execute(
        "UPDATE listings SET source_profile_id=NULL WHERE person_id=?1",
        [source_id],
    )?;
    tx.execute(
        "UPDATE listings SET person_id=?1 WHERE person_id=?2",
        params![target_id, source_id],
    )?;
    tx.execute(
        "UPDATE source_profiles SET person_id=?1 WHERE person_id=?2",
        params![target_id, source_id],
    )?;
    for (id, profile) in &prior {
        if let Some(profile) = profile {
            tx.execute(
                "UPDATE listings SET source_profile_id=?1 WHERE listing_id=?2",
                params![profile, id],
            )?;
        }
        tx.execute(
            "INSERT INTO identity_events
             (listing_id,previous_person_id,new_person_id,action,evidence_json)
             VALUES (?1,?2,?3,'MANUAL_MATCH',?4)",
            params![
                id,
                source_id,
                target_id,
                serde_json::json!({"previous_person_id":source_id,"target_person_id":target_id})
                    .to_string()
            ],
        )?;
    }
    tx.execute(
        "INSERT OR IGNORE INTO person_identifiers
         (person_id,listing_id,kind,normalized_value,raw_value,first_seen_at,last_seen_at)
         SELECT ?1,listing_id,kind,normalized_value,raw_value,first_seen_at,last_seen_at
         FROM person_identifiers WHERE person_id=?2",
        params![target_id, source_id],
    )?;
    for table in [
        "encounters",
        "price_lists",
        "change_events",
        "identity_evidence",
    ] {
        tx.execute(
            &format!("UPDATE {table} SET person_id=?1 WHERE person_id=?2"),
            params![target_id, source_id],
        )?;
    }
    tx.execute(
        "UPDATE identity_evidence SET candidate_person_id=?1 WHERE candidate_person_id=?2",
        params![target_id, source_id],
    )?;
    tx.execute(
        "UPDATE listings SET candidate_person_id=?1 WHERE candidate_person_id=?2",
        params![target_id, source_id],
    )?;
    tx.execute(
        "UPDATE persons SET display_name=COALESCE(display_name,?1),
         first_seen_at=MIN(first_seen_at,?2),last_seen_at=MAX(last_seen_at,?3),
         updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE person_id=?4",
        params![source.0, source.1, source.2, target_id],
    )?;
    tx.execute(
        "UPDATE listings SET match_decision='MANUAL_MATCH', candidate_person_id=NULL
                WHERE listing_id=?1",
        [listing_id],
    )?;
    tx.execute(
        "UPDATE listings SET candidate_person_id=NULL
                WHERE person_id=?1 AND candidate_person_id=?1",
        [target_id],
    )?;
    tx.execute("DELETE FROM persons WHERE person_id=?1", [source_id])?;
    let invalid: bool = tx.prepare("PRAGMA foreign_key_check")?.exists([])?;
    if invalid {
        return Err(CoreError::IdentityConflict(
            "Merged identity violates referential integrity".into(),
        ));
    }
    tx.commit()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn phone_normalization_matches_legacy() {
        assert_eq!(
            normalize_phone(Some("tel:0170 1234567")).as_deref(),
            Some("+491701234567")
        );
        assert_eq!(
            normalize_phone(Some("0049 170 1234567")).as_deref(),
            Some("+491701234567")
        );
        assert_eq!(
            normalize_phone(Some("wa.me/491701234567")).as_deref(),
            Some("+491701234567")
        );
        assert_eq!(normalize_phone(Some("")).as_deref(), None);
    }
    #[test]
    fn image_evidence_needs_independent_pictures() {
        assert_eq!(compare_images(&[0, 0], &[0]).1, 1);
        assert_eq!(compare_images(&[0, 1], &[0, 1]).1, 2);
        assert_eq!(compare_images(&[0xffffffffffffffff], &[0]).0, 0);
    }
    #[test]
    fn confirmed_merge_preserves_person_state_and_listing_links() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let source = core.create_person(Some("From".into())).unwrap();
        let target = core.create_person(Some("To".into())).unwrap();
        let sid = core
            .add_source_profile(source, "LADIES".into(), "src".into())
            .unwrap();
        let listing = core
            .add_listing(source, Some(sid), "LADIES".into(), "1".into(), None)
            .unwrap();
        core.set_favorite(source, true).unwrap();
        {
            let db = core.connection().unwrap();
            record_phone(&db, source, listing, Some("0170 1234567")).unwrap();
            record_phone(&db, target, listing, Some("0170 1234567")).unwrap();
            db.execute(
                "INSERT INTO listing_snapshots
                (listing_id,fingerprint,normalized_phone) VALUES (?1,'fp','+491701234567')",
                [listing],
            )
            .unwrap();
            let snapshot = db.last_insert_rowid();
            db.execute(
                "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
                params![snapshot, listing],
            )
            .unwrap();
            refresh(&db, listing).unwrap();
        }
        assert_eq!(core.list_identity_candidates(listing).unwrap().len(), 1);
        core.confirm_identity_match(listing, target).unwrap();
        assert_eq!(core.status().unwrap().person_count, 1);
        assert!(core.get_person(target).unwrap().unwrap().favorite);
        let db = core.connection().unwrap();
        let owner: (i64, i64) = db
            .query_row(
                "SELECT l.person_id,p.person_id FROM listings l JOIN source_profiles p
             ON p.source_profile_id=l.source_profile_id WHERE l.listing_id=?1",
                [listing],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(owner, (target, target));
    }
}
