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
    let values = rows.collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(values
        .into_iter()
        .filter_map(|hash| u64::from_str_radix(&hash, 16).ok())
        .collect::<HashSet<_>>()
        .into_iter()
        .collect())
}

fn augment(
    new_index: usize,
    edges: &[Vec<usize>],
    seen: &mut [bool],
    matched: &mut [Option<usize>],
) -> bool {
    for &old_index in &edges[new_index] {
        if seen[old_index] {
            continue;
        }
        seen[old_index] = true;
        let previous = matched[old_index];
        if previous.is_none_or(|other| augment(other, edges, seen, matched)) {
            matched[old_index] = Some(new_index);
            return true;
        }
    }
    false
}

fn independent_pairs(new_hashes: &[u64], old_hashes: &[u64], threshold: u32) -> u32 {
    let edges = new_hashes
        .iter()
        .map(|hash| {
            let mut neighbors = old_hashes
                .iter()
                .enumerate()
                .filter_map(|(index, old)| {
                    let distance = (hash ^ old).count_ones();
                    (distance <= threshold).then_some((distance, index))
                })
                .collect::<Vec<_>>();
            neighbors.sort_unstable();
            neighbors
                .into_iter()
                .map(|(_, index)| index)
                .collect::<Vec<_>>()
        })
        .collect::<Vec<_>>();
    let mut order = (0..edges.len()).collect::<Vec<_>>();
    order.sort_unstable_by_key(|&index| (edges[index].len(), index));
    let mut matched = vec![None; old_hashes.len()];
    let mut count = 0;
    for index in order {
        let mut seen = vec![false; old_hashes.len()];
        if augment(index, &edges, &mut seen, &mut matched) {
            count += 1;
        }
    }
    count
}

