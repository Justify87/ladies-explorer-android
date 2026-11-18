use std::fs;
use std::io::{Cursor, Read, Write};
use std::path::Path;
use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::media_url::validate_media_url;
use image::imageops::FilterType;
use reqwest::blocking::Client;
use rusqlite::{OptionalExtension, params};
use sha2::{Digest, Sha256};

use crate::{
    CoreError, LadiesCore,
    http_capture::{self, CaptureMode, HttpObservation, HttpRecorder},
    identity,
    operation::RequestControl,
};

const MAX_IMAGE_BYTES: u64 = 12 * 1024 * 1024;
const MAX_VIDEO_BYTES: u64 = 48 * 1024 * 1024;
const MAX_REFERENCES: usize = 80;
const MAX_ARCHIVE_DURATION: Duration = Duration::from_secs(150);
const MAX_REQUEST_DURATION: Duration = Duration::from_secs(15);
const MAX_PIXELS: u64 = 24_000_000;

#[derive(Debug, Clone, uniffi::Record)]
pub struct MediaArchiveReport {
    pub snapshot_id: i64,
    pub downloaded: u32,
    pub reused: u32,
    pub failed: u32,
    pub deferred: u32,
}

#[derive(Debug, Clone)]
struct Reference {
    id: i64,
    listing_id: i64,
    kind: String,
    url: String,
    base: String,
    revision: Option<String>,
    asset_id: Option<i64>,
    local_path: Option<String>,
    cached_asset_id: Option<i64>,
    cached_path: Option<String>,
    cached_revision: Option<String>,
    cached_dhash: Option<String>,
}

struct Download {
    bytes: Vec<u8>,
    mime: Option<String>,
    etag: Option<String>,
    last_modified: Option<String>,
}

trait Fetcher {
    fn get(
        &self,
        url: &str,
        limit: u64,
        timeout: Duration,
        control: &RequestControl,
    ) -> Result<Download, CoreError>;
}

struct HttpFetcher {
    client: Client,
    recorder: Arc<HttpRecorder>,
}
impl HttpFetcher {
    fn new(recorder: Arc<HttpRecorder>) -> Result<Self, CoreError> {
        Ok(Self {
            client: Client::builder()
                .timeout(Duration::from_secs(25))
                .redirect(reqwest::redirect::Policy::none())
                .user_agent("Mozilla/5.0 LadiesExplorerAndroid/1")
                .build()
                .map_err(|e| CoreError::Media(e.to_string()))?,
            recorder,
        })
    }
}

