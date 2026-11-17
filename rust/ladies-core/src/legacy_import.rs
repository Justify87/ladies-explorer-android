//! Transactional import from a consistent legacy SQLite backup and archived media.
//! The original database and user files are opened read-only and never altered.

use std::collections::{BTreeMap, HashSet};
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Component, Path};
use std::sync::Arc;

use rusqlite::{Connection, OpenFlags, Transaction, params_from_iter, types::Value};
use sha2::{Digest, Sha256};
use zip::ZipArchive;

use crate::{CoreError, ImportReport, LadiesCore};

const BUNDLE_MARKER: &str = "ladies-explorer-legacy-v1";
const TABLES: &[(&str, Option<&str>)] = &[
    ("persons", None),
    ("person_user_state", None),
    ("source_profiles", None),
    ("listings", None),
    ("person_identifiers", None),
    ("listing_snapshots", None),
    ("snapshot_values", None),
    ("media_assets", None),
    ("media_sources", None),
    ("snapshot_media", None),
    ("search_runs", None),
    ("search_results", None),
    ("listing_observations", None),
    ("saved_searches", None),
    ("saved_search_results", None),
    ("change_events", None),
    ("identity_evidence", None),
    ("identity_events", None),
    ("encounters", None),
    ("encounter_services", None),
    ("price_lists", Some("source = 'USER_ENTERED'")),
    (
        "price_items",
        Some(
            "price_list_id IN (SELECT price_list_id FROM price_lists WHERE source = 'USER_ENTERED')",
        ),
    ),
];

fn error(message: impl Into<String>) -> CoreError {
    CoreError::Import(message.into())
}

fn zip_error(context: &str, cause: impl std::fmt::Display) -> CoreError {
    error(format!("{context}: {cause}"))
}

fn identifier(value: &str) -> String {
    format!("\"{}\"", value.replace('"', "\"\""))
}

fn sqlite_columns(db: &Connection, table: &str) -> Result<Vec<String>, CoreError> {
    let mut q = db.prepare(&format!("PRAGMA table_info({})", identifier(table)))?;
    let names: Vec<String> = q
        .query_map([], |row| row.get(1))?
        .collect::<rusqlite::Result<_>>()?;
    if names.is_empty() {
        return Err(error(format!("Required table missing: {table}")));
    }
    Ok(names)
}

fn ignored_legacy_column(table: &str, column: &str) -> bool {
    table == "listing_snapshots" && matches!(column, "whatsapp_url" | "sms_url" | "tel_url")
}

fn copy_table(
    source: &Connection,
    target: &Transaction<'_>,
    table: &str,
    filter: Option<&str>,
) -> Result<u64, CoreError> {
    let source_columns = sqlite_columns(source, table)?;
    let target_columns = sqlite_columns(target, table)?;
    let target_set: HashSet<&str> = target_columns.iter().map(String::as_str).collect();
    let missing = source_columns
        .iter()
        .filter(|col| !target_set.contains(col.as_str()) && !ignored_legacy_column(table, col))
        .collect::<Vec<_>>();
    if !missing.is_empty() {
        return Err(error(format!(
            "Unmapped columns in {table}: {missing:?}; import aborted to prevent data loss"
        )));
    }
    let source_set: HashSet<&str> = source_columns.iter().map(String::as_str).collect();
    let columns: Vec<String> = target_columns
        .into_iter()
        .filter(|column| source_set.contains(column.as_str()))
        .collect();
    if columns.is_empty() {
        return Err(error(format!("No compatible columns in {table}")));
    }
    let names = columns
        .iter()
        .map(|col| identifier(col))
        .collect::<Vec<_>>()
        .join(", ");
    let selection = format!(
        "SELECT {names} FROM {}{}",
        identifier(table),
        filter
            .map(|clause| format!(" WHERE {clause}"))
            .unwrap_or_default()
    );
    let bind_marks = (1..=columns.len())
        .map(|n| format!("?{n}"))
        .collect::<Vec<_>>()
        .join(", ");
    let mut insertion = format!(
        "INSERT INTO {} ({names}) VALUES ({bind_marks})",
        identifier(table)
    );
    if table == "person_user_state" {
        insertion.push_str(" ON CONFLICT(person_id) DO UPDATE SET ");
        insertion.push_str(
            &columns
                .iter()
                .filter(|col| col.as_str() != "person_id")
                .map(|col| format!("{}=excluded.{}", identifier(col), identifier(col)))
                .collect::<Vec<_>>()
                .join(", "),
        );
    }

    let mut read = source.prepare(&selection)?;
    let mut rows = read.query([])?;
    let mut write = target.prepare(&insertion)?;
    let mut count = 0;
    while let Some(row) = rows.next()? {
        let values = (0..columns.len())
            .map(|index| row.get::<_, Value>(index))
            .collect::<rusqlite::Result<Vec<_>>>()?;
        write.execute(params_from_iter(values.iter()))?;
        count += 1;
    }
    Ok(count)
}