fn compare_images(new_hashes: &[u64], old_hashes: &[u64]) -> (u32, u32, Option<u32>) {
    let mut best = None;
    let mut edges = Vec::new();
    let mut candidate = 0;
    for (new_index, new_hash) in new_hashes.iter().enumerate() {
        let mut closest = None;
        for (old_index, old_hash) in old_hashes.iter().enumerate() {
            let distance = (new_hash ^ old_hash).count_ones();
            closest = Some(closest.map_or(distance, |current: u32| current.min(distance)));
            best = Some(best.map_or(distance, |current: u32| current.min(distance)));
            if distance <= STRONG_DISTANCE {
                edges.push((new_index, old_index, distance));
            }
        }
        if closest.is_some_and(|distance| distance <= CANDIDATE_DISTANCE) {
            candidate += 1;
        }
    }
    // Maximum-cardinality bipartite matching; deterministic order prefers closer pairs.
    edges.sort_by_key(|&(new, old, distance)| (distance, new, old));
    let mut neighbors = vec![Vec::new(); new_hashes.len()];
    for (new, old, _) in edges {
        neighbors[new].push(old);
    }
    fn augment(
        new: usize,
        neighbors: &[Vec<usize>],
        owners: &mut [Option<usize>],
        visited: &mut [bool],
    ) -> bool {
        for &old in &neighbors[new] {
            if visited[old] {
                continue;
            }
            visited[old] = true;
            let can_assign = match owners[old] {
                None => true,
                Some(previous) => augment(previous, neighbors, owners, visited),
            };
            if can_assign {
                owners[old] = Some(new);
                return true;
            }
        }
        false
    }
    let mut owners = vec![None; old_hashes.len()];
    let mut strong = 0;
    for new in 0..new_hashes.len() {
        let mut visited = vec![false; old_hashes.len()];
        if augment(new, &neighbors, &mut owners, &mut visited) {
            strong += 1;
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
    let (target_pref, target_interest, target_note): (String, String, Option<String>) = tx
        .query_row(
            "SELECT preference,repeat_interest,private_note FROM person_user_state WHERE person_id=?1",
            [target_id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .optional()?
        .ok_or(CoreError::PersonNotFound(target_id))?;
    if pref != "NONE" && target_pref != "NONE" && pref != target_pref {
        return Err(CoreError::IdentityConflict(
            "Conflicting LIKE/DISLIKE decisions require explicit resolution".into(),
        ));
    }
    if interest != "UNKNOWN" && target_interest != "UNKNOWN" && interest != target_interest {
        return Err(CoreError::IdentityConflict(
            "Conflicting repeat-interest decisions require explicit resolution".into(),
        ));
    }
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
    crate::history::reconcile_relistings(&tx, target_id)?;
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
    fn augmenting_paths_recover_independent_picture_matches() {
        assert_eq!(compare_images(&[1, 0], &[0, 63]).1, 2);
        assert_eq!(compare_images(&[1, 0], &[0, 63]).0, 2);
    }

    #[test]
    fn image_matching_finds_maximum_independent_pairs() {
        // New 0 matches both old 0 and old 1; new 1 matches only old 0.
        assert_eq!(compare_images(&[0, 32], &[0, 31]).1, 2);
    }

    #[test]
    fn conflicting_user_decisions_block_merge_without_data_loss() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let source = core.create_person(Some("Source".into())).unwrap();
        let target = core.create_person(Some("Target".into())).unwrap();
        let listing = core
            .add_listing(source, None, "LADIES".into(), "101".into(), None)
            .unwrap();
        core.set_preference(source, crate::Preference::Like)
            .unwrap();
        core.set_preference(target, crate::Preference::Dislike)
            .unwrap();
        let db = core.connection().unwrap();
        record_phone(&db, source, listing, Some("0170 1234567")).unwrap();
        record_phone(&db, target, listing, Some("0170 1234567")).unwrap();
        db.execute(
            "INSERT INTO listing_snapshots(listing_id,fingerprint,normalized_phone)
             VALUES (?1,'fp','+491701234567')",
            [listing],
        )
        .unwrap();
        let snapshot = db.last_insert_rowid();
        db.execute(
            "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
            params![snapshot, listing],
        )
        .unwrap();
        drop(db);
        assert!(core.confirm_identity_match(listing, target).is_err());
        assert_eq!(core.status().unwrap().person_count, 2);
    }

    #[test]
    fn image_evidence_needs_independent_pictures() {
        assert_eq!(compare_images(&[0, 0], &[0]).1, 1);
        assert_eq!(compare_images(&[0, 1], &[0, 1]).1, 2);
        assert_eq!(compare_images(&[0xffffffffffffffff], &[0]).0, 0);
    }
    #[test]
    fn phone_only_is_a_candidate_and_ties_are_ambiguous() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let original = core.create_person(Some("Original".into())).unwrap();
        let first = core.create_person(Some("Candidate A".into())).unwrap();
        let second = core.create_person(Some("Candidate B".into())).unwrap();
        let listing = core
            .add_listing(original, None, "LADIES".into(), "35".into(), None)
            .unwrap();
        {
            let db = core.connection().unwrap();
            record_phone(&db, first, listing, Some("0170 1234567")).unwrap();
            record_phone(&db, second, listing, Some("0170 1234567")).unwrap();
            db.execute(
                "INSERT INTO listing_snapshots
                (listing_id,fingerprint,normalized_phone) VALUES (?1,'fp','+491701234567')",
                [listing],
            )
            .unwrap();
            db.execute(
                "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
                params![db.last_insert_rowid(), listing],
            )
            .unwrap();
            refresh(&db, listing).unwrap();
            let state: (String, Option<i64>) = db
                .query_row(
                    "SELECT match_decision,candidate_person_id FROM listings WHERE listing_id=?1",
                    [listing],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )
                .unwrap();
            assert_eq!(state, ("AMBIGUOUS".into(), None));
        }
        assert_eq!(core.list_identity_candidates(listing).unwrap().len(), 2);
        assert_eq!(core.status().unwrap().person_count, 3);
    }

    #[test]
    fn conflicting_user_decisions_prevent_merge_without_data_loss() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let source = core.create_person(Some("Source".into())).unwrap();
        let target = core.create_person(Some("Target".into())).unwrap();
        let listing = core
            .add_listing(source, None, "LADIES".into(), "44".into(), None)
            .unwrap();
        let target_listing = core
            .add_listing(target, None, "LADIES".into(), "45".into(), None)
            .unwrap();
        core.set_preference(source, crate::Preference::Like)
            .unwrap();
        core.set_preference(target, crate::Preference::Dislike)
            .unwrap();
        {
            let db = core.connection().unwrap();
            record_phone(&db, source, listing, Some("0170 5556677")).unwrap();
            record_phone(&db, target, target_listing, Some("0170 5556677")).unwrap();
            db.execute(
                "INSERT INTO listing_snapshots (listing_id,fingerprint,normalized_phone)
                 VALUES (?1,'conflict','+491705556677')",
                [listing],
            )
            .unwrap();
            db.execute(
                "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
                params![db.last_insert_rowid(), listing],
            )
            .unwrap();
        }
        assert!(matches!(
            core.confirm_identity_match(listing, target),
            Err(CoreError::IdentityConflict(_))
        ));
        assert_eq!(core.status().unwrap().person_count, 2);
        assert_eq!(
            core.get_person(source).unwrap().unwrap().preference,
            crate::Preference::Like
        );
        assert_eq!(
            core.get_person(target).unwrap().unwrap().preference,
            crate::Preference::Dislike
        );
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
        let target_listing = core
            .add_listing(target, None, "LADIES".into(), "2".into(), None)
            .unwrap();
        core.save_person_note(listing, source, None, Some("Source note".into()))
            .unwrap();
        core.save_person_note(target_listing, target, None, Some("Target note".into()))
            .unwrap();
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
                "UPDATE listings SET first_seen_at='2026-01-01T00:00:00Z' WHERE listing_id=?1",
                [target_listing],
            )
            .unwrap();
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
        assert_eq!(saved.rows[0].person_id, target);
        assert_eq!(saved.rows[0].listing_count, 2);
        assert_eq!(
            saved.rows[0].latest_change_type.as_deref(),
            Some("NEW_LISTING_FOR_PERSON")
        );
        assert_eq!(
            core.person_note(listing, target).unwrap().as_deref(),
            Some("Target note\n\nSource note")
        );
        assert!(core.person_note(listing, source).is_err());
        // The exact same listing survives the merge. Old rendered owner tokens must fail
        // at the existing native admission boundary, including CAS and note writes.
        assert!(
            core.set_swipe_preference(listing, source, None, crate::Preference::Like)
                .is_err()
        );
        assert!(
            core.set_swipe_preference(
                listing,
                source,
                Some(crate::Preference::None),
                crate::Preference::Dislike
            )
            .is_err()
        );
        assert!(
            core.save_person_note(listing, source, Some("Source note".into()), None)
                .is_err()
        );
        assert!(core.confirm_identity_match(listing, target).is_err());
        assert_eq!(core.profile_identity(listing).unwrap().person_id, target);
        let state = core.get_person(target).unwrap().unwrap();
        assert!(state.favorite);
        assert_eq!(state.preference, crate::Preference::None);
        assert_eq!(
            core.person_note(listing, target).unwrap().as_deref(),
            Some("Target note\n\nSource note")
        );
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
        let event_previous: i64 = db
            .query_row(
                "SELECT previous_person_id FROM identity_events WHERE listing_id=?1",
                [listing],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(event_previous, source);
    }

    #[test]
    fn confirmation_rechecks_evidence_after_current_snapshot_changes() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let source = core.create_person(None).unwrap();
        let target = core.create_person(None).unwrap();
        let listing = core
            .add_listing(
                source,
                None,
                "LADIES".into(),
                "evidence-change".into(),
                None,
            )
            .unwrap();
        let target_listing = core
            .add_listing(
                target,
                None,
                "LADIES".into(),
                "evidence-target".into(),
                None,
            )
            .unwrap();
        {
            let db = core.connection().unwrap();
            record_phone(&db, target, target_listing, Some("0170 1234567")).unwrap();
            db.execute(
                "INSERT INTO listing_snapshots (listing_id,fingerprint,normalized_phone)
                 VALUES (?1,'before','+491701234567')",
                [listing],
            )
            .unwrap();
            db.execute(
                "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
                params![db.last_insert_rowid(), listing],
            )
            .unwrap();
            refresh(&db, listing).unwrap();
        }
        let rendered = core.list_identity_candidates(listing).unwrap();
        assert_eq!(rendered.len(), 1);
        assert_eq!(rendered[0].person_id, target);
        {
            let db = core.connection().unwrap();
            db.execute(
                "INSERT INTO listing_snapshots (listing_id,fingerprint)
                 VALUES (?1,'after')",
                [listing],
            )
            .unwrap();
            db.execute(
                "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
                params![db.last_insert_rowid(), listing],
            )
            .unwrap();
            // Intentionally do not refresh the cached match status: confirm must
            // use current evidence, not a candidate that was previously rendered.
        }
        assert!(core.confirm_identity_match(listing, target).is_err());
        assert_eq!(core.profile_identity(listing).unwrap().person_id, source);
        assert_eq!(core.status().unwrap().person_count, 2);
        assert!(core.list_identity_candidates(listing).unwrap().is_empty());
    }

    #[test]
    fn known_gap_stale_confirm_can_merge_a_different_current_source_owner() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let a = core.create_person(None).unwrap();
        let b = core.create_person(None).unwrap();
        let c = core.create_person(None).unwrap();
        let listing = core
            .add_listing(a, None, "LADIES".into(), "a".into(), None)
            .unwrap();
        let lb = core
            .add_listing(b, None, "LADIES".into(), "b".into(), None)
            .unwrap();
        let lc = core
            .add_listing(c, None, "LADIES".into(), "c".into(), None)
            .unwrap();
        {
            let db = core.connection().unwrap();
            record_phone(&db, b, lb, Some("0170 1234567")).unwrap();
            record_phone(&db, c, lc, Some("0170 1234567")).unwrap();
            db.execute(
                "INSERT INTO listing_snapshots (listing_id,fingerprint,normalized_phone)
                 VALUES (?1,'rendered','+491701234567')",
                [listing],
            )
            .unwrap();
            db.execute(
                "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
                params![db.last_insert_rowid(), listing],
            )
            .unwrap();
        }
        let rendered = core.list_identity_candidates(listing).unwrap();
        assert!(rendered.iter().any(|candidate| candidate.person_id == b));
        assert!(rendered.iter().any(|candidate| candidate.person_id == c));
        assert_eq!(core.profile_identity(listing).unwrap().person_id, a);
        core.confirm_identity_match(listing, b).unwrap();
        assert_eq!(core.profile_identity(listing).unwrap().person_id, b);
        // A stale A->C confirmation currently cannot carry expected source A.
        // Current evidence still supports C, so the old API merges B->C.
        core.confirm_identity_match(listing, c).unwrap();
        assert_eq!(core.profile_identity(listing).unwrap().person_id, c);
        assert_eq!(core.status().unwrap().person_count, 1);
    }
}
