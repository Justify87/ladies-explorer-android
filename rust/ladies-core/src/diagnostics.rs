//! App-private diagnostic log. Callers share one bounded file through UniFFI.
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

const MAX_BYTES: usize = 1_500_000;
const KEEP_BYTES: usize = 700_000;
const MAX_HTML_CHARS: usize = 24_000;

static LOG_PATH: OnceLock<Mutex<Option<PathBuf>>> = OnceLock::new();

fn state() -> &'static Mutex<Option<PathBuf>> {
    LOG_PATH.get_or_init(|| Mutex::new(None))
}

pub(crate) fn initialize(db_path: &str) {
    if db_path == ":memory:" {
        return;
    }
    let Some(root) = Path::new(db_path).parent() else {
        return;
    };
    if let Ok(mut log) = state().lock() {
        *log = Some(root.join("diagnostics/ladies-debug.log"));
    }
    event("CORE", "App diagnostic session opened (v1)");
}

fn append(path: &Path, component: &str, message: &str) -> std::io::Result<()> {
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
        .filter(|character| character.is_ascii_alphanumeric() || *character == '_')
        .take(32)
        .collect();
    let escaped = message.replace('\0', "\\0");
    let mut file = OpenOptions::new().create(true).append(true).open(path)?;
    writeln!(file, "[{timestamp_ms}] [{component}] {escaped}")?;
    Ok(())
}

pub(crate) fn event(component: &str, message: impl AsRef<str>) {
    if let Ok(log) = state().lock() {
        if let Some(path) = log.as_ref() {
            let _ = append(path, component, message.as_ref());
        }
    }
}

/// Preserve enough raw markup to diagnose parser drift (including short API fragments).
pub(crate) fn html(stage: &str, body: &str) {
    let excerpt: String = body.chars().take(MAX_HTML_CHARS).collect();
    event(
        "HTML",
        format!(
            "BEGIN {stage} body_bytes={} excerpt_limit={MAX_HTML_CHARS}\n{excerpt}\nEND {stage}",
            body.len()
        ),
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn log_file_contains_raw_excerpt_and_rotates() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("diagnostics/ladies-debug.log");
        append(&path, "HTTP", "request started").unwrap();
        append(&path, "HTML", "<div class='ladies-card'>unknown</div>").unwrap();
        assert!(fs::read_to_string(&path).unwrap().contains("ladies-card"));
        for n in 0..2_800 {
            append(&path, "HTTP", &format!("event={n} {}", "x".repeat(650))).unwrap();
        }
        let text = fs::read_to_string(&path).unwrap();
        assert!(text.len() < MAX_BYTES + 2048);
        assert!(text.contains("event=2799"));
    }
}
