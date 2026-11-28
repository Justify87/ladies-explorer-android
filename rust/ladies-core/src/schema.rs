use rusqlite::Connection;

use crate::CoreError;

pub const VERSION: u32 = 3;
const APPLICATION_ID: i32 = 0x4C44_5850; // LDXP: Ladies Explorer Android

pub(crate) const VERSION_1: &str = r#"
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

CREATE TRIGGER person_initial_user_state
AFTER INSERT ON persons
BEGIN
    INSERT INTO person_user_state (person_id) VALUES (NEW.person_id);
END;

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
CREATE INDEX idx_person_seen ON persons(last_seen_at DESC, person_id DESC);

CREATE TRIGGER listing_source_profile_insert
BEFORE INSERT ON listings
WHEN NEW.source_profile_id IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'source profile belongs to a different person or source')
    WHERE NOT EXISTS (
        SELECT 1 FROM source_profiles p
        WHERE p.source_profile_id = NEW.source_profile_id
          AND p.person_id = NEW.person_id AND p.source = NEW.source
    );
END;

CREATE TRIGGER listing_source_profile_update
BEFORE UPDATE OF source_profile_id, person_id, source ON listings
WHEN NEW.source_profile_id IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'source profile belongs to a different person or source')
    WHERE NOT EXISTS (
        SELECT 1 FROM source_profiles p
        WHERE p.source_profile_id = NEW.source_profile_id
          AND p.person_id = NEW.person_id AND p.source = NEW.source
    );
END;

CREATE TRIGGER source_profile_identity_update
BEFORE UPDATE OF person_id, source ON source_profiles
BEGIN
    SELECT RAISE(ABORT, 'source profile has listings with a different person or source')
    WHERE EXISTS (
        SELECT 1 FROM listings l WHERE l.source_profile_id = OLD.source_profile_id
          AND (l.person_id != NEW.person_id OR l.source != NEW.source)
    );
END;

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
    saved_search_id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    filters_json TEXT NOT NULL,
    definition_version INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    last_run_at TEXT,
    last_attempt_at TEXT,
    last_error TEXT,
    last_delta_json TEXT,
    unread_new_results INTEGER NOT NULL DEFAULT 0 CHECK (unread_new_results IN (0,1)),
    new_results_at TEXT
);

CREATE TABLE saved_search_results (
    saved_search_id TEXT NOT NULL REFERENCES saved_searches(saved_search_id) ON DELETE CASCADE,
    result_key TEXT NOT NULL,
    snapshot_json TEXT NOT NULL,
    position INTEGER NOT NULL DEFAULT 0,
    first_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    last_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    first_visible_at TEXT,
    present INTEGER NOT NULL DEFAULT 1 CHECK (present IN (0,1)),
    visible INTEGER NOT NULL DEFAULT 0 CHECK (visible IN (0,1)),
    PRIMARY KEY(saved_search_id, result_key)
);
CREATE INDEX idx_saved_search_result_visible
    ON saved_search_results(saved_search_id, visible, position);

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

// A merge creates historical identity IDs. They must not cascade when a person is removed.
const VERSION_2: &str = r#"
CREATE TABLE identity_events_v2 (
    identity_event_id INTEGER PRIMARY KEY,
    listing_id INTEGER NOT NULL REFERENCES listings(listing_id) ON DELETE CASCADE,
    previous_person_id INTEGER,
    new_person_id INTEGER NOT NULL,
    action TEXT NOT NULL,
    evidence_json TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
INSERT INTO identity_events_v2
 (identity_event_id, listing_id, previous_person_id, new_person_id, action, evidence_json, created_at)
 SELECT identity_event_id, listing_id,
        COALESCE(previous_person_id,
            CASE WHEN json_valid(evidence_json)
                 THEN json_extract(evidence_json, '$.previous_person_id')
            END),
        new_person_id, action, evidence_json, created_at
 FROM identity_events;
DROP TABLE identity_events;
ALTER TABLE identity_events_v2 RENAME TO identity_events;
"#;

const VERSION_3: &str = r#"
CREATE TABLE listing_locations (
    listing_id INTEGER NOT NULL REFERENCES listings(listing_id) ON DELETE CASCADE,
    city_key TEXT NOT NULL,
    postal_key TEXT NOT NULL,
    latitude REAL,
    longitude REAL,
    precision TEXT CHECK (precision IN ('POSTAL','CITY')),
    provenance TEXT NOT NULL CHECK (provenance IN ('NONE','GEONAMES','LEGACY_POSTAL','LEGACY_CITY')),
    dataset_version TEXT NOT NULL,
    CHECK ((latitude IS NULL AND longitude IS NULL AND precision IS NULL AND provenance='NONE')
        OR (latitude IS NOT NULL AND longitude IS NOT NULL AND precision IS NOT NULL
            AND latitude BETWEEN 47 AND 56 AND longitude BETWEEN 5 AND 16 AND provenance<>'NONE')),
    PRIMARY KEY(listing_id,city_key,postal_key)
);
"#;

fn schema_manifest(db: &Connection) -> Result<Vec<(String, String, String)>, CoreError> {
    let mut query = db.prepare(
        "SELECT type, name, sql FROM sqlite_master
         WHERE name NOT LIKE 'sqlite_%' AND sql IS NOT NULL
         ORDER BY type, name",
    )?;
    let rows = query.query_map([], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)))?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

