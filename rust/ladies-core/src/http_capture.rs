//! App-private, bounded HTTP observations and a single HAR 1.2 export.
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use regex::Regex;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tempfile::Builder;
use time::format_description::well_known::Rfc3339;
use url::Url;

pub(crate) const MAX_BODY_BYTES: usize = 2 * 1024 * 1024;
pub(crate) const MAX_SESSION_BYTES: u64 = 25 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CaptureMode {
    Off,
    Metadata,
    Text,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct CaptureStatus {
    pub mode: CaptureMode,
    pub entries: u32,
    pub bytes: u64,
    pub stopped: bool,
    pub last_error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct HttpHeader {
    pub name: String,
    pub value: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct HttpObservation {
    pub method: String,
    pub url: String,
    pub final_url: String,
    pub status: Option<u16>,
    pub http_version: String,
    pub request_headers: Vec<HttpHeader>,
    pub response_headers: Vec<HttpHeader>,
    pub mime: Option<String>,
    pub response_size: Option<u64>,
    pub started_ms: u64,
    pub headers_elapsed_ms: u64,
    pub body_elapsed_ms: u64,
    pub body: Option<String>,
    pub is_media: bool,
    pub error: Option<String>,
    pub body_omitted: Option<String>,
}

#[derive(Debug)]
struct State {
    mode: CaptureMode,
    entries: u32,
    bytes: u64,
    stopped: bool,
    last_error: Option<String>,
}

pub(crate) struct HttpRecorder {
    path: Option<PathBuf>,
    state: Mutex<State>,
}

pub(crate) fn started_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or_default()
}

pub(crate) fn headers(map: &reqwest::header::HeaderMap) -> Vec<HttpHeader> {
    map.iter()
        .map(|(name, value)| HttpHeader {
            name: name.to_string(),
            value: value.to_str().unwrap_or("<non-text>").to_owned(),
        })
        .collect()
}

fn is_secret(name: &str) -> bool {
    let key = name.to_ascii_lowercase().replace('_', "-");
    [
        "cookie",
        "authorization",
        "auth",
        "token",
        "secret",
        "api-key",
        "apikey",
        "password",
        "session",
        "jwt",
        "credential",
        "signature",
        "key",
        "access-key",
        "client-secret",
    ]
    .iter()
    .any(|needle| key.contains(needle))
}

fn redacted_headers(headers: &mut [HttpHeader]) {
    for header in headers {
        if is_secret(&header.name) {
            header.value = "[REDACTED]".to_owned();
        } else if header.name.eq_ignore_ascii_case("location")
            || header.name.eq_ignore_ascii_case("referer")
            || header.name.eq_ignore_ascii_case("referrer")
        {
            header.value = redacted_reference(&header.value);
        } else {
            header.value = redacted_text(&header.value);
        }
    }
}

fn redacted_url(input: &str) -> String {
    let Ok(mut url) = Url::parse(input) else {
        return input.to_owned();
    };
    if !url.username().is_empty() {
        let _ = url.set_username("[REDACTED]");
    }
    if url.password().is_some() {
        let _ = url.set_password(Some("[REDACTED]"));
    }
    // Fragments are not transmitted by HTTP and may carry application tokens.
    url.set_fragment(None);
    if url.query().is_some() {
        let pairs: Vec<_> = url
            .query_pairs()
            .map(|(k, v)| {
                let value = if is_secret(&k) {
                    "[REDACTED]".to_owned()
                } else {
                    v.into_owned()
                };
                (k.into_owned(), value)
            })
            .collect();
        url.query_pairs_mut().clear().extend_pairs(pairs);
    }
    url.to_string()
}

fn redacted_reference(input: &str) -> String {
    if input.starts_with('/') || input.starts_with('?') {
        let base = Url::parse("https://har-redaction.invalid/").expect("static URL");
        if let Ok(joined) = base.join(input) {
            let sanitized = redacted_url(joined.as_str());
            if let Some(relative) = sanitized.strip_prefix("https://har-redaction.invalid") {
                return relative.to_owned();
            }
        }
    }
    redacted_text(input)
}

fn redacted_text(input: &str) -> String {
    static URLS: OnceLock<Regex> = OnceLock::new();
    static TOKENS: OnceLock<Regex> = OnceLock::new();
    let urls = URLS.get_or_init(|| Regex::new(r#"(?i)https?://[^\s<>"']+"#).expect("static regex"));
    let with_urls = urls.replace_all(input, |caps: &regex::Captures<'_>| {
        let matched = caps.get(0).expect("match").as_str();
        let trimmed = matched.trim_end_matches(['.', ',', ';', ')', ']']);
        format!("{}{}", redacted_url(trimmed), &matched[trimmed.len()..])
    });
    let tokens = TOKENS.get_or_init(|| {
        Regex::new(r"(?i)\b(token|auth|authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|session|signature)=([^\s&]+)").expect("static regex")
    });
    tokens.replace_all(&with_urls, "$1=[REDACTED]").into_owned()
}

fn is_text(mime: Option<&str>) -> bool {
    let mime = mime.unwrap_or("").to_ascii_lowercase();
    // Rust source responses have already passed UTF-8 decoding; missing MIME is still text.
    mime.is_empty()
        || mime == "not specified"
        || mime.starts_with("text/")
        || mime.contains("json")
        || mime.contains("xml")
        || mime.contains("javascript")
        || mime.contains("x-www-form-urlencoded")
}

impl HttpRecorder {
    pub(crate) fn new(database_path: &str) -> Self {
        let path = if database_path == ":memory:" {
            None
        } else {
            Path::new(database_path)
                .parent()
                .map(|p| p.join("diagnostics/ladies-http.jsonl"))
        };
        let (bytes, entries, last_error, invalid_file) = path
            .as_ref()
            .map_or((0, 0, None, false), |p| recover_session(p));
        Self {
            path,
            state: Mutex::new(State {
                mode: CaptureMode::Off,
                entries,
                bytes,
                stopped: invalid_file || bytes >= MAX_SESSION_BYTES,
                last_error,
            }),
        }
    }

    pub(crate) fn set_mode(&self, mode: CaptureMode) {
        if let Ok(mut state) = self.state.lock() {
            // Modes are never persisted; restart always reverts to Off.
            state.mode = mode;
        }
    }

    pub(crate) fn mode(&self) -> CaptureMode {
        self.state
            .lock()
            .map(|s| s.mode)
            .unwrap_or(CaptureMode::Off)
    }

    pub(crate) fn status(&self) -> CaptureStatus {
        match self.state.lock() {
            Ok(s) => CaptureStatus {
                mode: s.mode,
                entries: s.entries,
                bytes: s.bytes,
                stopped: s.stopped,
                last_error: s.last_error.clone(),
            },
            Err(_) => CaptureStatus {
                mode: CaptureMode::Off,
                entries: 0,
                bytes: 0,
                stopped: true,
                last_error: Some("HTTP recorder lock poisoned".into()),
            },
        }
    }

    /// Recorder errors never alter a source request or an image load.
    pub(crate) fn record(&self, mut event: HttpObservation) {
        let Ok(mut state) = self.state.lock() else {
            return;
        };
        if state.mode == CaptureMode::Off || state.stopped {
            return;
        };
        let Some(path) = self.path.as_ref() else {
            return;
        };
        event.url = redacted_url(&event.url);
        event.final_url = redacted_url(&event.final_url);
        redacted_headers(&mut event.request_headers);
        redacted_headers(&mut event.response_headers);
        event.error = event.error.as_deref().map(redacted_text);
        if state.mode != CaptureMode::Text {
            event.body = None;
            event.body_omitted = Some("metadata_mode".into());
        } else if event.is_media {
            event.body = None;
            event.body_omitted = Some("media_body_disabled".into());
        } else if !is_text(event.mime.as_deref()) {
            event.body = None;
            event.body_omitted = Some("non_text_mime".into());
        } else if event
            .body
            .as_ref()
            .is_some_and(|b| b.len() > MAX_BODY_BYTES)
        {
            event.body = None;
            event.body_omitted = Some("body_over_2_mib".into());
        }
        let mut line = match serde_json::to_vec(&event) {
            Ok(serialized) => serialized,
            Err(error) => {
                state.last_error = Some(error.to_string());
                return;
            }
        };
        if state.bytes + line.len() as u64 + 1 > MAX_SESSION_BYTES && event.body.is_some() {
            event.body = None;
            event.body_omitted = Some("session_limit_body_omitted".into());
            line = match serde_json::to_vec(&event) {
                Ok(serialized) => serialized,
                Err(error) => {
                    state.last_error = Some(error.to_string());
                    return;
                }
            };
        }
        if state.bytes + line.len() as u64 + 1 > MAX_SESSION_BYTES {
            state.stopped = true;
            state.last_error = Some("HTTP session reached 25 MiB limit".into());
            return;
        }
        line.push(b'\n');
        let write_result = (|| -> io::Result<()> {
            if let Some(parent) = path.parent() {
                fs::create_dir_all(parent)?;
            }
            let mut file = OpenOptions::new().create(true).append(true).open(path)?;
            let original_len = file.metadata()?.len();
            if original_len != state.bytes {
                return Err(io::Error::other("HTTP session size changed unexpectedly"));
            }
            if let Err(error) = file.write_all(&line).and_then(|_| file.flush()) {
                file.set_len(original_len)?;
                return Err(error);
            }
            Ok(())
        })();
        match write_result {
            Ok(()) => {
                state.bytes += line.len() as u64;
                state.entries += 1;
            }
            Err(error) => {
                state.last_error = Some(error.to_string());
                state.stopped = true;
            },
        }
    }

    pub(crate) fn clear(&self) -> io::Result<()> {
        let mut state = self
            .state
            .lock()
            .map_err(|_| io::Error::other("Recorder lock poisoned"))?;
        if let Some(path) = &self.path {
            match fs::remove_file(path) {
                Ok(()) => {}
                Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                Err(error) => {
                    state.last_error = Some(error.to_string());
                    return Err(error);
                }
            }
        }
        state.mode = CaptureMode::Off;
        state.bytes = 0;
        state.entries = 0;
        state.stopped = false;
        state.last_error = None;
        Ok(())
    }

    pub(crate) fn export(&self, directory: &Path) -> io::Result<PathBuf> {
        let _state = self
            .state
            .lock()
            .map_err(|_| io::Error::other("Recorder lock poisoned"))?;
        let path = self
            .path
            .as_ref()
            .ok_or_else(|| io::Error::other("Recorder requires file storage"))?;
        let file = File::open(path)?;
        let entries = BufReader::new(file)
            .lines()
            .map(|line| {
                let item: HttpObservation = serde_json::from_str(&line?)
                    .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
                Ok(har_entry(&item))
            })
            .collect::<io::Result<Vec<_>>>()?;
        fs::create_dir_all(directory)?;
        let mut tmp = Builder::new()
            .prefix("ladies-http-")
            .suffix(".har")
            .tempfile_in(directory)?;
        serde_json::to_writer_pretty(
            &mut tmp,
            &json!({
                "log": {
                    "version": "1.2",
                    "creator": {"name": "Ladies Explorer Android", "version": "0.1.0"},
                    "entries": entries
                }
            }),
        )?;
        tmp.write_all(b"\n")?;
        tmp.as_file_mut().sync_all()?;
        let (_, path) = tmp.keep().map_err(|error| error.error)?;
        Ok(path)
    }
}

fn recover_session(path: &Path) -> (u64, u32, Option<String>, bool) {
    let data = match fs::read(path) {
        Ok(data) => data,
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            return (0, 0, None, false);
        }
        Err(error) => return (0, 0, Some(error.to_string()), true),
    };
    let mut valid_bytes = 0u64;
    let mut entries = 0u32;
    for record in data.split_inclusive(|byte| *byte == b'\n') {
        if !record.ends_with(b"\n")
            || serde_json::from_slice::<HttpObservation>(record).is_err()
        {
            break;
        }
        valid_bytes += record.len() as u64;
        entries += 1;
    }
    if valid_bytes == data.len() as u64 {
        return (valid_bytes, entries, None, false);
    }
    let recovery = OpenOptions::new()
        .write(true)
        .open(path)
        .and_then(|file| file.set_len(valid_bytes));
    match recovery {
        Ok(()) => (
            valid_bytes,
            entries,
            Some("An incomplete HTTP journal tail was discarded on startup".into()),
            false,
        ),
        Err(error) => (valid_bytes, entries, Some(format!("HTTP journal recovery failed: {error}")), true),
    }
}

fn har_entry(event: &HttpObservation) -> Value {
    let timestamp =
        time::OffsetDateTime::from_unix_timestamp_nanos(event.started_ms as i128 * 1_000_000)
            .ok()
            .and_then(|dt| dt.format(&Rfc3339).ok())
            .unwrap_or_else(|| "1970-01-01T00:00:00Z".into());
    let query = Url::parse(&event.url)
        .ok()
        .map(|url| {
            url.query_pairs()
                .map(|(name, value)| json!({"name": name, "value": value}))
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    let to_headers = |values: &[HttpHeader]| -> Vec<Value> {
        values
            .iter()
            .map(|h| json!({"name": h.name, "value": h.value}))
            .collect()
    };
    let mut content = json!({
        "size": event.response_size.map(|bytes| bytes as i64).unwrap_or(-1),
        "mimeType": event.mime.as_deref().unwrap_or("application/octet-stream"),
    });
    if let Some(body) = &event.body {
        content["text"] = json!(body);
    }
    let total = event
        .headers_elapsed_ms
        .saturating_add(event.body_elapsed_ms);
    // The pre-header interval is measured, but includes connection and redirects.
    // It is NOT a separately measured TTFB.
    json!({
        "startedDateTime": timestamp,
        "time": total,
        "request": {
            "method": event.method,
            "url": event.url,
            "httpVersion": event.http_version,
            "cookies": [],
            "headers": to_headers(&event.request_headers),
            "queryString": query,
            "headersSize": -1,
            "bodySize": 0
        },
        "response": {
            "status": event.status.unwrap_or(0),
            "statusText": "",
            "httpVersion": event.http_version,
            "cookies": [],
            "headers": to_headers(&event.response_headers),
            "content": content,
            "redirectURL": event.response_headers.iter()
                .find(|h| h.name.eq_ignore_ascii_case("location")).map(|h| h.value.as_str()).unwrap_or(""),
            "headersSize": -1,
            "bodySize": event.response_size.map(|n| n as i64).unwrap_or(-1),
        },
        "cache": {},
        "timings": {
            "blocked": -1,
            "dns": -1,
            "connect": -1,
            // HAR requires nonnegative send/wait/receive. The observed pre-header
            // aggregate is mapped to wait; it is NOT an isolated server TTFB.
            "send": 0,
            "wait": event.headers_elapsed_ms,
            "receive": event.body_elapsed_ms
        },
        "_diagnostics": {
            "final_url": event.final_url,
            "error": event.error,
            "body_omitted": event.body_omitted,
            "headers_elapsed_ms": event.headers_elapsed_ms,
            "timing_note": "HAR requires send/wait/receive to be nonnegative. The measured request-to-headers aggregate is represented in wait, but includes unmeasured connection phases; it is not a measured server TTFB. receive is time spent reading the response body.",
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn sample() -> HttpObservation {
        HttpObservation {
            method: "GET".into(),
            url: "https://www.ladies.de/api/list?token=abc".into(),
            final_url: "https://www.ladies.de/api/list?token=abc".into(),
            status: Some(200),
            http_version: "HTTP/2.0".into(),
            request_headers: vec![HttpHeader {
                name: "Authorization".into(),
                value: "Bearer secret".into(),
            }],
            response_headers: vec![HttpHeader {
                name: "Set-Cookie".into(),
                value: "session=test".into(),
            }],
            mime: Some("application/json".into()),
            response_size: Some(7),
            started_ms: started_ms(),
            headers_elapsed_ms: 25,
            body_elapsed_ms: 5,
            body: Some("{\"a\":1}".into()),
            is_media: false,
            error: None,
            body_omitted: None,
        }
    }

    #[test]
    fn default_off_and_restart_off_but_session_persists() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("db.sqlite").to_string_lossy().into_owned();
        let recorder = HttpRecorder::new(&path);
        recorder.record(sample());
        assert_eq!(recorder.status().entries, 0);
        recorder.set_mode(CaptureMode::Text);
        recorder.record(sample());
        let next = HttpRecorder::new(&path);
        assert_eq!(next.status().mode, CaptureMode::Off);
        assert_eq!(next.status().entries, 1);
    }

    #[test]
    fn export_redacts_secrets_and_preserves_full_json() {
        let root = tempfile::tempdir().unwrap();
        let recorder = HttpRecorder::new(&root.path().join("db.sqlite").to_string_lossy());
        recorder.set_mode(CaptureMode::Text);
        recorder.record(sample());
        let file = recorder.export(root.path()).unwrap();
        let har: Value = serde_json::from_slice(&fs::read(file).unwrap()).unwrap();
        let entry = &har["log"]["entries"][0];
        assert_eq!(har["log"]["version"], "1.2");
        assert_eq!(entry["response"]["content"]["text"], "{\"a\":1}");
        assert_eq!(entry["time"], 30);
        assert_eq!(entry["request"]["headers"][0]["value"], "[REDACTED]");
        assert_eq!(entry["response"]["headers"][0]["value"], "[REDACTED]");
        assert!(!entry["request"]["url"].as_str().unwrap().contains("abc"));
    }

    #[test]
    fn mode_and_body_limits_do_not_truncate_html() {
        let root = tempfile::tempdir().unwrap();
        let recorder = HttpRecorder::new(&root.path().join("db.sqlite").to_string_lossy());
        recorder.set_mode(CaptureMode::Metadata);
        recorder.record(sample());
        recorder.set_mode(CaptureMode::Text);
        let mut permitted = sample();
        permitted.mime = Some("text/html".into());
        permitted.body = Some("x".repeat(MAX_BODY_BYTES));
        recorder.record(permitted);
        let mut over = sample();
        over.body = Some("x".repeat(MAX_BODY_BYTES + 1));
        recorder.record(over);
        let mut media = sample();
        media.is_media = true;
        recorder.record(media);
        let har: Value =
            serde_json::from_slice(&fs::read(recorder.export(root.path()).unwrap()).unwrap())
                .unwrap();
        let entries = har["log"]["entries"].as_array().unwrap();
        assert!(entries[0]["response"]["content"].get("text").is_none());
        assert_eq!(
            entries[1]["response"]["content"]["text"]
                .as_str()
                .unwrap()
                .len(),
            MAX_BODY_BYTES
        );
        assert_eq!(
            entries[2]["_diagnostics"]["body_omitted"],
            "body_over_2_mib"
        );
        assert!(entries[3]["response"]["content"].get("text").is_none());
    }

    #[test]
    fn delete_resets_and_export_is_consistent() {
        let root = tempfile::tempdir().unwrap();
        let recorder = HttpRecorder::new(&root.path().join("db.sqlite").to_string_lossy());
        recorder.set_mode(CaptureMode::Text);
        recorder.record(sample());
        recorder.clear().unwrap();
        assert_eq!(recorder.status().entries, 0);
        assert_eq!(recorder.status().mode, CaptureMode::Off);
        assert!(recorder.export(root.path()).is_err());
    }
    #[test]
    fn parallel_exports_keep_valid_har_snapshots() {
        use std::sync::Arc;
        use std::thread;
        let root = tempfile::tempdir().unwrap();
        let recorder = Arc::new(HttpRecorder::new(
            &root.path().join("ladies.sqlite").to_string_lossy(),
        ));
        recorder.set_mode(CaptureMode::Metadata);
        let writer = Arc::clone(&recorder);
        let job = thread::spawn(move || {
            for _ in 0..200 {
                writer.record(sample());
            }
        });
        for _ in 0..10 {
            if recorder.status().entries > 0 {
                let har: Value = serde_json::from_slice(
                    &fs::read(recorder.export(root.path()).unwrap()).unwrap(),
                )
                .unwrap();
                assert!(har["log"]["entries"].as_array().is_some());
            }
        }
        job.join().unwrap();
        assert_eq!(recorder.status().entries, 200);
        assert_eq!(recorder.status().mode, CaptureMode::Metadata);
    }

    #[test]
    fn write_failure_is_visible_and_does_not_panic() {
        let root = tempfile::tempdir().unwrap();
        fs::write(root.path().join("diagnostics"), "file not folder").unwrap();
        let recorder = HttpRecorder::new(&root.path().join("ladies.sqlite").to_string_lossy());
        recorder.set_mode(CaptureMode::Text);
        recorder.record(sample());
        let status = recorder.status();
        assert_eq!(status.entries, 0);
        assert!(status.last_error.is_some());
    }

    #[test]
    fn session_limit_stops_and_keeps_existing_export_valid() {
        let root = tempfile::tempdir().unwrap();
        let recorder = HttpRecorder::new(&root.path().join("ladies.sqlite").to_string_lossy());
        recorder.set_mode(CaptureMode::Text);
        recorder.record(sample());
        {
            let mut state = recorder.state.lock().unwrap();
            // Near the cap, the next entry cannot fit even after body omission.
            state.bytes = MAX_SESSION_BYTES - 1;
        }
        recorder.record(sample());
        let status = recorder.status();
        assert!(status.stopped);
        assert!(
            status
                .last_error
                .as_deref()
                .unwrap_or("")
                .contains("25 MiB")
        );
        assert_eq!(status.entries, 1);
        let har: Value =
            serde_json::from_slice(&fs::read(recorder.export(root.path()).unwrap()).unwrap())
                .unwrap();
        assert_eq!(har["log"]["entries"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn redirect_and_error_are_recorded_with_no_fabricated_server_phases() {
        let root = tempfile::tempdir().unwrap();
        let recorder = HttpRecorder::new(&root.path().join("ladies.sqlite").to_string_lossy());
        recorder.set_mode(CaptureMode::Metadata);
        let mut redirected = sample();
        redirected.final_url = "https://www.ladies.de/profile".into();
        redirected.response_headers.push(HttpHeader {
            name: "Location".into(),
            value: "/profile".into(),
        });
        recorder.record(redirected);
        let mut error = sample();
        error.status = None;
        error.error = Some("timeout".into());
        recorder.record(error);
        let har: Value =
            serde_json::from_slice(&fs::read(recorder.export(root.path()).unwrap()).unwrap())
                .unwrap();
        let entries = har["log"]["entries"].as_array().unwrap();
        assert_eq!(
            entries[0]["_diagnostics"]["final_url"],
            "https://www.ladies.de/profile"
        );
        assert_eq!(entries[0]["response"]["redirectURL"], "/profile");
        assert_eq!(entries[1]["response"]["status"], 0);
        assert_eq!(entries[1]["_diagnostics"]["error"], "timeout");
        assert_eq!(entries[1]["timings"]["dns"], -1);
        assert_eq!(entries[1]["timings"]["connect"], -1);
    }
    #[test]
    fn har_timings_are_nonnegative_consistent_and_unknown_size_is_not_zero() {
        let root = tempfile::tempdir().unwrap();
        let recorder = HttpRecorder::new(&root.path().join("db.sqlite").to_string_lossy());
        recorder.set_mode(CaptureMode::Metadata);
        let mut event = sample();
        event.response_size = None;
        event.headers_elapsed_ms = 73;
        event.body_elapsed_ms = 12;
        recorder.record(event);
        let har: Value =
            serde_json::from_slice(&fs::read(recorder.export(root.path()).unwrap()).unwrap())
                .unwrap();
        let entry = &har["log"]["entries"][0];
        assert_eq!(entry["response"]["content"]["size"], -1);
        assert_eq!(entry["time"], 85);
        assert_eq!(entry["timings"]["send"], 0);
        assert_eq!(entry["timings"]["wait"], 73);
        assert_eq!(entry["timings"]["receive"], 12);
        assert_eq!(
            entry["time"].as_u64().unwrap(),
            entry["timings"]["send"].as_u64().unwrap()
                + entry["timings"]["wait"].as_u64().unwrap()
                + entry["timings"]["receive"].as_u64().unwrap()
        );
    }

    #[test]
    fn masks_url_header_redirect_credentials_and_embedded_error_tokens() {
        let root = tempfile::tempdir().unwrap();
        let recorder = HttpRecorder::new(&root.path().join("db.sqlite").to_string_lossy());
        recorder.set_mode(CaptureMode::Metadata);
        let mut event = sample();
        event.url = "https://alice:pwd@www.ladies.de/path?api_key=request-key&keep=yes#token".into();
        event.request_headers.push(HttpHeader {
            name: "Referer".into(),
            value: "https://www.ladies.de/start?access_token=ref-secret".into(),
        });
        event.response_headers.push(HttpHeader {
            name: "Location".into(),
            value: "/login?refresh_token=redirect-secret".into(),
        });
        event.error = Some(
            "request to https://www.ladies.de/check?token=error-secret failed; password=plain-secret".into(),
        );
        recorder.record(event);
        let har = fs::read_to_string(recorder.export(root.path()).unwrap()).unwrap();
        for secret in [
            "request-key", "ref-secret", "redirect-secret", "error-secret",
            "plain-secret", "alice", "pwd", "#token",
        ] {
            assert!(!har.contains(secret), "leaked: {secret}");
        }
        assert!(har.contains("keep=yes"));
        assert!(har.contains("REDACTED"));
    }

    #[test]
    fn recover_incomplete_jsonl_tail_and_preserve_valid_entries() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("db.sqlite").to_string_lossy().into_owned();
        let recorder = HttpRecorder::new(&path);
        recorder.set_mode(CaptureMode::Metadata);
        recorder.record(sample());
        let journal = root.path().join("diagnostics/ladies-http.jsonl");
        let mut file = OpenOptions::new().append(true).open(&journal).unwrap();
        file.write_all(br#"{"partial":"#).unwrap();
        drop(file);

        let reopened = HttpRecorder::new(&path);
        assert_eq!(reopened.status().entries, 1);
        assert!(reopened.status().last_error.is_some());
        assert!(!reopened.status().stopped);
        reopened.set_mode(CaptureMode::Metadata);
        reopened.record(sample());
        assert_eq!(reopened.status().entries, 2);
        let data: Value =
            serde_json::from_slice(&fs::read(reopened.export(root.path()).unwrap()).unwrap())
                .unwrap();
        assert_eq!(data["log"]["entries"].as_array().unwrap().len(), 2);
    }

    #[test]
    fn corrupt_existing_lines_cannot_silently_disappear_during_export() {
        let root = tempfile::tempdir().unwrap();
        let recorder = HttpRecorder::new(&root.path().join("db.sqlite").to_string_lossy());
        recorder.set_mode(CaptureMode::Metadata);
        recorder.record(sample());
        let journal = root.path().join("diagnostics/ladies-http.jsonl");
        OpenOptions::new().append(true).open(journal).unwrap()
            .write_all(b"invalid json\n").unwrap();
        assert_eq!(
            recorder.export(root.path()).unwrap_err().kind(),
            io::ErrorKind::InvalidData,
        );
    }

}
