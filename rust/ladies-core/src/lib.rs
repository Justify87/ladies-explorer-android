//! Native application core. Domain features will be added in migration milestones.
//! The Android UI owns system integration; Rust owns persistence and business logic.

use std::sync::{Arc, Mutex};

use rusqlite::{Connection, OptionalExtension, params};
use thiserror::Error;

uniffi::setup_scaffolding!();

#[derive(Debug, Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CoreError {
    #[error("Storage operation failed: {0}")]
    Storage(String),
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct CoreStatus {
    pub api_version: u32,
    pub stored_notes: u32,
}

#[derive(uniffi::Object)]
pub struct LadiesCore {
    db: Mutex<Connection>,
}

#[uniffi::export]
impl LadiesCore {
    #[uniffi::constructor]
    pub fn new(database_path: String) -> Result<Arc<Self>, CoreError> {
        let connection =
            Connection::open(database_path).map_err(|e| CoreError::Storage(e.to_string()))?;
        connection
            .execute_batch(
                "PRAGMA foreign_keys=ON;
                 CREATE TABLE IF NOT EXISTS scaffold_notes (
                   note_key TEXT PRIMARY KEY NOT NULL,
                   note_value TEXT NOT NULL
                 );",
            )
            .map_err(|e| CoreError::Storage(e.to_string()))?;
        Ok(Arc::new(Self {
            db: Mutex::new(connection),
        }))
    }

    pub fn status(&self) -> Result<CoreStatus, CoreError> {
        let db = self.connection()?;
        let count: u32 = db
            .query_row("SELECT COUNT(*) FROM scaffold_notes", [], |row| row.get(0))
            .map_err(|e| CoreError::Storage(e.to_string()))?;
        Ok(CoreStatus {
            api_version: 1,
            stored_notes: count,
        })
    }

    pub fn save_note(&self, note_key: String, note_value: String) -> Result<(), CoreError> {
        let db = self.connection()?;
        db.execute(
            "INSERT INTO scaffold_notes (note_key, note_value) VALUES (?1, ?2)
             ON CONFLICT(note_key) DO UPDATE SET note_value=excluded.note_value",
            params![note_key, note_value],
        )
        .map_err(|e| CoreError::Storage(e.to_string()))?;
        Ok(())
    }

    pub fn load_note(&self, note_key: String) -> Result<Option<String>, CoreError> {
        let db = self.connection()?;
        db.query_row(
            "SELECT note_value FROM scaffold_notes WHERE note_key=?1",
            params![note_key],
            |row| row.get(0),
        )
        .optional()
        .map_err(|e| CoreError::Storage(e.to_string()))
    }
}

impl LadiesCore {
    fn connection(&self) -> Result<std::sync::MutexGuard<'_, Connection>, CoreError> {
        self.db
            .lock()
            .map_err(|e| CoreError::Storage(e.to_string()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sqlite_roundtrip_is_repeatable() {
        let core = LadiesCore::new(":memory:".to_owned()).expect("in-memory database");
        assert_eq!(core.status().expect("status").stored_notes, 0);
        assert_eq!(core.load_note("sample".into()).expect("read"), None);
        core.save_note("sample".into(), "first".into())
            .expect("write");
        core.save_note("sample".into(), "updated".into())
            .expect("replace");
        assert_eq!(
            core.load_note("sample".into()).expect("read").as_deref(),
            Some("updated")
        );
        assert_eq!(core.status().expect("status").stored_notes, 1);
    }

    #[test]
    fn invalid_database_path_reports_error() {
        let result = LadiesCore::new("/definitely/not/a/real/directory/ladies.sqlite".into());
        assert!(result.is_err());
    }
}