impl Fetcher for HttpFetcher {
    fn get(
        &self,
        url: &str,
        limit: u64,
        timeout: Duration,
        control: &RequestControl,
    ) -> Result<Download, CoreError> {
        if control.is_cancelled() {
            return Err(CoreError::Media("Media archive cancelled".into()));
        }
        let started = Instant::now();
        let request = self
            .client
            .get(url)
            .timeout(timeout)
            .header("Referer", "https://www.ladies.de/")
            .build()
            .map_err(|error| CoreError::Media(format!("Media request failed: {error}")))?;
        let mut observation = (self.recorder.mode() != CaptureMode::Off).then(|| HttpObservation {
            method: request.method().to_string(),
            url: request.url().to_string(),
            final_url: request.url().to_string(),
            status: None,
            http_version: "unknown".into(),
            request_headers: http_capture::headers(request.headers()),
            response_headers: Vec::new(),
            mime: None,
            response_size: None,
            started_ms: http_capture::started_ms(),
            headers_elapsed_ms: 0,
            body_elapsed_ms: 0,
            body: None,
            is_media: true,
            error: None,
            body_omitted: None,
        });
        let sent = self.client.execute(request);
        let mut response = match sent {
            Ok(response) => response,
            Err(error) => {
                if let Some(mut obs) = observation.take() {
                    obs.headers_elapsed_ms = started.elapsed().as_millis() as u64;
                    obs.error = Some(error.to_string());
                    self.recorder.record(obs);
                }
                return Err(CoreError::Media(format!("Media request failed: {error}")));
            }
        };
        if let Some(obs) = observation.as_mut() {
            obs.headers_elapsed_ms = started.elapsed().as_millis() as u64;
            obs.status = Some(response.status().as_u16());
            obs.http_version = format!("{:?}", response.version());
            obs.final_url = response.url().to_string();
            obs.response_headers = http_capture::headers(response.headers());
            obs.mime = response
                .headers()
                .get(reqwest::header::CONTENT_TYPE)
                .and_then(|h| h.to_str().ok())
                .map(ToOwned::to_owned);
            obs.response_size = response.content_length();
        }
        if !response.status().is_success() {
            if let Some(obs) = observation.take() {
                self.recorder.record(obs);
            }
            return Err(CoreError::Media(format!(
                "Media HTTP status {}",
                response.status()
            )));
        }
        if response.content_length().is_some_and(|size| size > limit) {
            if let Some(mut obs) = observation.take() {
                obs.body_omitted = Some("media_size_limit".into());
                self.recorder.record(obs);
            }
            return Err(CoreError::Media("Media exceeds byte limit".into()));
        }
        let mime = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|h| h.to_str().ok())
            .map(|h| {
                h.split(';')
                    .next()
                    .unwrap_or("")
                    .trim()
                    .to_ascii_lowercase()
            });
        let etag = response
            .headers()
            .get(reqwest::header::ETAG)
            .and_then(|h| h.to_str().ok())
            .map(ToOwned::to_owned);
        let last_modified = response
            .headers()
            .get(reqwest::header::LAST_MODIFIED)
            .and_then(|h| h.to_str().ok())
            .map(ToOwned::to_owned);
        let body_started = Instant::now();
        let mut bytes = Vec::new();
        let mut chunk = [0u8; 32 * 1024];
        loop {
            if control.is_cancelled() {
                if let Some(mut obs) = observation.take() {
                    obs.error = Some("Media archive cancelled".into());
                    obs.body_elapsed_ms = body_started.elapsed().as_millis() as u64;
                    obs.response_size = Some(bytes.len() as u64);
                    self.recorder.record(obs);
                }
                return Err(CoreError::Media("Media archive cancelled".into()));
            }
            let read = match response.read(&mut chunk) {
                Ok(read) => read,
                Err(error) => {
                    if let Some(mut obs) = observation.take() {
                        obs.error = Some(error.to_string());
                        obs.body_elapsed_ms = body_started.elapsed().as_millis() as u64;
                        obs.response_size = Some(bytes.len() as u64);
                        self.recorder.record(obs);
                    }
                    return Err(CoreError::Media(format!("Media response failed: {error}")));
                }
            };
            if read == 0 {
                break;
            }
            if bytes.len() as u64 + read as u64 > limit {
                if let Some(mut obs) = observation.take() {
                    obs.error = Some("Media exceeds byte limit".into());
                    obs.body_elapsed_ms = body_started.elapsed().as_millis() as u64;
                    self.recorder.record(obs);
                }
                return Err(CoreError::Media("Media exceeds byte limit".into()));
            }
            bytes.extend_from_slice(&chunk[..read]);
        }
        if let Some(mut obs) = observation {
            obs.body_elapsed_ms = body_started.elapsed().as_millis() as u64;
            obs.response_size = Some(bytes.len() as u64);
            self.recorder.record(obs);
        }
        if bytes.is_empty() {
            return Err(CoreError::Media("Empty media response".into()));
        }
        Ok(Download {
            bytes,
            mime,
            etag,
            last_modified,
        })
    }
}

fn image_dhash(bytes: &[u8]) -> Result<(String, u32, u32, &'static str), CoreError> {
    let reader = image::ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()
        .map_err(|e| CoreError::Media(e.to_string()))?;
    let format = reader
        .format()
        .ok_or_else(|| CoreError::Media("Unsupported image format".into()))?;
    let (width, height) = reader
        .into_dimensions()
        .map_err(|e| CoreError::Media(format!("Cannot read image dimensions: {e}")))?;
    if width == 0
        || height == 0
        || width > 8192
        || height > 8192
        || u64::from(width) * u64::from(height) > MAX_PIXELS
    {
        return Err(CoreError::Media(
            "Image dimensions exceed safe decode budget".into(),
        ));
    }
    let mut decoder = image::ImageReader::with_format(Cursor::new(bytes), format);
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(8192);
    limits.max_image_height = Some(8192);
    limits.max_alloc = Some(128 * 1024 * 1024);
    decoder.limits(limits);
    let img = decoder
        .decode()
        .map_err(|e| CoreError::Media(format!("Cannot decode image: {e}")))?;
    let mono = img
        .grayscale()
        .resize_exact(9, 8, FilterType::Lanczos3)
        .to_luma8();
    let mut hash = 0u64;
    for y in 0..8 {
        for x in 0..8 {
            hash = (hash << 1) | u64::from(mono.get_pixel(x, y)[0] > mono.get_pixel(x + 1, y)[0]);
        }
    }
    let ext = match format {
        image::ImageFormat::Jpeg => ".jpg",
        image::ImageFormat::Png => ".png",
        image::ImageFormat::WebP => ".webp",
        image::ImageFormat::Gif => ".gif",
        _ => ".img",
    };
    Ok((format!("{hash:016x}"), width, height, ext))
}

