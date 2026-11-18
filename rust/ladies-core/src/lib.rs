//! App-private Rust persistence and the typed UniFFI boundary.
//! A person is distinct from its source profiles and individual listings.

mod diagnostics;
mod history;
mod identity;
mod legacy_import;
mod media;
mod schema;
mod source;

use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use rusqlite::{Connection, OptionalExtension, params};
use thiserror::Error;

uniffi::setup_scaffolding!();

#[derive(Debug, Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CoreError {
    #[error("Storage operation failed: {0}")]
    Storage(String),
    #[error("No person with id {0}")]
    PersonNotFound(i64),
    #[error("No source profile with id {0}")]
    SourceProfileNotFound(i64),
    #[error("Identity ownership conflict: {0}")]
    IdentityConflict(String),
    #[error("Invalid input: {0}")]
    InvalidInput(String),
    #[error("Unsupported local database schema version {0}")]
    UnsupportedSchema(u32),
    #[error("Legacy import failed: {0}")]
    Import(String),
    #[error("Ladies source request or parsing failed: {0}")]
    Source(String),
    #[error("Media archive error: {0}")]
    Media(String),
    #[error("No LADIES listing with id {0}")]
    ListingNotFound(i64),
    #[error("Incompatible local database schema: {0}")]
    SchemaMismatch(String),
}

impl From<rusqlite::Error> for CoreError {
    fn from(error: rusqlite::Error) -> Self {
        Self::Storage(error.to_string())
    }
}

