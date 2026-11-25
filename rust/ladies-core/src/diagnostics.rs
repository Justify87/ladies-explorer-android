//! Per-core tracing dispatch and a size-bounded, snapshot-safe file writer.
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

use tempfile::Builder;
use sha2::{Digest, Sha256};
use crate::operation::RequestControl;
use tracing::Dispatch;
use tracing_subscriber::fmt::MakeWriter;

const MAX_BYTES: usize = 1_500_000;
const KEEP_BYTES: usize = 700_000;

#[derive(Default)]
struct JournalState {
    last_write_error: Option<String>,
}

struct Journal {
    path: Option<PathBuf>,
    state: Mutex<JournalState>,
}

#[derive(Clone)]
struct JournalWriter(Arc<Journal>);

struct EventWriter {
    journal: Arc<Journal>,
    buffer: Vec<u8>,
}

impl<'a> MakeWriter<'a> for JournalWriter {
    type Writer = EventWriter;

    fn make_writer(&'a self) -> EventWriter {
        EventWriter {
            journal: Arc::clone(&self.0),
            buffer: Vec::with_capacity(256),
        }
    }
}

impl Write for EventWriter {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        self.buffer.extend_from_slice(data);
        Ok(data.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

impl Drop for EventWriter {
    fn drop(&mut self) {
        let Some(path) = &self.journal.path else {
            return;
        };
        let Ok(mut state) = self.journal.state.lock() else {
            return;
        };
        if let Err(error) = append(path, &self.buffer) {
            state.last_write_error = Some(error.to_string());
            eprintln!("Ladies Explorer diagnostics: {error}");
        }
    }
}

fn append(path: &Path, buffer: &[u8]) -> io::Result<()> {
    if buffer.is_empty() {
        return Ok(());
    }
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let size = fs::metadata(path).map(|m| m.len() as usize).unwrap_or(0);
    if size.saturating_add(buffer.len()) > MAX_BYTES {
        let bytes = fs::read(path).unwrap_or_default();
        let start = bytes.len().saturating_sub(KEEP_BYTES);
        let offset = bytes[start..]
            .iter()
            .position(|byte| *byte == b'\n')
            .map(|index| start + index + 1)
            .unwrap_or(bytes.len());
        let mut retained = b"[old diagnostic events truncated]\n".to_vec();
        retained.extend_from_slice(&bytes[offset..]);
        fs::write(path, retained)?;
    }
    let mut file = OpenOptions::new().create(true).append(true).open(path)?;
    file.write_all(buffer)
}

pub(crate) struct Diagnostics {
    journal: Arc<Journal>,
    dispatch: Dispatch,
    debug: AtomicBool,
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
        let journal = Arc::new(Journal {
            path,
            state: Mutex::new(JournalState::default()),
        });
        let subscriber = tracing_subscriber::fmt()
            .with_writer(JournalWriter(Arc::clone(&journal)))
            .with_ansi(false)
            .with_max_level(tracing::Level::DEBUG)
            .finish();
        let service = Self {
            journal,
            dispatch: Dispatch::new(subscriber),
            debug: AtomicBool::new(false),
        };
        service.event("CORE", "App diagnostic session opened");
        service
    }

    pub(crate) fn set_debug(&self, enabled: bool) {
        self.debug.store(enabled, Ordering::Relaxed);
        self.event(
            "CORE",
            format!(
                "Diagnostic level={}",
                if enabled { "DEBUG" } else { "INFO" }
            ),
        );
    }

    pub(crate) fn debug_enabled(&self) -> bool {
        self.debug.load(Ordering::Relaxed)
    }

    pub(crate) fn event(&self, component: &str, message: impl AsRef<str>) {
        let message = message.as_ref();
        tracing::dispatcher::with_default(&self.dispatch, || {
            tracing::info!(component = %component, message = %message);
        });
    }

    pub(crate) fn debug(&self, component: &str, message: impl AsRef<str>) {
        if !self.debug_enabled() {
            return;
        }
        let message = message.as_ref();
        tracing::dispatcher::with_default(&self.dispatch, || {
            tracing::debug!(component = %component, message = %message);
        });
    }

    pub(crate) fn warn(&self, component: &str, message: impl AsRef<str>) {
        let message = message.as_ref();
        tracing::dispatcher::with_default(&self.dispatch, || {
            tracing::warn!(component = %component, message = %message);
        });
    }

    pub(crate) fn error(&self, component: &str, message: impl AsRef<str>) {
        let message = message.as_ref();
        tracing::dispatcher::with_default(&self.dispatch, || {
            tracing::error!(component = %component, message = %message);
        });
    }

    pub(crate) fn html(&self, stage: &str, body: &str) {
        // Response bodies are never emitted into general-purpose logging.
        self.debug(
            "HTTP",
            format!("body discarded stage={stage} bytes={}", body.len()),
        );
    }

    pub(crate) fn phase(&self, component: &str, phase: &str, elapsed: Instant) {
        if self.debug_enabled() {
            tracing::dispatcher::with_default(&self.dispatch, || {
                tracing::debug!(component = %component, phase = %phase,
                    elapsed_ms = elapsed.elapsed().as_millis() as u64, "phase completed");
            });
        }
    }

    pub(crate) fn timed<T, E: std::fmt::Display>(
        &self,
        component: &str,
        operation: &str,
        work: impl FnOnce() -> Result<T, E>,
    ) -> Result<T, E> {
        self.timed_with_context(component, operation, None, || false, work)
    }

    pub(crate) fn timed_media<T, E: std::fmt::Display>(
        &self,
        operation: &str,
        url: &str,
        control: &RequestControl,
        work: impl FnOnce() -> Result<T, E>,
    ) -> Result<T, E> {
        let digest = format!("{:x}", Sha256::digest(url.as_bytes()));
        self.timed_with_context("MEDIA", operation, Some(&digest[..16]), || control.is_cancelled(), work)
    }

    fn timed_with_context<T, E: std::fmt::Display>(
        &self,
        component: &str,
        operation: &str,
        media: Option<&str>,
        cancelled: impl FnOnce() -> bool,
        work: impl FnOnce() -> Result<T, E>,
    ) -> Result<T, E> {
        tracing::dispatcher::with_default(&self.dispatch, || {
            let span = tracing::info_span!("operation", component = %component, name = %operation, media = tracing::field::Empty);
            if let Some(key) = media {
                span.record("media", key);
            }
            let _entered = span.enter();
            let started = Instant::now();
            let result = work();
            let elapsed_ms = started.elapsed().as_millis() as u64;
            match &result {
                Ok(_) => tracing::info!(
                    component = %component,
                    operation = %operation,
                    elapsed_ms,
                    "operation completed"
                ),
                Err(_) if cancelled() => {
                    if self.debug_enabled() {
                        tracing::debug!(component = %component, operation = %operation, elapsed_ms, "operation cancelled");
                    }
                }
                Err(error) => tracing::error!(
                    component = %component,
                    operation = %operation,
                    elapsed_ms,
                    reason = %error,
                    "operation failed"
                ),
            };
            result
        })
    }

    pub(crate) fn status(&self) -> io::Result<(bool, u64, Option<String>)> {
        let state = self
            .journal
            .state
            .lock()
            .map_err(|_| io::Error::other("Diagnostic journal lock poisoned"))?;
        let (available, size) = match self.journal.path.as_ref() {
            Some(path) => match fs::metadata(path) {
                Ok(meta) => (meta.is_file(), meta.len()),
                Err(error) if error.kind() == io::ErrorKind::NotFound => (false, 0),
                Err(error) => return Ok((false, 0, Some(error.to_string()))),
            },
            None => (false, 0),
        };
        Ok((available, size, state.last_write_error.clone()))
    }

    /// Serialize events, rotations and export snapshots with one mutex.
    pub(crate) fn snapshot(&self, directory: &Path) -> io::Result<PathBuf> {
        let _state = self
            .journal
            .state
            .lock()
            .map_err(|_| io::Error::other("Diagnostic journal lock poisoned"))?;
        let source = self.journal.path.as_ref().ok_or_else(|| {
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
    use std::thread;

    fn logger(directory: &Path) -> Diagnostics {
        Diagnostics::new(directory.join("ladies.sqlite").to_str().unwrap())
    }

    #[test]
    fn journal_rotates_and_retains_recent_events() {
        let directory = tempfile::tempdir().unwrap();
        let log = logger(directory.path());
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
        let a_dir = tempfile::tempdir().unwrap();
        let b_dir = tempfile::tempdir().unwrap();
        let a = logger(a_dir.path());
        let b = logger(b_dir.path());
        a.event("CORE", "first-only");
        b.event("CORE", "second-only");
        let a_text = fs::read_to_string(a_dir.path().join("diagnostics/ladies-debug.log")).unwrap();
        let b_text = fs::read_to_string(b_dir.path().join("diagnostics/ladies-debug.log")).unwrap();
        assert!(a_text.contains("first-only") && !a_text.contains("second-only"));
        assert!(b_text.contains("second-only") && !b_text.contains("first-only"));
    }

    #[test]
    fn failed_log_writes_are_reported_in_status() {
        let directory = tempfile::tempdir().unwrap();
        fs::write(directory.path().join("diagnostics"), "not a directory").unwrap();
        let log = logger(directory.path());
        let (available, _, error) = log.status().unwrap();
        assert!(!available && error.is_some());
        assert!(log.snapshot(&directory.path().join("export")).is_err());
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
            assert!(!content.contains('\0'));
            for line in content.lines() {
                assert!(!line.is_empty());
            }
        }
    }

    #[test]
    fn debug_only_appears_when_enabled() {
        let directory = tempfile::tempdir().unwrap();
        let log = logger(directory.path());
        log.debug("CORE", "hidden");
        log.set_debug(true);
        log.debug("CORE", "shown");
        let data =
            fs::read_to_string(directory.path().join("diagnostics/ladies-debug.log")).unwrap();
        assert!(!data.contains("hidden"));
        assert!(data.contains("shown"));
    }

    #[test]
    fn severity_correlation_and_cancellation_are_preserved() {
        let directory = tempfile::tempdir().unwrap();
        let log = logger(directory.path());
        log.warn("ANDROID", "warn_marker");
        log.error("ANDROID", "error_marker");
        log.set_debug(true);
        let control = RequestControl::default();
        let url = "https://example.invalid/synthetic.jpg";
        let result: Result<(), &str> = log.timed_media("display_image", url, &control, || Err("synthetic failure"));
        assert!(result.is_err());
        control.cancel();
        let result: Result<(), &str> = log.timed_media("display_image", url, &control, || Err("cancel marker"));
        assert!(result.is_err());
        let data = fs::read_to_string(directory.path().join("diagnostics/ladies-debug.log")).unwrap();
        let digest = format!("{:x}", Sha256::digest(url.as_bytes()));
        assert!(data.lines().any(|line| line.contains("WARN") && line.contains("warn_marker")));
        assert!(data.lines().any(|line| line.contains("ERROR") && line.contains("error_marker")));
        assert!(data.lines().any(|line| line.contains(&digest[..16]) && line.contains("synthetic failure")));
        assert!(data.lines().any(|line| line.contains(&digest[..16]) && line.contains("operation cancelled")));
        assert!(!data.contains("cancel marker"));
        assert!(!data.contains(url));
    }

    #[test]
    fn memory_database_does_not_create_disk_log() {
        let log = Diagnostics::new(":memory:");
        assert!(!log.status().unwrap().0);
    }
}