fn verified_relative_path(raw: &str) -> Result<&Path, CoreError> {
    let path = Path::new(raw);
    let mut parts = path.components();
    let safe_root = match parts.next() {
        Some(Component::Normal(first)) => first == "media" || first == "raw_profiles",
        _ => false,
    };
    if !safe_root
        || !parts.clone().next().is_some()
        || path
            .components()
            .any(|part| !matches!(part, Component::Normal(_)))
        || raw.contains('\\')
    {
        return Err(error(format!("Invalid archived path: {raw}")));
    }
    Ok(path)
}

fn required_files(source: &Connection) -> Result<BTreeMap<String, Option<String>>, CoreError> {
    let mut paths = BTreeMap::new();
    let mut query = source.prepare("SELECT local_path, sha256 FROM media_assets")?;
    let media = query.query_map([], |row| {
        Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
    })?;
    for item in media {
        let (path, digest) = item?;
        verified_relative_path(&path)?;
        paths.insert(path, Some(digest));
    }
    let mut query = source.prepare(
        "SELECT DISTINCT raw_html_path FROM listing_snapshots WHERE raw_html_path IS NOT NULL",
    )?;
    let raw = query.query_map([], |row| row.get::<_, String>(0))?;
    for item in raw {
        let path = item?;
        verified_relative_path(&path)?;
        paths.entry(path).or_insert(None);
    }
    Ok(paths)
}

fn unpack_media(
    archive: &mut ZipArchive<File>,
    source: &Connection,
    staged_assets: &Path,
) -> Result<u32, CoreError> {
    let referenced = required_files(source)?;
    let mut imported = 0;
    for (path, expected_hash) in referenced {
        let archive_name = format!("assets/{path}");
        let mut entry = archive
            .by_name(&archive_name)
            .map_err(|e| zip_error(&format!("Missing {archive_name}"), e))?;
        if entry.is_dir() {
            return Err(error(format!(
                "{archive_name} is a directory, not an asset"
            )));
        }
        let destination = staged_assets.join(verified_relative_path(&path)?);
        fs::create_dir_all(
            destination
                .parent()
                .ok_or_else(|| error("Missing parent directory"))?,
        )?;
        let mut file = File::create(&destination)?;
        let mut hash = Sha256::new();
        let mut buffer = [0u8; 64 * 1024];
        loop {
            let size = entry.read(&mut buffer)?;
            if size == 0 {
                break;
            }
            file.write_all(&buffer[..size])?;
            if expected_hash.is_some() {
                hash.update(&buffer[..size]);
            }
        }
        file.flush()?;
        if let Some(expected) = expected_hash {
            let actual = format!("{:x}", hash.finalize());
            if !actual.eq_ignore_ascii_case(&expected) {
                return Err(error(format!("Media SHA-256 mismatch: {path}")));
            }
        }
        imported += 1;
    }
    Ok(imported)
}

fn validate_legacy(source: &Connection) -> Result<(), CoreError> {
    let version: i64 = source.pragma_query_value(None, "user_version", |r| r.get(0))?;
    let application_id: i64 = source.pragma_query_value(None, "application_id", |r| r.get(0))?;
    if version != 1 || application_id != 0 {
        return Err(error(
            "Expected original web-app SQLite v1 database, not the Android DB",
        ));
    }
    let integrity: String = source.query_row("PRAGMA integrity_check", [], |r| r.get(0))?;
    if integrity != "ok" {
        return Err(error(format!(
            "Legacy SQLite integrity check failed: {integrity}"
        )));
    }
    for (table, _) in TABLES {
        sqlite_columns(source, table)?;
    }
    Ok(())
}

fn target_has_data(db: &Connection) -> Result<bool, CoreError> {
    for table in TABLES.iter().map(|(table, _)| *table).chain(std::iter::once("app_settings")) {
        let count: i64 = db.query_row(
            &format!("SELECT COUNT(*) FROM {}", identifier(table)),
            [],
            |row| row.get(0),
        )?;
        if count != 0 {
            return Ok(true);
        }
    }
    Ok(false)
}