fn validate_schema(db: &Connection, version: u32) -> Result<(), CoreError> {
    let reference = Connection::open_in_memory()?;
    reference.execute_batch(VERSION_1)?;
    if version >= 2 {
        reference.execute_batch(VERSION_2)?;
    }
    if version >= 3 {
        reference.execute_batch(VERSION_3)?;
    }
    if schema_manifest(db)? != schema_manifest(&reference)? {
        return Err(CoreError::SchemaMismatch(format!(
            "Database objects do not match schema v{version}"
        )));
    }
    Ok(())
}

pub fn migrate(db: &mut Connection) -> Result<(), CoreError> {
    db.execute_batch("PRAGMA foreign_keys = ON;")?;
    let version: u32 = db.pragma_query_value(None, "user_version", |row| row.get(0))?;
    let application_id: i32 = db.pragma_query_value(None, "application_id", |row| row.get(0))?;
    if version > VERSION {
        return Err(CoreError::UnsupportedSchema(version));
    }
    if version > 0 {
        if application_id != APPLICATION_ID {
            return Err(CoreError::SchemaMismatch(
                "This SQLite database is not a Ladies Explorer Android database".into(),
            ));
        }
        validate_schema(db, version)?;
        if version == VERSION {
            return Ok(());
        }
    } else {
        if application_id != 0 {
            return Err(CoreError::SchemaMismatch(
                "Unrecognized application ID for an unversioned database".into(),
            ));
        }
        let existing = schema_manifest(db)?;
        let names: Vec<&str> = existing
            .iter()
            .filter(|(kind, _, _)| kind == "table")
            .map(|(_, name, _)| name.as_str())
            .collect();
        if !names.is_empty() && names != ["scaffold_notes"] {
            return Err(CoreError::UnsupportedSchema(version));
        }
        if names == ["scaffold_notes"] {
            let mut query = db.prepare("PRAGMA table_info(scaffold_notes)")?;
            let columns = query
                .query_map([], |row| row.get::<_, String>(1))?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            if columns != ["note_key", "note_value"] || existing.len() != 1 {
                return Err(CoreError::UnsupportedSchema(version));
            }
        }
    }
    let tx = db.transaction()?;
    if version == 0 {
        tx.execute_batch(VERSION_1)?;
        tx.execute_batch("DROP TABLE IF EXISTS scaffold_notes;")?;
        tx.pragma_update(None, "application_id", APPLICATION_ID)?;
    }
    if version < 2 {
        tx.execute_batch(VERSION_2)?;
    }
    tx.execute_batch(VERSION_3)?;
    tx.pragma_update(None, "user_version", VERSION)?;
    validate_schema(&tx, VERSION)?;
    let broken: bool = tx.prepare("PRAGMA foreign_key_check")?.exists([])?;
    if broken {
        return Err(CoreError::SchemaMismatch(
            "Migration violates foreign keys".into(),
        ));
    }
    tx.commit()?;
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
        let app: i32 = db
            .pragma_query_value(None, "application_id", |r| r.get(0))
            .unwrap();
        assert_eq!(app, APPLICATION_ID);
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
    fn version_one_migration_preserves_historical_identity_references() {
        let mut db = Connection::open_in_memory().unwrap();
        db.execute_batch(VERSION_1).unwrap();
        db.pragma_update(None, "application_id", APPLICATION_ID)
            .unwrap();
        db.pragma_update(None, "user_version", 1).unwrap();
        db.execute_batch(
            r#"PRAGMA foreign_keys=ON;
            INSERT INTO persons(person_id) VALUES (1),(2);
            INSERT INTO listings(listing_id,person_id,external_listing_id) VALUES (10,2,'old');
            INSERT INTO identity_events
            (identity_event_id,listing_id,previous_person_id,new_person_id,action,evidence_json)
            VALUES (20,10,1,2,'MANUAL_MATCH','{"previous_person_id":1}');
            DELETE FROM persons WHERE person_id=1;"#,
        )
        .unwrap();
        let old_before: Option<i64> = db
            .query_row(
                "SELECT previous_person_id FROM identity_events WHERE identity_event_id=20",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(old_before, None);
        migrate(&mut db).unwrap();
        let old: i64 = db
            .query_row(
                "SELECT previous_person_id FROM identity_events WHERE identity_event_id=20",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(old, 1);
        assert_eq!(
            db.pragma_query_value::<u32, _>(None, "user_version", |r| r.get(0))
                .unwrap(),
            VERSION
        );
        migrate(&mut db).unwrap();
    }

    #[test]
    fn version_two_migration_keeps_listings_and_legacy_location_cache() {
        let mut db = Connection::open_in_memory().unwrap();
        db.execute_batch(VERSION_1).unwrap();
        db.execute_batch(VERSION_2).unwrap();
        db.pragma_update(None, "application_id", APPLICATION_ID)
            .unwrap();
        db.pragma_update(None, "user_version", 2).unwrap();
        db.execute_batch(
            "INSERT INTO persons(person_id) VALUES (1);
             INSERT INTO listings(listing_id,person_id,external_listing_id) VALUES (10,1,'fixture');
             INSERT INTO app_settings(key,value_json) VALUES ('map:city:DE:fixture','{}');",
        )
        .unwrap();
        migrate(&mut db).unwrap();
        migrate(&mut db).unwrap();
        let counts: (i64, i64, i64) = db
            .query_row(
                "SELECT (SELECT COUNT(*) FROM listings),
                        (SELECT COUNT(*) FROM app_settings),
                        (SELECT COUNT(*) FROM listing_locations)",
                [],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .unwrap();
        assert_eq!(counts, (1, 1, 0));
        assert!(
            db.execute(
                "INSERT INTO listing_locations
                 (listing_id,city_key,postal_key,latitude,longitude,precision,provenance,dataset_version)
                 VALUES (10,'fixture','',0,0,'CITY','GEONAMES','fixture')",
                [],
            )
            .is_err()
        );
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
        assert!(matches!(
            res,
            Err(rusqlite::Error::SqliteFailure(error, _))
                if error.extended_code == rusqlite::ffi::SQLITE_CONSTRAINT_FOREIGNKEY
        ));
    }

    #[test]
    fn legacy_webapp_v1_is_rejected_without_modification() {
        let mut db = Connection::open_in_memory().unwrap();
        db.execute_batch(
            "CREATE TABLE persons (person_id INTEGER PRIMARY KEY, legacy_value TEXT);
             INSERT INTO persons (legacy_value) VALUES ('keep');
             PRAGMA user_version = 1;",
        )
        .unwrap();
        assert!(matches!(
            migrate(&mut db),
            Err(CoreError::SchemaMismatch(_))
        ));
        let value: String = db
            .query_row("SELECT legacy_value FROM persons", [], |row| row.get(0))
            .unwrap();
        assert_eq!(value, "keep");
    }

    #[test]
    fn schema_drift_with_correct_version_is_not_accepted() {
        let mut db = Connection::open_in_memory().unwrap();
        migrate(&mut db).unwrap();
        db.execute_batch("ALTER TABLE persons ADD COLUMN rogue TEXT;")
            .unwrap();
        assert!(matches!(
            migrate(&mut db),
            Err(CoreError::SchemaMismatch(_))
        ));
    }

    #[test]
    fn missing_table_with_correct_version_is_not_accepted() {
        let mut db = Connection::open_in_memory().unwrap();
        migrate(&mut db).unwrap();
        db.execute_batch("DROP TABLE saved_search_results;")
            .unwrap();
        assert!(matches!(
            migrate(&mut db),
            Err(CoreError::SchemaMismatch(_))
        ));
    }

    #[test]
    fn saved_search_string_ids_and_result_history_survive_reopening() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("saved_searches.sqlite");
        let mut db = Connection::open(&path).unwrap();
        migrate(&mut db).unwrap();
        db.execute(
            "INSERT INTO saved_searches
             (saved_search_id, name, filters_json, definition_version,
              unread_new_results, last_delta_json)
             VALUES (?1, ?2, ?3, 7, 1, ?4)",
            rusqlite::params!["uuid-abc-4711", "Leipzig", "{}", "{\"new\":2}"],
        )
        .unwrap();
        db.execute(
            "INSERT INTO saved_search_results
             (saved_search_id, result_key, snapshot_json, position, present, visible)
             VALUES (?1, ?2, ?3, 1, 1, 1)",
            rusqlite::params!["uuid-abc-4711", "source-ad-id", "{\"ad\":1}"],
        )
        .unwrap();
        drop(db);
        let mut db = Connection::open(&path).unwrap();
        migrate(&mut db).unwrap();
        let row: (String, i64, i64, String) = db
            .query_row(
                "SELECT saved_search_id, definition_version, unread_new_results, last_delta_json
             FROM saved_searches",
                [],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
            )
            .unwrap();
        assert_eq!(row, ("uuid-abc-4711".into(), 7, 1, "{\"new\":2}".into()));
        let result: (String, String, i64, i64, i64) = db
            .query_row(
                "SELECT result_key, snapshot_json, position, present, visible
                 FROM saved_search_results WHERE saved_search_id=?1",
                ["uuid-abc-4711"],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
            )
            .unwrap();
        assert_eq!(
            result,
            ("source-ad-id".into(), "{\"ad\":1}".into(), 1, 1, 1)
        );
        db.execute(
            "DELETE FROM saved_searches WHERE saved_search_id = ?1",
            ["uuid-abc-4711"],
        )
        .unwrap();
        let remaining: i64 = db
            .query_row("SELECT COUNT(*) FROM saved_search_results", [], |r| {
                r.get(0)
            })
            .unwrap();
        assert_eq!(remaining, 0);
    }

    #[test]
    fn person_insert_always_creates_default_user_state() {
        let mut db = Connection::open_in_memory().unwrap();
        migrate(&mut db).unwrap();
        db.execute("INSERT INTO persons (display_name) VALUES ('Test')", [])
            .unwrap();
        let state: (String, i64) = db
            .query_row(
                "SELECT preference, favorite FROM person_user_state WHERE person_id=1",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(state, ("NONE".into(), 0));
    }

    #[test]
    fn sqlite_rejects_cross_person_and_cross_source_links() {
        let mut db = Connection::open_in_memory().unwrap();
        migrate(&mut db).unwrap();
        db.execute_batch(
            "INSERT INTO persons (person_id, display_name) VALUES (1, 'One'), (2, 'Two');
             INSERT INTO source_profiles (source_profile_id, person_id, source, external_profile_id)
             VALUES (10, 1, 'LADIES', 'profile-10');",
        )
        .unwrap();
        assert!(
            db.execute(
                "INSERT INTO listings (person_id, source_profile_id, source, external_listing_id)
             VALUES (2, 10, 'LADIES', 'ad-other-person')",
                [],
            )
            .is_err()
        );
        assert!(
            db.execute(
                "INSERT INTO listings (person_id, source_profile_id, source, external_listing_id)
             VALUES (1, 10, 'OTHER', 'ad-other-source')",
                [],
            )
            .is_err()
        );
        db.execute(
            "INSERT INTO listings (person_id, source_profile_id, source, external_listing_id)
             VALUES (1, 10, 'LADIES', 'ad-same')",
            [],
        )
        .unwrap();
        for sql in [
            "INSERT INTO source_profiles (person_id, source, external_profile_id)
             VALUES (2, 'LADIES', 'profile-10')",
            "INSERT INTO listings (person_id, source, external_listing_id)
             VALUES (2, 'LADIES', 'ad-same')",
        ] {
            assert!(matches!(
                db.execute(sql, []),
                Err(rusqlite::Error::SqliteFailure(error, _))
                    if error.extended_code == rusqlite::ffi::SQLITE_CONSTRAINT_UNIQUE
            ));
        }
        assert!(
            db.execute(
                "UPDATE listings SET person_id=2 WHERE external_listing_id='ad-same'",
                [],
            )
            .is_err()
        );
        assert!(
            db.execute(
                "UPDATE source_profiles SET source='OTHER' WHERE source_profile_id=10",
                [],
            )
            .is_err()
        );
        assert!(
            db.execute(
                "UPDATE source_profiles SET person_id=2 WHERE source_profile_id=10",
                [],
            )
            .is_err()
        );
        db.execute("DELETE FROM source_profiles WHERE source_profile_id=10", [])
            .unwrap();
        let linked: Option<i64> = db
            .query_row(
                "SELECT source_profile_id FROM listings WHERE external_listing_id='ad-same'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(linked, None);
    }
}