fn relative_path(kind: &str, sha: &str, ext: &str) -> String {
    let sub = if kind == "IMAGE" { "images" } else { "videos" };
    format!("media/{sub}/{}/{sha}{ext}", &sha[..2])
}

fn snapshot_refs(core: &LadiesCore, snapshot_id: i64) -> Result<Vec<Reference>, CoreError> {
    let db = core.connection()?;
    let mut query = db.prepare(
        "SELECT m.snapshot_media_id, s.listing_id,
                m.kind,m.remote_url,COALESCE(m.remote_base_url,m.remote_url),
                m.revision_token,m.media_asset_id,a.local_path,
                src.media_asset_id,cached.local_path,src.revision_token,cached.dhash
         FROM snapshot_media m
         JOIN listing_snapshots s ON s.snapshot_id=m.snapshot_id
                  LEFT JOIN media_assets a ON a.media_asset_id=m.media_asset_id
         LEFT JOIN media_sources src ON src.remote_base_url=m.remote_base_url
         LEFT JOIN media_assets cached ON cached.media_asset_id=src.media_asset_id
         WHERE m.snapshot_id=?1 ORDER BY m.position,m.snapshot_media_id",
    )?;
    let refs = query
        .query_map([snapshot_id], |r| {
            Ok(Reference {
                id: r.get(0)?,
                listing_id: r.get(1)?,
                kind: r.get(2)?,
                url: r.get(3)?,
                base: r.get(4)?,
                revision: r.get(5)?,
                asset_id: r.get(6)?,
                local_path: r.get(7)?,
                cached_asset_id: r.get(8)?,
                cached_path: r.get(9)?,
                cached_revision: r.get(10)?,
                cached_dhash: r.get(11)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    let exists: bool = db.query_row(
        "SELECT EXISTS(SELECT 1 FROM listing_snapshots WHERE snapshot_id=?1)",
        [snapshot_id],
        |r| r.get(0),
    )?;
    if !exists {
        return Err(CoreError::Media("Unknown profile snapshot".into()));
    }
    Ok(refs)
}

fn current_person(db: &rusqlite::Connection, listing_id: i64) -> Result<i64, CoreError> {
    db.query_row(
        "SELECT person_id FROM listings WHERE listing_id=?1",
        [listing_id],
        |row| row.get(0),
    )
    .map_err(Into::into)
}

fn persist(
    core: &LadiesCore,
    root: &Path,
    reference: &Reference,
    download: Download,
) -> Result<(), CoreError> {
    let processing_started = Instant::now();
    let hash = format!("{:x}", Sha256::digest(&download.bytes));
    let (dhash, width, height, ext) = if reference.kind == "IMAGE" {
        let (hash, w, h, ext) = image_dhash(&download.bytes)?;
        (Some(hash), Some(w), Some(h), ext)
    } else {
        let ext = match download.mime.as_deref() {
            Some("video/mp4") => ".mp4",
            Some("video/webm") => ".webm",
            _ => return Err(CoreError::Media("Unexpected video MIME type".into())),
        };
        (None, None, None, ext)
    };
    core.diagnostics
        .phase("MEDIA", "image_hash_and_decode", processing_started);
    // Respect an already imported asset path for the same content hash.
    let existing: Option<String> = {
        let db = core.connection()?;
        db.query_row(
            "SELECT local_path FROM media_assets WHERE sha256=?1",
            [&hash],
            |r| r.get(0),
        )
        .optional()?
    };
    let rel = existing
        .filter(|path| root.join(path).is_file())
        .unwrap_or_else(|| relative_path(&reference.kind, &hash, ext));
    let path = root.join(&rel);
    let parent = path
        .parent()
        .ok_or_else(|| CoreError::Media("Invalid media path".into()))?;
    fs::create_dir_all(parent)?;
    if !path.is_file() {
        let mut temporary = tempfile::NamedTempFile::new_in(parent)?;
        temporary.write_all(&download.bytes)?;
        temporary.flush()?;
        if let Err(error) = temporary.persist_noclobber(&path) {
            if error.error.kind() != std::io::ErrorKind::AlreadyExists {
                return Err(CoreError::Media(error.error.to_string()));
            }
        }
    }
    let sqlite_started = Instant::now();
    let mut db = core.connection()?;
    let tx = db.transaction()?;
    tx.execute(
        "INSERT INTO media_assets
         (sha256,dhash,kind,mime_type,byte_size,width,height,local_path)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8)
         ON CONFLICT(sha256) DO UPDATE SET
         last_seen_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),
         dhash=COALESCE(media_assets.dhash,excluded.dhash),
         width=COALESCE(media_assets.width,excluded.width),
         height=COALESCE(media_assets.height,excluded.height),
         local_path=excluded.local_path",
        params![
            hash,
            dhash,
            reference.kind,
            download.mime,
            download.bytes.len() as i64,
            width,
            height,
            rel
        ],
    )?;
    let id: i64 = tx.query_row(
        "SELECT media_asset_id FROM media_assets WHERE sha256=?1",
        [&hash],
        |r| r.get(0),
    )?;
    tx.execute(
        "INSERT INTO media_sources
         (remote_base_url,last_remote_url,revision_token,etag,last_modified,
          content_length,media_asset_id)
         VALUES (?1,?2,?3,?4,?5,?6,?7)
         ON CONFLICT(remote_base_url) DO UPDATE SET
         last_remote_url=excluded.last_remote_url,
         revision_token=excluded.revision_token,etag=excluded.etag,
         last_modified=excluded.last_modified,content_length=excluded.content_length,
         media_asset_id=excluded.media_asset_id,
         last_checked_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')",
        params![
            reference.base,
            reference.url,
            reference.revision,
            download.etag,
            download.last_modified,
            download.bytes.len() as i64,
            id
        ],
    )?;
    tx.execute(
        "UPDATE snapshot_media SET media_asset_id=?1,etag=?2,last_modified=?3
         WHERE snapshot_media_id=?4",
        params![id, download.etag, download.last_modified, reference.id],
    )?;
    if let Some(ref dhash) = dhash {
        identity::record_image_hash(
            &tx,
            current_person(&tx, reference.listing_id)?,
            reference.listing_id,
            dhash,
        )?;
        identity::refresh(&tx, reference.listing_id)?;
    }
    tx.commit()?;
    core.diagnostics
        .phase("MEDIA", "sqlite_media_write", sqlite_started);
    Ok(())
}

fn archive_with(
    core: &LadiesCore,
    snapshot_id: i64,
    include_videos: bool,
    fetcher: &impl Fetcher,
    control: &RequestControl,
) -> Result<MediaArchiveReport, CoreError> {
    let root = core
        .media_root
        .as_ref()
        .ok_or_else(|| CoreError::Media("Media archive requires a file-backed database".into()))?;
    let refs = snapshot_refs(core, snapshot_id)?;
    let mut report = MediaArchiveReport {
        snapshot_id,
        downloaded: 0,
        reused: 0,
        failed: 0,
        deferred: 0,
    };
    let started = Instant::now();
    for (index, reference) in refs.iter().enumerate() {
        if control.is_cancelled()
            || started.elapsed() >= MAX_ARCHIVE_DURATION
            || index >= MAX_REFERENCES
        {
            report.deferred += (refs.len() - index) as u32;
            break;
        }
        if reference.kind == "VIDEO" && !include_videos {
            report.deferred += 1;
            continue;
        }
        if reference.asset_id.is_some()
            && reference
                .local_path
                .as_deref()
                .is_some_and(|p| root.join(p).is_file())
        {
            report.reused += 1;
            continue;
        }
        // Cross-snapshot reuse is safe only for a matching, explicit revision token.
        if reference.revision.is_some()
            && reference.revision == reference.cached_revision
            && reference.cached_asset_id.is_some()
            && reference
                .cached_path
                .as_deref()
                .is_some_and(|path| root.join(path).is_file())
        {
            let mut db = core.connection()?;
            let tx = db.transaction()?;
            tx.execute(
                "UPDATE snapshot_media SET media_asset_id=?1 WHERE snapshot_media_id=?2",
                params![reference.cached_asset_id, reference.id],
            )?;
            if let Some(hash) = reference.cached_dhash.as_deref() {
                identity::record_image_hash(
                    &tx,
                    current_person(&tx, reference.listing_id)?,
                    reference.listing_id,
                    hash,
                )?;
                identity::refresh(&tx, reference.listing_id)?;
            }
            tx.commit()?;
            report.reused += 1;
            continue;
        }
        let result = (|| -> Result<(), CoreError> {
            let url = validate_media_url(&reference.url, Some(&reference.kind))?;
            let limit = if reference.kind == "IMAGE" {
                MAX_IMAGE_BYTES
            } else {
                MAX_VIDEO_BYTES
            };
            let timeout =
                MAX_REQUEST_DURATION.min(MAX_ARCHIVE_DURATION.saturating_sub(started.elapsed()));
            if timeout.is_zero() || control.is_cancelled() {
                return Err(CoreError::Media("Media archive interrupted".into()));
            }
            let response = fetcher.get(url.as_str(), limit, timeout, control)?;
            if control.is_cancelled() {
                return Err(CoreError::Media("Media archive cancelled".into()));
            }
            persist(core, root, reference, response)?;
            Ok(())
        })();
        match result {
            Ok(()) => report.downloaded += 1,
            Err(_) if control.is_cancelled() => {
                report.deferred += (refs.len() - index) as u32;
                break;
            }
            Err(error) => {
                report.failed += 1;
                core.diagnostics.event(
                    "MEDIA",
                    format!(
                        "Asset for snapshot={} position={} failed: {}",
                        snapshot_id,
                        index + 1,
                        error
                    ),
                );
            }
        }
    }
    Ok(report)
}

pub(crate) fn archive(
    core: &LadiesCore,
    snapshot_id: i64,
    include_videos: bool,
    control: &RequestControl,
) -> Result<MediaArchiveReport, CoreError> {
    let http = HttpFetcher::new(Arc::clone(&core.recorder))?;
    archive_with(core, snapshot_id, include_videos, &http, control)
}

#[cfg(test)]
mod tests {
    use super::*;
    struct FixtureFetcher(Vec<u8>);
    impl Fetcher for FixtureFetcher {
        fn get(
            &self,
            _: &str,
            _: u64,
            _: Duration,
            _: &RequestControl,
        ) -> Result<Download, CoreError> {
            Ok(Download {
                bytes: self.0.clone(),
                mime: Some("image/png".into()),
                etag: None,
                last_modified: None,
            })
        }
    }
    #[test]
    fn rejects_oversized_image_dimensions_before_decode() {
        // PNG header announces 12001x1 pixels; decoding must not be attempted.
        let mut png = std::io::Cursor::new(Vec::new());
        image::DynamicImage::new_rgb8(1, 1)
            .write_to(&mut png, image::ImageFormat::Png)
            .unwrap();
        let mut bytes = png.into_inner();
        bytes[16..20].copy_from_slice(&12_001u32.to_be_bytes());
        assert!(image_dhash(&bytes).is_err());
    }

    fn fixture() -> (tempfile::TempDir, std::sync::Arc<LadiesCore>, i64, i64) {
        let temp = tempfile::tempdir().unwrap();
        let path = temp
            .path()
            .join("ladies.sqlite")
            .to_string_lossy()
            .to_string();
        let core = LadiesCore::new(path).unwrap();
        let person = core.create_person(Some("Anna".into())).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "100".into(), None)
            .unwrap();
        let db = core.connection().unwrap();
        db.execute(
            "INSERT INTO listing_snapshots(listing_id,fingerprint) VALUES (?1,'test')",
            [listing],
        )
        .unwrap();
        let snapshot = db.last_insert_rowid();
        db.execute(
            "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
            params![snapshot, listing],
        )
        .unwrap();
        for index in 0..2 {
            db.execute(
                "INSERT INTO snapshot_media
                 (snapshot_id,position,kind,role,remote_url,remote_base_url)
                 VALUES (?1,?2,'IMAGE','GALLERY',?3,?4)",
                params![
                    snapshot,
                    index + 1,
                    format!("https://bilder1.ladies-cdn.de/Bilder/1/{index}-A1.JPG?rev=1"),
                    format!("https://bilder1.ladies-cdn.de/Bilder/1/{index}-A1.JPG")
                ],
            )
            .unwrap();
        }
        drop(db);
        (temp, core, snapshot, listing)
    }
    #[test]
    fn archive_uses_current_owner_after_listing_is_reassigned() {
        let (_temp, core, snapshot, listing) = fixture();
        let pending = snapshot_refs(&core, snapshot).unwrap()[0].clone();
        let new_owner = core.create_person(Some("New owner".into())).unwrap();
        {
            let db = core.connection().unwrap();
            db.execute(
                "UPDATE listings SET person_id=?1 WHERE listing_id=?2",
                params![new_owner, listing],
            )
            .unwrap();
        }
        let mut png = std::io::Cursor::new(Vec::new());
        image::DynamicImage::new_rgb8(16, 16)
            .write_to(&mut png, image::ImageFormat::Png)
            .unwrap();
        persist(
            &core,
            core.media_root.as_ref().unwrap(),
            &pending,
            Download {
                bytes: png.into_inner(),
                mime: Some("image/png".into()),
                etag: None,
                last_modified: None,
            },
        )
        .unwrap();
        let db = core.connection().unwrap();
        let owner: i64 = db
            .query_row(
                "SELECT person_id FROM person_identifiers WHERE kind='IMAGE_DHASH'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(owner, new_owner);
    }

    #[test]
    fn archive_is_content_addressed_and_repeatable() {
        let (_temp, core, snapshot, listing) = fixture();
        let mut png = std::io::Cursor::new(Vec::new());
        image::DynamicImage::new_rgb8(16, 16)
            .write_to(&mut png, image::ImageFormat::Png)
            .unwrap();
        let fetcher = FixtureFetcher(png.into_inner());
        let first =
            archive_with(&core, snapshot, false, &fetcher, &RequestControl::default()).unwrap();
        assert_eq!((first.downloaded, first.failed), (2, 0));
        let again =
            archive_with(&core, snapshot, false, &fetcher, &RequestControl::default()).unwrap();
        assert_eq!((again.reused, again.downloaded), (2, 0));
        let db = core.connection().unwrap();
        let assets: i64 = db
            .query_row("SELECT COUNT(*) FROM media_assets", [], |r| r.get(0))
            .unwrap();
        assert_eq!(assets, 1);
        let hashes: i64 = db
            .query_row(
                "SELECT COUNT(*) FROM person_identifiers WHERE kind='IMAGE_DHASH'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(hashes, 1);
        let links: i64 = db
            .query_row(
                "SELECT COUNT(*) FROM snapshot_media WHERE media_asset_id IS NOT NULL",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(links, 2);
        drop(db);
        assert_eq!(core.list_identity_candidates(listing).unwrap().len(), 0);
    }
    #[test]
    fn existing_revision_is_linked_without_network() {
        let (_temp, core, first, listing) = fixture();
        let mut image = std::io::Cursor::new(Vec::new());
        image::DynamicImage::new_rgb8(16, 16)
            .write_to(&mut image, image::ImageFormat::Png)
            .unwrap();
        let fetcher = FixtureFetcher(image.into_inner());
        archive_with(&core, first, false, &fetcher, &RequestControl::default()).unwrap();
        let db = core.connection().unwrap();
        db.execute(
            "INSERT INTO listing_snapshots(listing_id,fingerprint)
                    VALUES (?1,'later')",
            [listing],
        )
        .unwrap();
        let second = db.last_insert_rowid();
        db.execute(
            "INSERT INTO snapshot_media
             (snapshot_id,position,kind,role,remote_url,remote_base_url,revision_token)
             SELECT ?1,position,kind,role,remote_url,remote_base_url,'rev1'
             FROM snapshot_media WHERE snapshot_id=?2",
            params![second, first],
        )
        .unwrap();
        // The first snapshot now provides a verified cached revision.
        db.execute("UPDATE media_sources SET revision_token='rev1'", [])
            .unwrap();
        drop(db);
        struct Offline;
        impl Fetcher for Offline {
            fn get(
                &self,
                _: &str,
                _: u64,
                _: Duration,
                _: &RequestControl,
            ) -> Result<Download, CoreError> {
                Err(CoreError::Media("No network allowed".into()))
            }
        }
        let reused =
            archive_with(&core, second, false, &Offline, &RequestControl::default()).unwrap();
        assert_eq!((reused.reused, reused.failed), (2, 0));
        let db = core.connection().unwrap();
        let count:i64=db.query_row(
            "SELECT COUNT(*) FROM snapshot_media WHERE snapshot_id=?1 AND media_asset_id IS NOT NULL",
            [second],|r|r.get(0)
        ).unwrap();
        assert_eq!(count, 2);
    }

    #[test]
    fn failed_download_keeps_profile_and_remote_media_references() {
        let (_temp, core, snapshot, listing) = fixture();
        struct Offline;
        impl Fetcher for Offline {
            fn get(
                &self,
                _: &str,
                _: u64,
                _: Duration,
                _: &RequestControl,
            ) -> Result<Download, CoreError> {
                Err(CoreError::Media("Network unavailable".into()))
            }
        }
        let report =
            archive_with(&core, snapshot, false, &Offline, &RequestControl::default()).unwrap();
        assert_eq!((report.failed, report.downloaded), (2, 0));
        let db = core.connection().unwrap();
        let snapshots: i64 = db
            .query_row(
                "SELECT COUNT(*) FROM listing_snapshots WHERE listing_id=?1",
                [listing],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(snapshots, 1);
        let links:i64=db.query_row(
            "SELECT COUNT(*) FROM snapshot_media WHERE snapshot_id=?1 AND media_asset_id IS NULL",
            [snapshot],|r|r.get(0)).unwrap();
        assert_eq!(links, 2);
    }

    #[test]
    fn cancelled_archive_preserves_pending_assets() {
        let (_temp, core, snapshot, _) = fixture();
        let control = RequestControl::default();
        control.cancel();
        struct Never;
        impl Fetcher for Never {
            fn get(
                &self,
                _: &str,
                _: u64,
                _: Duration,
                _: &RequestControl,
            ) -> Result<Download, CoreError> {
                panic!("Cancelled archive attempted an HTTP request")
            }
        }
        let report = archive_with(&core, snapshot, false, &Never, &control).unwrap();
        assert_eq!(
            (report.downloaded, report.failed, report.deferred),
            (0, 0, 2)
        );
    }

    #[test]
    fn oversized_pixel_dimensions_are_rejected_before_decode() {
        let mut png = Cursor::new(Vec::new());
        image::DynamicImage::new_rgb8(8193, 1)
            .write_to(&mut png, image::ImageFormat::Png)
            .unwrap();
        assert!(matches!(
            image_dhash(&png.into_inner()),
            Err(CoreError::Media(_))
        ));
    }

    #[test]
    fn only_whitelisted_cdn_hosts_are_downloaded() {
        assert!(
            validate_media_url("https://bilder1.ladies-cdn.de/Bilder/1.jpg", Some("IMAGE")).is_ok()
        );
        assert!(
            validate_media_url(
                "https://bilder1.ladies-cdn.de.evil.test/a.jpg",
                Some("IMAGE")
            )
            .is_err()
        );
        assert!(validate_media_url("http://bilder1.ladies-cdn.de/a.jpg", Some("IMAGE")).is_err());
        assert!(validate_media_url("https://127.0.0.1/a.jpg", Some("IMAGE")).is_err());
        assert!(validate_media_url("https://video2.ladies-cdn.de/a.mp4", Some("VIDEO")).is_ok());
        assert!(
            validate_media_url("https://m.ladies.de/Bilder/gallery.jpg", Some("IMAGE")).is_ok()
        );
        assert!(validate_media_url("https://bilder1.ladies-cdn.de/a.jpg", Some("VIDEO")).is_err());
    }
}