pub(crate) fn import_bundle(
    core: &LadiesCore,
    bundle_path: &str,
    app_files: &str,
) -> Result<ImportReport, CoreError> {
    let files_dir = Path::new(app_files);
    if !files_dir.is_dir() {
        return Err(error(
            "Android application storage directory does not exist",
        ));
    }
    let final_assets = files_dir.join("ladies_data");
    if final_assets.exists() {
        return Err(error(
            "An existing media archive was detected; refusing to overwrite it",
        ));
    }

    let mut archive = ZipArchive::new(File::open(bundle_path)?)
        .map_err(|e| zip_error("Cannot open ZIP bundle", e))?;
    let mut marker = String::new();
    archive
        .by_name("manifest.txt")
        .map_err(|e| zip_error("Missing bundle manifest", e))?
        .read_to_string(&mut marker)?;
    if marker.trim() != BUNDLE_MARKER {
        return Err(error("Unknown migration bundle format"));
    }

    let staging = tempfile::tempdir_in(files_dir)?;
    let source_file = staging.path().join("legacy.sqlite3");
    {
        let mut input = archive
            .by_name("legacy.sqlite3")
            .map_err(|e| zip_error("Missing legacy SQLite backup", e))?;
        let mut output = File::create(&source_file)?;
        std::io::copy(&mut input, &mut output)?;
        output.sync_all()?;
    }
    let source = Connection::open_with_flags(
        &source_file,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )?;
    validate_legacy(&source)?;

    let staged_assets = staging.path().join("ladies_data");
    fs::create_dir(&staged_assets)?;
    let media_file_count = unpack_media(&mut archive, &source, &staged_assets)?;
    let mut target = core.connection()?;
    if target_has_data(&target)? {
        return Err(error(
            "Target database is not empty; import is only allowed once",
        ));
    }
    let tx = target.transaction()?;
    tx.execute_batch("PRAGMA defer_foreign_keys=ON;")?;
    let mut counts = BTreeMap::new();
    for (table, filter) in TABLES {
        counts.insert(*table, copy_table(&source, &tx, table, *filter)?);
    }
    let invalid: bool = tx.prepare("PRAGMA foreign_key_check")?.exists([])?;
    if invalid {
        return Err(error("Imported database contains broken foreign keys"));
    }
    // File promotion happens while the SQL transaction is still uncommitted.
    fs::rename(&staged_assets, &final_assets)?;
    if let Err(err) = tx.commit() {
        let _ = fs::remove_dir_all(&final_assets);
        return Err(CoreError::from(err));
    }
    Ok(ImportReport {
        persons: count(&counts, "persons"),
        listings: count(&counts, "listings"),
        source_profiles: count(&counts, "source_profiles"),
        saved_searches: count(&counts, "saved_searches"),
        manual_price_lists: count(&counts, "price_lists"),
        media_files: media_file_count,
    })
}

