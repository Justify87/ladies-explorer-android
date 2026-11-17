//! Per-core diagnostic journal with serialized writes and immutable export snapshots.
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use tempfile::Builder;

const MAX_BYTES: usize = 1_500_000;
const KEEP_BYTES: usize = 700_000;
const MAX_HTML_CHARS: usize = 24_000;

#[derive(Default)]
struct JournalState {
    last_write_error: Option<String>,
}

pub(crate) struct Diagnostics {
    path: Option<PathBuf>,
    state: Mutex<JournalState>,
}

impl Diagnostics {
    pub(crate) fn new(database_path: &str) -> Self {
        let path = if database_path == ":memory:" {
            None
        } else {
            Path::new(database_path)
                .parent()
                .map(|directory| directory.join("diagnostics/ladies-debug.log"))
        };
        let journal = Self {
            path,
            state: Mutex::new(JournalState::default()),
        };
        journal.event("CORE", "App diagnostic session opened (v2)");
        journal
    }

    fn append(path: &Path, component: &str, message: &str) -> io::Result<()> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        if fs::metadata(path).is_ok_and(|meta| meta.len() as usize > MAX_BYTES) {
            let bytes = fs::read(path)?;
            let first = bytes.len().saturating_sub(KEEP_BYTES);
            let offset = bytes[first..]
                .iter()
                .position(|byte| *byte == b'\n')
                .map(|index| first + index + 1)
                .unwrap_or(bytes.len());
            let mut retained = b"[old diagnostic events truncated]\n".to_vec();
            retained.extend_from_slice(&bytes[offset..]);
            fs::write(path, retained)?;
        }
        let timestamp_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|duration| duration.as_millis())
            .unwrap_or_default();
        let component: String = component
            .chars()
            .filter(|ch| ch.is_ascii_alphanumeric() || *ch == '_')
            .take(32)
            .collect();
        let escaped = message.replace('\0', "\\0");
        let mut file = OpenOptions::new().create(true).append(true).open(path)?;
        writeln!(file, "[{timestamp_ms}] [{component}] {escaped}")?;
        Ok(())
    }

    pub(crate) fn event(&self, component: &str, message: impl AsRef<str>) {
        let Some(path) = self.path.as_ref() else {
            return;
        };
        let Ok(mut state) = self.state.lock() else {
            eprintln!("Ladies Explorer diagnostics: journal lock poisoned");
            return;
        };
        if let Err(error) = Self::append(path, component, message.as_ref()) {
            state.last_write_error = Some(error.to_string());
            eprintln!("Ladies Explorer diagnostics: {error}");
        }
    }

    pub(crate) fn html(&self, stage: &str, body: &str) {
        let excerpt: String = body.chars().take(MAX_HTML_CHARS).collect();
        self.event(
            "HTML",
            format!(
                "BEGIN {stage} body_bytes={} excerpt_limit={MAX_HTML_CHARS}\n{excerpt}\nEND {stage}",
                body.len()
            ),
        );
    }

    pub(crate) fn status(&self) -> io::Result<(bool, u64, Option<String>)> {
        let state = self
            .state
            .lock()
            .map_err(|_| io::Error::other("Diagnostic journal lock poisoned"))?;
        let (available, size) = match self.path.as_ref() {
            Some(path) => match fs::metadata(path) {
                Ok(metadata) => (metadata.is_file(), metadata.len()),
                Err(error) if error.kind() == io::ErrorKind::NotFound => (false, 0),
                Err(error) => {
                    return Ok((false, 0, Some(error.to_string())));
                }
            },
            None => (false, 0),
        };
        Ok((available, size, state.last_write_error.clone()))
    }

    /// Copy while holding the same lock as appends/rotation; consumers never see a live log.
    pub(crate) fn snapshot(&self, directory: &Path) -> io::Result<PathBuf> {
        let _state = self
            .state
            .lock()
            .map_err(|_| io::Error::other("Diagnostic journal lock poisoned"))?;
        let source = self.path.as_ref().ok_or_else(|| {
            io::Error::new(io::ErrorKind::NotFound, "Diagnostic journal is disabled")
        })?;
        let mut input = File::open(source)?;
        fs::create_dir_all(directory)?;
        let mut temporary = Builder::new()
            .prefix("ladies-debug-")
            .suffix(".txt")
            .tempfile_in(directory)?;
        io::copy(&mut input, &mut temporary)?;
        temporary.as_file_mut().sync_all()?;
        let (_, destination) = temporary.keep().map_err(|error| error.error)?;
        Ok(destination)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::thread;

    fn logger(directory: &Path) -> Diagnostics {
        Diagnostics::new(directory.join("ladies.sqlite").to_str().unwrap())
    }

    #[test]
    fn journal_rotates_and_retains_recent_events() {
        let directory = tempfile::tempdir().unwrap();
        let log = logger(directory.path());
        log.event("HTTP", "<div class='ladies-card'>unknown</div>");
        for n in 0..2_800 {
            log.event("HTTP", format!("event={n} {}", "x".repeat(650)));
        }
        let content =
            fs::read_to_string(directory.path().join("diagnostics/ladies-debug.log")).unwrap();
        assert!(content.len() < MAX_BYTES + 2048);
        assert!(content.contains("event=2799"));
    }

    #[test]
    fn separate_core_journals_do_not_redirect_each_other() {
        let first = tempfile::tempdir().unwrap();
        let second = tempfile::tempdir().unwrap();
        let a = logger(first.path());
        let b = logger(second.path());
        a.event("CORE", "first-only");
        b.event("CORE", "second-only");
        let content_a =
            fs::read_to_string(first.path().join("diagnostics/ladies-debug.log")).unwrap();
        let content_b =
            fs::read_to_string(second.path().join("diagnostics/ladies-debug.log")).unwrap();
        assert!(content_a.contains("first-only"));
        assert!(!content_a.contains("second-only"));
        assert!(content_b.contains("second-only"));
        assert!(!content_b.contains("first-only"));
    }

    #[test]
    fn failed_log_writes_are_reported_in_status() {
        let directory = tempfile::tempdir().unwrap();
        fs::write(directory.path().join("diagnostics"), "not a directory").unwrap();
        let log = logger(directory.path());
        let (available, _, error) = log.status().unwrap();
        assert!(!available);
        assert!(error.is_some());
        assert!(
            log.snapshot(directory.path().join("export").as_path())
                .is_err()
        );
    }

    #[test]
    fn snapshots_are_complete_during_simultaneous_rotation() {
        let directory = tempfile::tempdir().unwrap();
        let log = Arc::new(logger(directory.path()));
        let writer = Arc::clone(&log);
        let handle = thread::spawn(move || {
            for n in 0..3_000 {
                writer.event("WORK", format!("event={n} {}", "x".repeat(600)));
            }
        });
        let export_dir = directory.path().join("snapshots");
        let mut exports = Vec::new();
        for _ in 0..20 {
            exports.push(log.snapshot(&export_dir).unwrap());
        }
        handle.join().unwrap();
        exports.push(log.snapshot(&export_dir).unwrap());
        for export in exports {
            let content = fs::read_to_string(export).unwrap();
            assert!(content.ends_with('\n'));
            assert!(!content.contains("\0"));
            for line in content.lines() {
                assert!(line.starts_with('['));
            }
        }
    }

    #[test]
    fn memory_database_does_not_create_disk_log() {
        let log = Diagnostics::new(":memory:");
        assert!(!log.status().unwrap().0);
    }
}
