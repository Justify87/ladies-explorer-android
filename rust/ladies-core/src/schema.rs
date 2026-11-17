use rusqlite::Connection;

use crate::CoreError;

pub const VERSION: u32 = 1;

const VERSION_1: &str = r#"
CREATE TABLE persons (
    person_id INTEGER PRIMARY KEY,
    display_name TEXT,
    first_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    last_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE person_user_state (
    person_id INTEGER PRIMARY KEY REFERENCES persons(person_id) ON DELETE CASCADE,
    preference TEXT NOT NULL DEFAULT 'NONE' CHECK (preference IN ('NONE','LIKE','DISLIKE')),
    favorite INTEGER NOT NULL DEFAULT 0 CHECK (favorite IN (0,1)),
    repeat_interest TEXT NOT NULL DEFAULT 'UNKNOWN',
    private_note TEXT,
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE source_profiles (
    source_profile_id INTEGER PRIMARY KEY,
    person_id INTEGER NOT NULL REFERENCES persons(person_id) ON DELETE CASCADE,
    source TEXT NOT NULL DEFAULT 'LADIES',
    external_profile_id TEXT,
    canonical_id TEXT,
    first_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    last_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    link_reason TEXT,
    UNIQUE(source, external_profile_id)
);
CREATE INDEX idx_source_profile_person ON source_profiles(person_id);
CREATE INDEX idx_source_profile_canonical ON source_profiles(canonical_id);

CREATE TABLE listings (
    listing_id INTEGER PRIMARY KEY,
    person_id INTEGER NOT NULL REFERENCES persons(person_id) ON DELETE CASCADE,
    source_profile_id INTEGER REFERENCES source_profiles(source_profile_id) ON DELETE SET NULL,
    source TEXT NOT NULL DEFAULT 'LADIES',
    external_listing_id TEXT NOT NULL,
    current_url TEXT,
    category TEXT,
    published_at TEXT,
    first_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    last_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    current_status TEXT NOT NULL DEFAULT 'UNKNOWN',
    status_checked_at TEXT,
    ended_at TEXT,
    current_snapshot_id INTEGER REFERENCES listing_snapshots(snapshot_id) ON DELETE SET NULL,
    match_decision TEXT NOT NULL DEFAULT 'NO_MATCH',
    candidate_person_id INTEGER REFERENCES persons(person_id) ON DELETE SET NULL,
    match_reason TEXT,
    match_best_distance INTEGER,
    match_image_count INTEGER NOT NULL DEFAULT 0,
    match_strong_image_count INTEGER NOT NULL DEFAULT 0,
    match_phone INTEGER NOT NULL DEFAULT 0,
    match_source_profile INTEGER NOT NULL DEFAULT 0,
    UNIQUE(source, external_listing_id)
);
CREATE INDEX idx_listing_person ON listings(person_id);
CREATE INDEX idx_listing_source_profile ON listings(source_profile_id);
CREATE INDEX idx_listing_status ON listings(current_status);

CREATE TABLE person_identifiers (
    identifier_id INTEGER PRIMARY KEY,
    person_id INTEGER NOT NULL REFERENCES persons(person_id) ON DELETE CASCADE,
    listing_id INTEGER REFERENCES listings(listing_id) ON DELETE SET NULL,
    kind TEXT NOT NULL,
    normalized_value TEXT NOT NULL,
    raw_value TEXT,
    first_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    last_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    UNIQUE(person_id, kind, normalized_value)
);
CREATE INDEX idx_identifier_kind_value ON person_identifiers(kind, normalized_value);

CREATE TABLE listing_snapshots (
    snapshot_id INTEGER PRIMARY KEY,
    listing_id INTEGER NOT NULL REFERENCES listings(listing_id) ON DELETE CASCADE,
    captured_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    fingerprint TEXT NOT NULL,
    source_url TEXT,
    name TEXT,
    city TEXT,
    postal_code TEXT,
    hidden_location TEXT,
    category TEXT,
    description TEXT,
    published_at TEXT,
    external_profile_id TEXT,
    canonical_id TEXT,
    phone_display TEXT,
    normalized_phone TEXT,
    traits_json TEXT,
    services_json TEXT,
    opening_times_json TEXT,
    auxiliary_json TEXT,
    raw_html_sha256 TEXT,
    raw_html_path TEXT,
    UNIQUE(listing_id, fingerprint)
);
CREATE INDEX idx_snapshot_listing_time ON listing_snapshots(listing_id, captured_at);

CREATE TABLE snapshot_values (
    snapshot_value_id INTEGER PRIMARY KEY,
    snapshot_id INTEGER NOT NULL REFERENCES listing_snapshots(snapshot_id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    group_name TEXT,
    label TEXT,
    value TEXT NOT NULL,
    position INTEGER NOT NULL DEFAULT 0,
    provenance TEXT NOT NULL DEFAULT 'DOM_VISIBLE',
    confidence_rank INTEGER NOT NULL DEFAULT 100
);
CREATE INDEX idx_snapshot_value_lookup ON snapshot_values(kind, group_name, label, value);

CREATE TABLE media_assets (
    media_asset_id INTEGER PRIMARY KEY,
    sha256 TEXT NOT NULL UNIQUE,
    dhash TEXT,
    kind TEXT NOT NULL,
    mime_type TEXT,
    byte_size INTEGER,
    width INTEGER,
    height INTEGER,
    local_path TEXT NOT NULL,
    first_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    last_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX idx_media_dhash ON media_assets(dhash);

CREATE TABLE media_sources (
    media_source_id INTEGER PRIMARY KEY,
    remote_base_url TEXT NOT NULL UNIQUE,
    last_remote_url TEXT,
    revision_token TEXT,
    etag TEXT,
    last_modified TEXT,
    content_length INTEGER,
    media_asset_id INTEGER REFERENCES media_assets(media_asset_id) ON DELETE SET NULL,
    last_checked_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE snapshot_media (
    snapshot_media_id INTEGER PRIMARY KEY,
    snapshot_id INTEGER NOT NULL REFERENCES listing_snapshots(snapshot_id) ON DELETE CASCADE,
    media_asset_id INTEGER REFERENCES media_assets(media_asset_id) ON DELETE SET NULL,
    media_source_id INTEGER REFERENCES media_sources(media_source_id) ON DELETE SET NULL,
    position INTEGER NOT NULL,
    kind TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'GALLERY',
    remote_url TEXT NOT NULL,
    remote_base_url TEXT,
    remote_variant TEXT,
    revision_token TEXT,
    etag TEXT,
    last_modified TEXT,
    UNIQUE(snapshot_id, position, kind)
);
CREATE INDEX idx_snapshot_media_asset ON snapshot_media(media_asset_id);

CREATE TABLE search_runs (
    search_run_id INTEGER PRIMARY KEY,
    started_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    finished_at TEXT,
    city TEXT,
    city_slug TEXT,
    radius_km INTEGER,
    filters_json TEXT,
    advert_count INTEGER,
    distance_searched INTEGER,
    metadata_json TEXT
);

CREATE TABLE search_results (
    search_result_id INTEGER PRIMARY KEY,
    search_run_id INTEGER NOT NULL REFERENCES search_runs(search_run_id) ON DELETE CASCADE,
    listing_id INTEGER NOT NULL REFERENCES listings(listing_id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    observed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    thumbnail_url TEXT,
    name TEXT,
    city TEXT,
    has_video INTEGER NOT NULL DEFAULT 0,
    is_premium INTEGER NOT NULL DEFAULT 0,
    has_ai_content INTEGER NOT NULL DEFAULT 0,
    markers_json TEXT,
    UNIQUE(search_run_id, listing_id)
);
CREATE INDEX idx_search_result_listing ON search_results(listing_id);

CREATE TABLE listing_observations (
    observation_id INTEGER PRIMARY KEY,
    listing_id INTEGER NOT NULL REFERENCES listings(listing_id) ON DELETE CASCADE,
    observed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    source TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'SEEN',
    http_status INTEGER,
    search_run_id INTEGER REFERENCES search_runs(search_run_id) ON DELETE SET NULL,
    snapshot_id INTEGER REFERENCES listing_snapshots(snapshot_id) ON DELETE SET NULL,
    details_json TEXT
);
CREATE INDEX idx_observation_listing_time ON listing_observations(listing_id, observed_at);

CREATE TABLE saved_searches (
    saved_search_id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    filters_json TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE change_events (
    change_event_id INTEGER PRIMARY KEY,
    person_id INTEGER NOT NULL REFERENCES persons(person_id) ON DELETE CASCADE,
    listing_id INTEGER REFERENCES listings(listing_id) ON DELETE CASCADE,
    from_snapshot_id INTEGER REFERENCES listing_snapshots(snapshot_id) ON DELETE SET NULL,
    to_snapshot_id INTEGER REFERENCES listing_snapshots(snapshot_id) ON DELETE SET NULL,
    event_type TEXT NOT NULL,
    field_key TEXT,
    old_json TEXT,
    new_json TEXT,
    importance TEXT NOT NULL DEFAULT 'MEDIUM',
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX idx_change_person_time ON change_events(person_id, created_at);
CREATE INDEX idx_change_listing_time ON change_events(listing_id, created_at);

CREATE TABLE identity_evidence (
    identity_evidence_id INTEGER PRIMARY KEY,
    listing_id INTEGER NOT NULL REFERENCES listings(listing_id) ON DELETE CASCADE,
    person_id INTEGER NOT NULL REFERENCES persons(person_id) ON DELETE CASCADE,
    candidate_person_id INTEGER REFERENCES persons(person_id) ON DELETE SET NULL,
    kind TEXT NOT NULL,
    value TEXT,
    score REAL,
    best_distance INTEGER,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX idx_identity_evidence_listing ON identity_evidence(listing_id);

CREATE TABLE identity_events (
    identity_event_id INTEGER PRIMARY KEY,
    listing_id INTEGER NOT NULL REFERENCES listings(listing_id) ON DELETE CASCADE,
    previous_person_id INTEGER REFERENCES persons(person_id) ON DELETE SET NULL,
    new_person_id INTEGER NOT NULL REFERENCES persons(person_id) ON DELETE CASCADE,
    action TEXT NOT NULL,
    evidence_json TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE encounters (
    encounter_id INTEGER PRIMARY KEY,
    person_id INTEGER NOT NULL REFERENCES persons(person_id) ON DELETE CASCADE,
    listing_id INTEGER REFERENCES listings(listing_id) ON DELETE SET NULL,
    occurred_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    duration_minutes INTEGER,
    base_price_cents INTEGER,
    total_price_cents INTEGER,
    overall_rating INTEGER CHECK(overall_rating IS NULL OR overall_rating BETWEEN 1 AND 5),
    sympathy_rating INTEGER CHECK(sympathy_rating IS NULL OR sympathy_rating BETWEEN 1 AND 5),
    photo_status TEXT NOT NULL DEFAULT 'UNKNOWN',
    service_accuracy TEXT NOT NULL DEFAULT 'UNKNOWN',
    hygiene TEXT NOT NULL DEFAULT 'UNKNOWN',
    kissing TEXT NOT NULL DEFAULT 'UNKNOWN',
    negotiable TEXT NOT NULL DEFAULT 'UNKNOWN',
    note TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX idx_encounter_person_time ON encounters(person_id, occurred_at);

CREATE TABLE encounter_services (
    encounter_service_id INTEGER PRIMARY KEY,
    encounter_id INTEGER NOT NULL REFERENCES encounters(encounter_id) ON DELETE CASCADE,
    service_name TEXT NOT NULL,
    amount_cents INTEGER,
    performed INTEGER NOT NULL DEFAULT 1,
    note TEXT
);

CREATE TABLE price_lists (
    price_list_id INTEGER PRIMARY KEY,
    person_id INTEGER NOT NULL REFERENCES persons(person_id) ON DELETE CASCADE,
    listing_id INTEGER REFERENCES listings(listing_id) ON DELETE SET NULL,
    observed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    source TEXT NOT NULL DEFAULT 'USER_ENTERED',
    currency TEXT NOT NULL DEFAULT 'EUR',
    note TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX idx_price_list_person_time ON price_lists(person_id, observed_at);

CREATE TABLE price_items (
    price_item_id INTEGER PRIMARY KEY,
    price_list_id INTEGER NOT NULL REFERENCES price_lists(price_list_id) ON DELETE CASCADE,
    item_type TEXT NOT NULL DEFAULT 'BASE',
    label TEXT,
    duration_minutes INTEGER,
    service TEXT,
    amount_cents INTEGER NOT NULL,
    note TEXT
);

CREATE TABLE app_settings (
    key TEXT PRIMARY KEY,
    value_json TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
"#;

pub fn migrate(db: &mut Connection) -> Result<(), CoreError> {
    db.execute_batch("PRAGMA foreign_keys = ON;")
        .map_err(CoreError::from)?;
    let current: u32 = db
        .pragma_query_value(None, "user_version", |row| row.get(0))
        .map_err(CoreError::from)?;

    if current > VERSION {
        return Err(CoreError::UnsupportedSchema(current));
    }
    if current == VERSION {
        return Ok(());
    }

    // Only the previous technical scaffold is supported, not arbitrary web-app databases.
    let unexpected_tables: i64 = db
        .query_row(
            "SELECT COUNT(*) FROM sqlite_master
             WHERE type='table' AND name NOT LIKE 'sqlite_%'
               AND name != 'scaffold_notes'",
            [],
            |row| row.get(0),
        )
        .map_err(CoreError::from)?;
    if unexpected_tables != 0 {
        return Err(CoreError::UnsupportedSchema(current));
    }

    let transaction = db.transaction().map_err(CoreError::from)?;
    transaction
        .execute_batch(VERSION_1)
        .map_err(CoreError::from)?;
    transaction
        .execute_batch("DROP TABLE IF EXISTS scaffold_notes; PRAGMA user_version = 1;")
        .map_err(CoreError::from)?;
    transaction.commit().map_err(CoreError::from)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn initial_migration_is_idempotent() {
        let mut db = Connection::open_in_memory().unwrap();
        migrate(&mut db).unwrap();
        migrate(&mut db).unwrap();
        let version: u32 = db
            .pragma_query_value(None, "user_version", |r| r.get(0))
            .unwrap();
        assert_eq!(version, VERSION);
        let count: i64 = db
            .query_row(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='persons'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(count, 1);
    }

    #[test]
    fn migrating_scaffold_drops_only_scaffold_data() {
        let mut db = Connection::open_in_memory().unwrap();
        db.execute_batch(
            "CREATE TABLE scaffold_notes (note_key TEXT PRIMARY KEY, note_value TEXT NOT NULL);
            INSERT INTO scaffold_notes VALUES ('old', 'test');",
        )
        .unwrap();
        migrate(&mut db).unwrap();
        let count: i64 = db
            .query_row(
                "SELECT COUNT(*) FROM sqlite_master WHERE name='scaffold_notes'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(count, 0);
    }

    #[test]
    fn rejects_unknown_schema_without_wiping() {
        let mut db = Connection::open_in_memory().unwrap();
        db.execute_batch(
            "CREATE TABLE important (value TEXT); INSERT INTO important VALUES ('keep');",
        )
        .unwrap();
        assert!(matches!(
            migrate(&mut db),
            Err(CoreError::UnsupportedSchema(0))
        ));
        let value: String = db
            .query_row("SELECT value FROM important", [], |r| r.get(0))
            .unwrap();
        assert_eq!(value, "keep");
    }

    #[test]
    fn rejects_newer_schema() {
        let mut db = Connection::open_in_memory().unwrap();
        db.pragma_update(None, "user_version", 99).unwrap();
        assert!(matches!(
            migrate(&mut db),
            Err(CoreError::UnsupportedSchema(99))
        ));
    }

    #[test]
    fn foreign_keys_are_enforced() {
        let mut db = Connection::open_in_memory().unwrap();
        migrate(&mut db).unwrap();
        let res = db.execute(
            "INSERT INTO listings (person_id, source, external_listing_id) VALUES (77,'LADIES','x')",
            []
        );
        assert!(res.is_err());
    }
}
