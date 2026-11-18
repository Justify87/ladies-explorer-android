//! App-private, bounded HTTP observations and a single HAR 1.2 export.
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
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
    SystemTime::now().duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64).unwrap_or_default()
}

pub(crate) fn headers(map: &reqwest::header::HeaderMap) -> Vec<HttpHeader> {
    map.iter().map(|(name, value)| HttpHeader {
        name: name.to_string(),
        value: value.to_str().unwrap_or("<non-text>").to_owned(),
    }).collect()
}

fn is_secret(name: &str) -> bool {
    let key = name.to_ascii_lowercase();
    ["cookie", "authorization", "auth", "token", "secret", "api-key",
        "apikey", "password", "session", "jwt", "credential", "signature"]
        .iter().any(|needle| key.contains(needle))
}

fn redacted_headers(headers: &mut [HttpHeader]) {
    for header in headers {
        if is_secret(&header.name) {
            header.value = "[REDACTED]".to_owned();
        }
    }
}

fn redacted_url(input: &str) -> String {
    let Ok(mut url) = Url::parse(input) else { return input.to_owned() };
    if url.query().is_some() {
        let pairs: Vec<_> = url.query_pairs().map(|(k, v)| {
            let value = if is_secret(&k) { "[REDACTED]".to_owned() } else { v.into_owned() };
            (k.into_owned(), value)
        }).collect();
        url.query_pairs_mut().clear().extend_pairs(pairs);
    }
    url.to_string()
}

fn is_text(mime: Option<&str>) -> bool {
    let mime = mime.unwrap_or("").to_ascii_lowercase();
    mime.starts_with("text/") || mime.contains("json") ||
        mime.contains("xml") || mime.contains("javascript") ||
        mime.contains("x-www-form-urlencoded")
}