impl From<std::io::Error> for CoreError {
    fn from(error: std::io::Error) -> Self {
        Self::Import(error.to_string())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Preference {
    None,
    Like,
    Dislike,
}

impl Preference {
    fn as_db(self) -> &'static str {
        match self {
            Self::None => "NONE",
            Self::Like => "LIKE",
            Self::Dislike => "DISLIKE",
        }
    }

    fn from_db(raw: &str) -> Result<Self, CoreError> {
        match raw {
            "NONE" => Ok(Self::None),
            "LIKE" => Ok(Self::Like),
            "DISLIKE" => Ok(Self::Dislike),
            _ => Err(CoreError::Storage(format!(
                "Unknown preference value: {raw}"
            ))),
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DiagnosticStatus {
    pub available: bool,
    pub size_bytes: u64,
    pub last_write_error: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct CoreStatus {
    pub api_version: u32,
    pub schema_version: u32,
    pub person_count: u32,
    pub listing_count: u32,
    pub source_profile_count: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PersonSummary {
    pub person_id: i64,
    pub display_name: Option<String>,
    pub preference: Preference,
    pub favorite: bool,
    pub listing_count: u32,
    pub last_seen_at: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PersonCursor {
    pub last_seen_at: String,
    pub person_id: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PersonPage {
    pub people: Vec<PersonSummary>,
    pub next_cursor: Option<PersonCursor>,
}

type RawPerson = (i64, Option<String>, String, i64, u32, String);

#[derive(Debug, Clone, uniffi::Record)]
pub struct ImportReport {
    pub persons: u32,
    pub listings: u32,
    pub source_profiles: u32,
    pub saved_searches: u32,
    pub manual_price_lists: u32,
    pub media_files: u32,
}

pub use history::ChangeRecord;
pub use identity::IdentityCandidate;
pub use media::MediaArchiveReport;
pub use source::{MediaReference, ProfileResponse, SearchCard, SearchRequest, SearchResponse};

#[derive(uniffi::Object)]
pub struct LadiesCore {
    db: Mutex<Connection>,
    diagnostics: Arc<diagnostics::Diagnostics>,
    source_session: source::SourceSession,
    media_root: Option<std::path::PathBuf>,
}

#[uniffi::export]
impl LadiesCore {
    #[uniffi::constructor]
    pub fn new(database_path: String) -> Result<Arc<Self>, CoreError> {
        if database_path.trim().is_empty() {
            return Err(CoreError::InvalidInput(
                "Database path must not be empty".into(),
            ));
        }
        let media_root = if database_path == ":memory:" {
            None
        } else {
            std::path::Path::new(&database_path)
                .parent()
                .filter(|path| !path.as_os_str().is_empty())
                .map(|parent| parent.join("ladies_data"))
        };
        let diagnostics = Arc::new(diagnostics::Diagnostics::new(&database_path));
        diagnostics.event("CORE", "Opening SQLite");
        let mut db = Connection::open(database_path)?;
        db.busy_timeout(Duration::from_secs(5))?;
        db.execute_batch("PRAGMA journal_mode = WAL;")?;
        schema::migrate(&mut db)?;
        diagnostics.event("CORE", "SQLite schema ready");
        let source_session = source::SourceSession::new(Arc::clone(&diagnostics))?;
        Ok(Arc::new(Self {
            db: Mutex::new(db),
            diagnostics,
            source_session,
            media_root,
        }))
    }

    pub fn import_legacy_bundle(
        &self,
        bundle_path: String,
        app_files_directory: String,
    ) -> Result<ImportReport, CoreError> {
        self.diagnostics
            .event("IMPORT", "Legacy data import requested");
        let result = legacy_import::import_bundle(self, &bundle_path, &app_files_directory);
        match &result {
            Ok(report) => self.diagnostics.event(
                "IMPORT",
                format!(
                    "Import completed: persons={} listings={} media={}",
                    report.persons, report.listings, report.media_files
                ),
            ),
            Err(error) => self
                .diagnostics
                .event("IMPORT", format!("Import failed: {error}")),
        }
        result
    }

    pub fn record_debug_event(&self, component: String, message: String) {
        self.diagnostics.event(&component, message);
    }

    pub fn diagnostic_status(&self) -> Result<DiagnosticStatus, CoreError> {
        let (available, size_bytes, last_write_error) = self
            .diagnostics
            .status()
            .map_err(|error| CoreError::Storage(error.to_string()))?;
        Ok(DiagnosticStatus {
            available,
            size_bytes,
            last_write_error,
        })
    }

    pub fn export_diagnostic_snapshot(
        &self,
        export_directory: String,
    ) -> Result<String, CoreError> {
        let directory = std::path::Path::new(&export_directory);
        let path = self
            .diagnostics
            .snapshot(directory)
            .map_err(|error| CoreError::Storage(format!("Diagnostic export failed: {error}")))?;
        Ok(path.to_string_lossy().into_owned())
    }

    pub fn search_source(&self, query: SearchRequest) -> Result<SearchResponse, CoreError> {
        source::search(self, query)
    }

    pub fn load_source_profile(&self, listing_id: i64) -> Result<ProfileResponse, CoreError> {
        source::load_profile(self, listing_id)
    }

    pub fn archive_profile_media(
        &self,
        snapshot_id: i64,
        include_videos: bool,
    ) -> Result<MediaArchiveReport, CoreError> {
        media::archive(self, snapshot_id, include_videos)
    }

    pub fn list_identity_candidates(
        &self,
        listing_id: i64,
    ) -> Result<Vec<IdentityCandidate>, CoreError> {
        identity::list(self, listing_id)
    }

    pub fn confirm_identity_match(
        &self,
        listing_id: i64,
        target_person_id: i64,
    ) -> Result<(), CoreError> {
        identity::confirm(self, listing_id, target_person_id)
    }

    pub fn list_change_events(
        &self,
        person_id: i64,
        limit: u32,
    ) -> Result<Vec<ChangeRecord>, CoreError> {
        history::list(self, person_id, limit)
    }

    pub fn status(&self) -> Result<CoreStatus, CoreError> {
        let db = self.connection()?;
        let count = |table: &str| -> Result<u32, CoreError> {
            let sql = match table {
                "persons" => "SELECT COUNT(*) FROM persons",
                "listings" => "SELECT COUNT(*) FROM listings",
                "source_profiles" => "SELECT COUNT(*) FROM source_profiles",
                _ => return Err(CoreError::InvalidInput("Unknown status table".into())),
            };
            Ok(db.query_row(sql, [], |row| row.get(0))?)
        };
        Ok(CoreStatus {
            api_version: 1,
            schema_version: schema::VERSION,
            person_count: count("persons")?,
            listing_count: count("listings")?,
            source_profile_count: count("source_profiles")?,
        })
    }

    /// Allocate a person without assuming a relationship to other people.
    pub fn create_person(&self, display_name: Option<String>) -> Result<i64, CoreError> {
        let name = display_name
            .map(|n| n.trim().to_owned())
            .filter(|n| !n.is_empty());
        let mut db = self.connection()?;
        let tx = db.transaction()?;
        tx.execute(
            "INSERT INTO persons (display_name) VALUES (?1)",
            params![name],
        )?;
        let id = tx.last_insert_rowid();
        // An SQLite trigger initializes person_user_state for all insertion paths.
        tx.commit()?;
        Ok(id)
    }

    /// External profile IDs are evidence, not permission to merge different persons.
    pub fn add_source_profile(
        &self,
        person_id: i64,
        source: String,
        external_profile_id: String,
    ) -> Result<i64, CoreError> {
        let source = non_empty(source, "source")?;
        let external_profile_id = non_empty(external_profile_id, "external_profile_id")?;
        let mut db = self.connection()?;
        let tx = db.transaction()?;
        ensure_person(&tx, person_id)?;
        let existing: Option<(i64, i64)> = tx
            .query_row(
                "SELECT source_profile_id, person_id FROM source_profiles
                 WHERE source = ?1 AND external_profile_id = ?2",
                params![source, external_profile_id],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .optional()?;
        let id = if let Some((existing_id, owner)) = existing {
            if owner != person_id {
                return Err(CoreError::IdentityConflict(format!(
                    "Profile {source}/{external_profile_id} belongs to person {owner}"
                )));
            }
            tx.execute(
                "UPDATE source_profiles SET last_seen_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
                 WHERE source_profile_id = ?1",
                params![existing_id],
            )?;
            existing_id
        } else {
            tx.execute(
                "INSERT INTO source_profiles (person_id, source, external_profile_id)
                 VALUES (?1, ?2, ?3)",
                params![person_id, source, external_profile_id],
            )?;
            tx.last_insert_rowid()
        };
        touch_person(&tx, person_id)?;
        tx.commit()?;
        Ok(id)
    }

    /// The (source, external_listing_id) pair is stable and cannot silently change owners.
    pub fn add_listing(
        &self,
        person_id: i64,
        source_profile_id: Option<i64>,
        source: String,
        external_listing_id: String,
        current_url: Option<String>,
    ) -> Result<i64, CoreError> {
        let source = non_empty(source, "source")?;
        let external_listing_id = non_empty(external_listing_id, "external_listing_id")?;
        let mut db = self.connection()?;
        let tx = db.transaction()?;
        ensure_person(&tx, person_id)?;
        if let Some(profile_id) = source_profile_id {
            let owner: Option<(i64, String)> = tx
                .query_row(
                    "SELECT person_id, source FROM source_profiles WHERE source_profile_id = ?1",
                    params![profile_id],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .optional()?;
            match owner {
                None => return Err(CoreError::SourceProfileNotFound(profile_id)),
                Some((owner_id, profile_source))
                    if owner_id != person_id || profile_source != source =>
                {
                    return Err(CoreError::IdentityConflict(
                        "A listing must use a source profile of the same person and source".into(),
                    ));
                }
                _ => {}
            }
        }
        let existing: Option<(i64, i64, Option<i64>)> = tx
            .query_row(
                "SELECT listing_id, person_id, source_profile_id
                 FROM listings WHERE source = ?1 AND external_listing_id = ?2",
                params![source, external_listing_id],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .optional()?;
        let id = if let Some((existing_id, owner, existing_profile)) = existing {
            if owner != person_id
                || existing_profile
                    .is_some_and(|p| source_profile_id.is_some_and(|new_profile| p != new_profile))
            {
                return Err(CoreError::IdentityConflict(format!(
                    "Listing {source}/{external_listing_id} is already assigned"
                )));
            }
            tx.execute(
                "UPDATE listings
                 SET last_seen_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'),
                     current_url = COALESCE(?1, current_url),
                     source_profile_id = COALESCE(source_profile_id, ?2)
                 WHERE listing_id = ?3",
                params![current_url, source_profile_id, existing_id],
            )?;
            existing_id
        } else {
            tx.execute(
                "INSERT INTO listings
                 (person_id, source_profile_id, source, external_listing_id, current_url)
                 VALUES (?1, ?2, ?3, ?4, ?5)",
                params![
                    person_id,
                    source_profile_id,
                    source,
                    external_listing_id,
                    current_url
                ],
            )?;
            tx.last_insert_rowid()
        };
        touch_person(&tx, person_id)?;
        tx.execute(
            "UPDATE source_profiles
             SET last_seen_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
             WHERE source_profile_id =
               (SELECT source_profile_id FROM listings WHERE listing_id = ?1)",
            params![id],
        )?;
        tx.commit()?;
        Ok(id)
    }

    pub fn get_person(&self, person_id: i64) -> Result<Option<PersonSummary>, CoreError> {
        let db = self.connection()?;
        let raw = db
            .query_row(PERSON_QUERY_BY_ID, params![person_id], parse_person_row)
            .optional()?;
        raw.map(PersonSummary::try_from).transpose()
    }

    pub fn list_people(
        &self,
        limit: u32,
        cursor: Option<PersonCursor>,
    ) -> Result<PersonPage, CoreError> {
        if limit == 0 || limit > 100 {
            return Err(CoreError::InvalidInput(
                "Page size must be between 1 and 100".into(),
            ));
        }
        if cursor
            .as_ref()
            .is_some_and(|value| value.person_id <= 0 || value.last_seen_at.is_empty())
        {
            return Err(CoreError::InvalidInput("Invalid person cursor".into()));
        }
        let db = self.connection()?;
        let mut query = db.prepare(PERSON_QUERY_LIST)?;
        let rows = query.query_map(
            params![
                cursor.as_ref().map(|value| value.last_seen_at.as_str()),
                cursor.as_ref().map(|value| value.person_id),
                i64::from(limit) + 1,
            ],
            parse_person_row,
        )?;
        let mut people = rows
            .map(|row| PersonSummary::try_from(row?))
            .collect::<Result<Vec<_>, _>>()?;
        let has_more = people.len() > limit as usize;
        people.truncate(limit as usize);
        let next_cursor = if has_more {
            people.last().map(|person| PersonCursor {
                last_seen_at: person.last_seen_at.clone(),
                person_id: person.person_id,
            })
        } else {
            None
        };
        Ok(PersonPage {
            people,
            next_cursor,
        })
    }

    pub fn set_preference(&self, person_id: i64, preference: Preference) -> Result<(), CoreError> {
        let db = self.connection()?;
        let changed = db.execute(
            "UPDATE person_user_state
             SET preference = ?1, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
             WHERE person_id = ?2",
            params![preference.as_db(), person_id],
        )?;
        if changed == 0 {
            return Err(CoreError::PersonNotFound(person_id));
        }
        Ok(())
    }

    pub fn set_favorite(&self, person_id: i64, favorite: bool) -> Result<(), CoreError> {
        let db = self.connection()?;
        let changed = db.execute(
            "UPDATE person_user_state
             SET favorite = ?1, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
             WHERE person_id = ?2",
            params![favorite, person_id],
        )?;
        if changed == 0 {
            return Err(CoreError::PersonNotFound(person_id));
        }
        Ok(())
    }
}

const PERSON_QUERY_BY_ID: &str = r#"
SELECT p.person_id, p.display_name, s.preference, s.favorite,
       (SELECT COUNT(*) FROM listings l WHERE l.person_id = p.person_id),
       p.last_seen_at
FROM persons p
JOIN person_user_state s ON s.person_id = p.person_id
WHERE p.person_id = ?1
"#;

const PERSON_QUERY_LIST: &str = r#"
SELECT p.person_id, p.display_name, s.preference, s.favorite,
       (SELECT COUNT(*) FROM listings l WHERE l.person_id = p.person_id),
       p.last_seen_at
FROM persons p
JOIN person_user_state s ON s.person_id = p.person_id
WHERE (?1 IS NULL OR p.last_seen_at < ?1 OR (p.last_seen_at = ?1 AND p.person_id < ?2))
ORDER BY p.last_seen_at DESC, p.person_id DESC
LIMIT ?3
"#;

fn parse_person_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<RawPerson> {
    Ok((
        row.get(0)?,
        row.get(1)?,
        row.get(2)?,
        row.get(3)?,
        row.get(4)?,
        row.get(5)?,
    ))
}

impl TryFrom<RawPerson> for PersonSummary {
    type Error = CoreError;

    fn try_from(row: RawPerson) -> Result<Self, Self::Error> {
        Ok(Self {
            person_id: row.0,
            display_name: row.1,
            preference: Preference::from_db(&row.2)?,
            favorite: row.3 != 0,
            listing_count: row.4,
            last_seen_at: row.5,
        })
    }
}

fn non_empty(value: String, field: &'static str) -> Result<String, CoreError> {
    let cleaned = value.trim().to_owned();
    if cleaned.is_empty() {
        return Err(CoreError::InvalidInput(format!(
            "{field} must not be empty"
        )));
    }
    Ok(cleaned)
}

fn touch_person(db: &Connection, person_id: i64) -> Result<(), CoreError> {
    db.execute(
        "UPDATE persons
         SET last_seen_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'),
             updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
         WHERE person_id = ?1",
        params![person_id],
    )?;
    Ok(())
}

fn ensure_person(db: &Connection, person_id: i64) -> Result<(), CoreError> {
    let exists: bool = db.query_row(
        "SELECT EXISTS(SELECT 1 FROM persons WHERE person_id = ?1)",
        params![person_id],
        |row| row.get(0),
    )?;
    if exists {
        Ok(())
    } else {
        Err(CoreError::PersonNotFound(person_id))
    }
}

impl LadiesCore {
    fn connection(&self) -> Result<MutexGuard<'_, Connection>, CoreError> {
        self.db
            .lock()
            .map_err(|e| CoreError::Storage(e.to_string()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn memory_core() -> Arc<LadiesCore> {
        LadiesCore::new(":memory:".into()).unwrap()
    }

    #[test]
    fn person_profile_listing_and_preference_roundtrip() {
        let core = memory_core();
        let person_id = core.create_person(Some(" Example ".into())).unwrap();
        let profile_id = core
            .add_source_profile(person_id, " LADIES ".into(), "abc".into())
            .unwrap();
        let listing_id = core
            .add_listing(
                person_id,
                Some(profile_id),
                "LADIES".into(),
                "42".into(),
                None,
            )
            .unwrap();
        assert!(listing_id > 0);
        assert_eq!(
            profile_id,
            core.add_source_profile(person_id, "LADIES".into(), "abc".into())
                .unwrap()
        );
        assert_eq!(
            listing_id,
            core.add_listing(
                person_id,
                Some(profile_id),
                "LADIES".into(),
                "42".into(),
                None
            )
            .unwrap(),
        );
        core.set_preference(person_id, Preference::Like).unwrap();
        core.set_favorite(person_id, true).unwrap();
        let person = core.get_person(person_id).unwrap().unwrap();
        assert_eq!(person.display_name.as_deref(), Some("Example"));
        assert_eq!(person.preference, Preference::Like);
        assert!(person.favorite);
        assert_eq!(person.listing_count, 1);
        assert_eq!(core.list_people(10, None).unwrap().people.len(), 1);
        assert_eq!(core.status().unwrap().source_profile_count, 1);
        assert_eq!(core.status().unwrap().listing_count, 1);
    }

    #[test]
    fn similar_names_and_distinct_listings_never_automatically_merge() {
        let core = memory_core();
        let first = core.create_person(Some("Same".into())).unwrap();
        let second = core.create_person(Some("Same".into())).unwrap();
        core.add_listing(first, None, "LADIES".into(), "100".into(), None)
            .unwrap();
        core.add_listing(second, None, "LADIES".into(), "101".into(), None)
            .unwrap();
        assert_eq!(core.status().unwrap().person_count, 2);
        assert_ne!(first, second);
    }

    #[test]
    fn conflicting_external_ids_cannot_reassign_person() {
        let core = memory_core();
        let first = core.create_person(None).unwrap();
        let second = core.create_person(None).unwrap();
        let profile = core
            .add_source_profile(first, "LADIES".into(), "profile".into())
            .unwrap();
        assert!(matches!(
            core.add_source_profile(second, "LADIES".into(), "profile".into()),
            Err(CoreError::IdentityConflict(_))
        ));
        core.add_listing(first, Some(profile), "LADIES".into(), "ad".into(), None)
            .unwrap();
        assert!(matches!(
            core.add_listing(second, None, "LADIES".into(), "ad".into(), None),
            Err(CoreError::IdentityConflict(_))
        ));
        assert!(matches!(
            core.add_listing(
                second,
                Some(profile),
                "LADIES".into(),
                "another".into(),
                None
            ),
            Err(CoreError::IdentityConflict(_))
        ));
        assert!(matches!(
            core.add_listing(
                first,
                Some(profile),
                "OTHER".into(),
                "different".into(),
                None
            ),
            Err(CoreError::IdentityConflict(_))
        ));
    }

    #[test]
    fn invalid_references_and_empty_ids_return_errors() {
        let core = memory_core();
        assert!(matches!(
            core.add_source_profile(999, "LADIES".into(), "a".into()),
            Err(CoreError::PersonNotFound(999))
        ));
        assert!(matches!(
            core.add_listing(999, None, "LADIES".into(), "a".into(), None),
            Err(CoreError::PersonNotFound(999))
        ));
        assert!(matches!(
            core.add_source_profile(1, "".into(), "a".into()),
            Err(CoreError::InvalidInput(_))
        ));
        assert!(matches!(
            core.set_favorite(999, true),
            Err(CoreError::PersonNotFound(999))
        ));
        assert!(core.get_person(999).unwrap().is_none());
    }

    #[test]
    fn persistence_survives_reopening_database() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp
            .path()
            .join("ladies.sqlite")
            .to_string_lossy()
            .to_string();
        let id;
        {
            let core = LadiesCore::new(path.clone()).unwrap();
            id = core.create_person(Some("Persisted".into())).unwrap();
            core.set_favorite(id, true).unwrap();
        }
        let again = LadiesCore::new(path).unwrap();
        assert_eq!(again.status().unwrap().schema_version, schema::VERSION);
        assert_eq!(again.get_person(id).unwrap().unwrap().favorite, true);
    }

    #[test]
    fn keyset_pagination_returns_more_than_one_hundred_people_without_duplicates() {
        let core = memory_core();
        {
            let mut db = core.connection().unwrap();
            let tx = db.transaction().unwrap();
            for number in 0..235 {
                tx.execute(
                    "INSERT INTO persons (display_name, last_seen_at)
                     VALUES (?1, '2026-01-01T00:00:00.000Z')",
                    params![format!("Person {number}")],
                )
                .unwrap();
            }
            tx.commit().unwrap();
        }
        let mut cursor = None;
        let mut ids = Vec::new();
        loop {
            let page = core.list_people(70, cursor).unwrap();
            assert!(!page.people.is_empty());
            assert!(page.people.len() <= 70);
            ids.extend(page.people.into_iter().map(|person| person.person_id));
            match page.next_cursor {
                Some(next) => cursor = Some(next),
                None => break,
            }
        }
        assert_eq!(ids.len(), 235);
        assert!(ids.windows(2).all(|pair| pair[0] > pair[1]));
        assert_eq!(ids.first().copied(), Some(235));
        assert_eq!(ids.last().copied(), Some(1));
    }

    #[test]
    fn pagination_rejects_invalid_page_size_and_cursor() {
        let core = memory_core();
        assert!(matches!(
            core.list_people(0, None),
            Err(CoreError::InvalidInput(_))
        ));
        assert!(matches!(
            core.list_people(101, None),
            Err(CoreError::InvalidInput(_))
        ));
        assert!(matches!(
            core.list_people(
                25,
                Some(PersonCursor {
                    last_seen_at: "2026".into(),
                    person_id: 0
                })
            ),
            Err(CoreError::InvalidInput(_))
        ));
        assert!(core.list_people(25, None).unwrap().people.is_empty());
    }

    #[test]
    fn source_profiles_and_listings_update_the_person_activity_timestamp() {
        let core = memory_core();
        let id = core.create_person(Some("Active".into())).unwrap();
        const OLD: &str = "2000-01-01T00:00:00.000Z";
        {
            let db = core.connection().unwrap();
            db.execute(
                "UPDATE persons SET last_seen_at=?1 WHERE person_id=?2",
                params![OLD, id],
            )
            .unwrap();
        }
        let profile_id = core
            .add_source_profile(id, "LADIES".into(), "profile".into())
            .unwrap();
        assert!(core.get_person(id).unwrap().unwrap().last_seen_at.as_str() > OLD);

        {
            let db = core.connection().unwrap();
            db.execute(
                "UPDATE persons SET last_seen_at=?1 WHERE person_id=?2",
                params![OLD, id],
            )
            .unwrap();
            db.execute(
                "UPDATE source_profiles SET last_seen_at=?1 WHERE source_profile_id=?2",
                params![OLD, profile_id],
            )
            .unwrap();
        }
        core.add_listing(id, Some(profile_id), "LADIES".into(), "ad".into(), None)
            .unwrap();
        assert!(core.get_person(id).unwrap().unwrap().last_seen_at.as_str() > OLD);
        {
            let db = core.connection().unwrap();
            let profile_seen: String = db
                .query_row(
                    "SELECT last_seen_at FROM source_profiles WHERE source_profile_id=?1",
                    params![profile_id],
                    |r| r.get(0),
                )
                .unwrap();
            assert!(profile_seen.as_str() > OLD);
            db.execute(
                "UPDATE persons SET last_seen_at=?1 WHERE person_id=?2",
                params![OLD, id],
            )
            .unwrap();
        }
        core.add_listing(id, Some(profile_id), "LADIES".into(), "ad".into(), None)
            .unwrap();
        assert!(core.get_person(id).unwrap().unwrap().last_seen_at.as_str() > OLD);
    }
}