fn count(counts: &BTreeMap<&str, u64>, key: &str) -> u32 {
    counts.get(key).copied().unwrap_or(0).min(u32::MAX as u64) as u32
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use zip::write::SimpleFileOptions;

    fn fixture(root: &Path, missing_media: bool, invalid_owner: bool) -> (std::path::PathBuf, std::path::PathBuf) {
        let source_file = root.join("source.sqlite3");
        let mut src = Connection::open(&source_file).unwrap();
        crate::schema::migrate(&mut src).unwrap();
        src.pragma_update(None, "application_id", 0).unwrap();
        let hash = format!("{:x}", Sha256::digest(b"sample image"));
        let local = format!("media/images/aa/{hash}.jpg");
        src.execute(
            "INSERT INTO persons (person_id, display_name) VALUES (1,'Alice')",
            [],
        )
        .unwrap();
        src.execute(
            "INSERT INTO source_profiles (source_profile_id, person_id, source, external_profile_id)
             VALUES (11, 1, 'LADIES', 'p-1')", []
        ).unwrap();
        src.execute(
            "INSERT INTO listings (listing_id, person_id, source_profile_id, source,
             external_listing_id) VALUES (21,1,11,'LADIES','ad-1')",
            [],
        )
        .unwrap();
        src.execute(
            "INSERT INTO person_user_state (person_id, preference, favorite)
             VALUES (1,'LIKE',1)
             ON CONFLICT(person_id) DO UPDATE SET preference='LIKE', favorite=1",
            [],
        )
        .unwrap();
        src.execute(
            "INSERT INTO media_assets (media_asset_id, sha256, kind, local_path)
             VALUES (31,?1,'IMAGE',?2)",
            rusqlite::params![hash, local],
        )
        .unwrap();
        src.execute(
            "INSERT INTO saved_searches (saved_search_id,name,filters_json)
             VALUES ('uuid-leipzig','Leipzig','{}')",
            [],
        )
        .unwrap();
        src.execute(
            "INSERT INTO saved_search_results (saved_search_id,result_key,snapshot_json)
             VALUES ('uuid-leipzig','ad-1','{}')",
            [],
        )
        .unwrap();
        src.execute(
            "INSERT INTO price_lists (price_list_id,person_id,source) VALUES (41,1,'USER_ENTERED')",
            [],
        )
        .unwrap();
        src.execute(
            "INSERT INTO price_items (price_item_id,price_list_id,amount_cents)
             VALUES (51,41,10000)",
            [],
        )
        .unwrap();
        src.execute(
            "INSERT INTO price_lists (price_list_id,person_id,source) VALUES (42,1,'AUTOMATED')",
            [],
        )
        .unwrap();
        src.execute(
            "INSERT INTO price_items (price_item_id,price_list_id,amount_cents)
             VALUES (52,42,9999)",
            [],
        )
        .unwrap();
        src.execute_batch("ALTER TABLE listing_snapshots ADD COLUMN whatsapp_url TEXT;")
            .unwrap();
        if invalid_owner {
            src.execute_batch(
                "PRAGMA foreign_keys=OFF;
                 UPDATE listings SET person_id=999, source_profile_id=NULL WHERE listing_id=21;"
            ).unwrap();
        }
        drop(src);
        let bundle = root.join("export.zip");
        let mut writer = zip::ZipWriter::new(File::create(&bundle).unwrap());
        writer
            .start_file("manifest.txt", SimpleFileOptions::default())
            .unwrap();
        writer.write_all(b"ladies-explorer-legacy-v1\n").unwrap();
        writer
            .start_file("legacy.sqlite3", SimpleFileOptions::default())
            .unwrap();
        writer.write_all(&fs::read(&source_file).unwrap()).unwrap();
        if !missing_media {
            writer
                .start_file(format!("assets/{local}"), SimpleFileOptions::default())
                .unwrap();
            writer.write_all(b"sample image").unwrap();
        }
        writer.finish().unwrap();
        let app = root.join("android-files");
        fs::create_dir(&app).unwrap();
        (bundle, app)
    }

    #[test]
    fn imports_profiles_preferences_saved_searches_and_manual_prices() {
        let tmp = tempfile::tempdir().unwrap();
        let (bundle, app) = fixture(tmp.path(), false, false);
        let db = app.join("ladies.sqlite").to_string_lossy().into_owned();
        let core = LadiesCore::new(db).unwrap();
        let report = import_bundle(&core, bundle.to_str().unwrap(), app.to_str().unwrap()).unwrap();
        assert_eq!(report.persons, 1);
        assert_eq!(report.listings, 1);
        assert_eq!(report.saved_searches, 1);
        assert_eq!(report.manual_price_lists, 1);
        assert_eq!(report.media_files, 1);
        let person = core.get_person(1).unwrap().unwrap();
        assert_eq!(person.preference, crate::Preference::Like);
        assert!(person.favorite);
        let db = core.connection().unwrap();
        let manual: i64 = db
            .query_row("SELECT COUNT(*) FROM price_lists", [], |r| r.get(0))
            .unwrap();
        assert_eq!(manual, 1);
        let saved: String = db
            .query_row("SELECT saved_search_id FROM saved_searches", [], |r| {
                r.get(0)
            })
            .unwrap();
        assert_eq!(saved, "uuid-leipzig");
        assert!(app.join("ladies_data/media").is_dir());
        assert!(matches!(
            import_bundle(&core, bundle.to_str().unwrap(), app.to_str().unwrap()),
            Err(CoreError::Import(_))
        ));
    }

    #[test]
    fn inconsistent_source_foreign_keys_roll_back_after_partial_copy() {
        let tmp = tempfile::tempdir().unwrap();
        let (bundle, app) = fixture(tmp.path(), false, true);
        let core = LadiesCore::new(
            app.join("ladies.sqlite").to_string_lossy().into_owned()
        ).unwrap();
        assert!(matches!(
            import_bundle(&core, bundle.to_str().unwrap(), app.to_str().unwrap()),
            Err(CoreError::Import(_))
        ));
        assert_eq!(core.status().unwrap().person_count, 0);
        assert!(!app.join("ladies_data").exists());
    }

    #[test]
    fn missing_media_rolls_back_without_partial_database_changes() {
        let tmp = tempfile::tempdir().unwrap();
        let (bundle, app) = fixture(tmp.path(), true, false);
        let core =
            LadiesCore::new(app.join("ladies.sqlite").to_string_lossy().into_owned()).unwrap();
        assert!(matches!(
            import_bundle(&core, bundle.to_str().unwrap(), app.to_str().unwrap()),
            Err(CoreError::Import(_))
        ));
        assert_eq!(core.status().unwrap().person_count, 0);
        assert!(!app.join("ladies_data").exists());
    }
}