impl HttpRecorder {
    pub(crate) fn new(database_path: &str) -> Self {
        let path = if database_path == ":memory:" {
            None
        } else {
            Path::new(database_path).parent()
                .map(|p| p.join("diagnostics/ladies-http.jsonl"))
        };
        let bytes = path.as_ref().and_then(|p| fs::metadata(p).ok()).map_or(0, |m| m.len());
        let entries = path.as_ref().and_then(|p| File::open(p).ok())
            .map_or(0, |f| BufReader::new(f).lines().filter(Result::is_ok).count() as u32);
        Self {
            path,
            state: Mutex::new(State {
                mode: CaptureMode::Off,
                entries,
                bytes,
                stopped: bytes >= MAX_SESSION_BYTES,
                last_error: None,
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
        self.state.lock().map(|s| s.mode).unwrap_or(CaptureMode::Off)
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
                mode: CaptureMode::Off, entries: 0, bytes: 0, stopped: true,
                last_error: Some("HTTP recorder lock poisoned".into()),
            }
        }
    }

    /// Recorder errors never alter a source request or an image load.
    pub(crate) fn record(&self, mut event: HttpObservation) {
        let Ok(mut state) = self.state.lock() else { return };
        if state.mode == CaptureMode::Off || state.stopped { return };
        let Some(path) = self.path.as_ref() else { return };
        event.url = redacted_url(&event.url);
        event.final_url = redacted_url(&event.final_url);
        redacted_headers(&mut event.request_headers);
        redacted_headers(&mut event.response_headers);
        if state.mode != CaptureMode::Text {
            event.body = None;
            event.body_omitted = Some("metadata_mode".into());
        } else if event.is_media {
            event.body = None;
            event.body_omitted = Some("media_body_disabled".into());
        } else if !is_text(event.mime.as_deref()) {
            event.body = None;
            event.body_omitted = Some("non_text_mime".into());
        } else if event.body.as_ref().is_some_and(|b| b.len() > MAX_BODY_BYTES) {
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
        let write_result = (|| -> io::Result<()> {
            if let Some(parent) = path.parent() { fs::create_dir_all(parent)?; }
            let mut file = OpenOptions::new().create(true).append(true).open(path)?;
            file.write_all(&line)?;
            file.write_all(b"\n")?;
            file.flush()
        })();
        match write_result {
            Ok(()) => {
                state.bytes += line.len() as u64 + 1;
                state.entries += 1;
            }
            Err(error) => state.last_error = Some(error.to_string()),
        }
    }

    pub(crate) fn clear(&self) -> io::Result<()> {
        let mut state = self.state.lock().map_err(|_| io::Error::other("Recorder lock poisoned"))?;
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
        let _state = self.state.lock().map_err(|_| io::Error::other("Recorder lock poisoned"))?;
        let path = self.path.as_ref().ok_or_else(|| io::Error::other("Recorder requires file storage"))?;
        let file = File::open(path)?;
        let entries = BufReader::new(file).lines()
            .filter_map(|line| line.ok().and_then(|s| serde_json::from_str::<HttpObservation>(&s).ok()))
            .map(|item| har_entry(&item))
            .collect::<Vec<_>>();
        fs::create_dir_all(directory)?;
        let mut tmp = Builder::new()
            .prefix("ladies-http-").suffix(".har").tempfile_in(directory)?;
        serde_json::to_writer_pretty(&mut tmp, &json!({
            "log": {
                "version": "1.2",
                "creator": {"name": "Ladies Explorer Android", "version": "0.1.0"},
                "entries": entries
            }
        }))?;
        tmp.write_all(b"\n")?;
        tmp.as_file_mut().sync_all()?;
        let (_, path) = tmp.keep().map_err(|error| error.error)?;
        Ok(path)
    }
}

fn har_entry(event: &HttpObservation) -> Value {
    let timestamp = time::OffsetDateTime::from_unix_timestamp_nanos(event.started_ms as i128 * 1_000_000)
        .ok().and_then(|dt| dt.format(&Rfc3339).ok())
        .unwrap_or_else(|| "1970-01-01T00:00:00Z".into());
    let query = Url::parse(&event.url).ok().map(|url| {
        url.query_pairs().map(|(name, value)| {
            json!({"name": name, "value": value})
        }).collect::<Vec<_>>()
    }).unwrap_or_default();
    let to_headers = |values: &[HttpHeader]| -> Vec<Value> {
        values.iter().map(|h| json!({"name": h.name, "value": h.value})).collect()
    };
    let mut content = json!({
        "size": event.response_size.unwrap_or_default(),
        "mimeType": event.mime.as_deref().unwrap_or("application/octet-stream"),
    });
    if let Some(body) = &event.body {
        content["text"] = json!(body);
    }
    let total = event.headers_elapsed_ms.saturating_add(event.body_elapsed_ms);
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
            "send": 0,
            "wait": event.headers_elapsed_ms,
            "receive": event.body_elapsed_ms
        },
        "_diagnostics": {
            "final_url": event.final_url,
            "error": event.error,
            "body_omitted": event.body_omitted,
            "timing_note": "Wait includes unmeasured connection, redirects and response headers; it is not server TTFB. Receive is only time spent reading an instrumented body.",
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn sample() -> HttpObservation {
        HttpObservation {
            method: "GET".into(), url: "https://www.ladies.de/api/list?token=abc".into(),
            final_url: "https://www.ladies.de/api/list?token=abc".into(),
            status: Some(200), http_version: "HTTP/2.0".into(),
            request_headers: vec![HttpHeader {name: "Authorization".into(),value: "Bearer secret".into()}],
            response_headers: vec![HttpHeader {name: "Set-Cookie".into(),value: "session=test".into()}],
            mime: Some("application/json".into()), response_size: Some(7),
            started_ms: started_ms(), headers_elapsed_ms: 25, body_elapsed_ms: 5,
            body: Some("{\"a\":1}".into()), is_media: false,
            error: None, body_omitted: None
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
        let har: Value = serde_json::from_slice(&fs::read(recorder.export(root.path()).unwrap()).unwrap()).unwrap();
        let entries = har["log"]["entries"].as_array().unwrap();
        assert!(entries[0]["response"]["content"].get("text").is_none());
        assert_eq!(entries[1]["response"]["content"]["text"].as_str().unwrap().len(), MAX_BODY_BYTES);
        assert_eq!(entries[2]["_diagnostics"]["body_omitted"], "body_over_2_mib");
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
}
