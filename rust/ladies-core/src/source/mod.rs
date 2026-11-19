pub(crate) mod filter;
mod network;
mod parse;
mod storage;

use crate::{
    CoreError, LadiesCore, diagnostics::Diagnostics, http_capture::HttpRecorder,
    operation::RequestControl,
};
use std::sync::Arc;
use std::time::Instant;

pub(crate) struct SourceSession(network::SourceHttp);

impl SourceSession {
    pub(crate) fn new(
        diagnostics: Arc<Diagnostics>,
        recorder: Arc<HttpRecorder>,
    ) -> Result<Self, CoreError> {
        network::SourceHttp::new(diagnostics, recorder).map(Self)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct SearchRequest {
    pub city: String,
    pub radius_km: u32,
    pub category: String,
    pub age_min: u32,
    pub age_max: u32,
    pub height_min: u32,
    pub height_max: u32,
    pub filters_json: String,
    pub max_pages: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchCard {
    pub listing_id: i64,
    pub person_id: i64,
    pub advert_id: String,
    pub name: String,
    pub city: String,
    pub category: String,
    pub profile_url: String,
    pub thumbnail_url: Option<String>,
    pub has_video: bool,
    pub is_premium: bool,
    pub has_ai_content: bool,
    pub has_18plus: bool,
    pub has_stars: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchPreview {
    pub server_count: Option<u32>,
    pub radius_verified: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchResponse {
    pub query: SearchRequest,
    pub local: crate::saved_search::LocalVisibility,
    pub search_run_id: i64,
    pub city: String,
    pub requested_radius_km: u32,
    pub server_count: Option<u32>,
    pub total_pages: u32,
    pub fetched_pages: u32,
    pub partial: bool,
    pub radius_boundary_stopped: bool,
    pub radius_verified: bool,
    pub profiles: Vec<SearchCard>,
}

#[derive(Debug, Clone, serde::Serialize, uniffi::Record)]
pub struct MediaReference {
    pub url: String,
    pub kind: String,
    pub position: u32,
    pub role: String,
    pub variant: Option<String>,
    pub revision_token: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ProfileResponse {
    pub listing_id: i64,
    pub snapshot_id: i64,
    pub new_snapshot: bool,
    pub advert_id: String,
    pub name: String,
    pub city: String,
    pub description: String,
    pub postal_code: Option<String>,
    pub hidden_location: Option<String>,
    pub source_profile_id: Option<String>,
    pub canonical_id: Option<String>,
    pub phone_display: Option<String>,
    pub published_at: Option<String>,
    pub traits_json: String,
    pub services_json: String,
    pub opening_times_json: String,
    pub media: Vec<MediaReference>,
}

#[derive(Debug, Clone)]
struct Card {
    advert_id: String,
    name: String,
    city: String,
    category: String,
    profile_url: String,
    thumbnail_url: Option<String>,
    has_video: bool,
    is_premium: bool,
    has_ai_content: bool,
    has_18plus: bool,
    has_stars: bool,
}

#[derive(Debug, Clone)]
struct Profile {
    advert_id: String,
    name: String,
    city: String,
    description: String,
    postal_code: Option<String>,
    hidden_location: Option<String>,
    source_profile_id: Option<String>,
    canonical_id: Option<String>,
    phone_display: Option<String>,
    published_at: Option<String>,
    traits_json: String,
    services_json: String,
    opening_times_json: String,
    media: Vec<MediaReference>,
}

pub(crate) fn preview(
    core: &LadiesCore,
    request: SearchRequest,
    control: &RequestControl,
) -> Result<SearchPreview, CoreError> {
    network::preview_search(&core.source_session.0, &request, control)
}

pub(crate) fn search(
    core: &LadiesCore,
    request: SearchRequest,
    control: Option<&RequestControl>,
) -> Result<SearchResponse, CoreError> {
    core.diagnostics.event(
        "SEARCH",
        format!(
            "Search requested city={} radius={} category={} max_pages={}",
            request.city, request.radius_km, request.category, request.max_pages
        ),
    );
    filter::validate_request(&request)?;
    if let Some(control) = control {
        control.check()?;
    }
    let fetch_started = Instant::now();
    let downloaded =
        network::fetch_search(&core.source_session.0, &request, control).map_err(|error| {
            core.diagnostics
                .event("SEARCH", format!("Search fetch failed: {error}"));
            error
        })?;
    core.diagnostics
        .phase("SEARCH", "source_fetch_and_pagination", fetch_started);
    if let Some(control) = control {
        control.check()?;
    }
    let storage_started = Instant::now();
    let saved = storage::save_search_with_control(core, &request, downloaded, control);
    core.diagnostics
        .phase("STORAGE", "search_sqlite_write", storage_started);
    match &saved {
        Ok(response) => core.diagnostics.event(
            "STORAGE",
            format!(
                "Search saved run={} cards={}",
                response.search_run_id,
                response.profiles.len()
            ),
        ),
        Err(error) => core
            .diagnostics
            .event("STORAGE", format!("Search save failed: {error}")),
    }
    saved
}

pub(crate) fn load_profile(
    core: &LadiesCore,
    listing_id: i64,
    control: Option<&RequestControl>,
) -> Result<ProfileResponse, CoreError> {
    if let Some(control) = control {
        control.check()?;
    }
    core.diagnostics
        .event("PROFILE", format!("Profile requested listing={listing_id}"));
    let target = storage::listing_target(core, listing_id, false)?;
    let network_started = Instant::now();
    let observation = core
        .source_session
        .0
        .get_profile(&target.url, &target.advert_id, control)?;
    let html = match observation {
        network::ProfileObservation::Active(html) => html,
        network::ProfileObservation::Unavailable(code) => {
            storage::save_status(core, &target, "UNAVAILABLE", code, false, control)?;
            return Err(CoreError::Source(format!("Profile HTTP status {code}")));
        }
    };
    core.diagnostics
        .phase("HTTP", "profile_fetch", network_started);
    let fallback = Card {
        advert_id: target.advert_id.clone(),
        name: target.name.clone(),
        city: target.city.clone(),
        category: target.category.clone(),
        profile_url: target.url.clone(),
        thumbnail_url: None,
        has_video: false,
        is_premium: false,
        has_ai_content: false,
        has_18plus: false,
        has_stars: false,
    };
    let parse_started = Instant::now();
    let profile = parse::parse_profile(&html, &fallback).map_err(|error| {
        core.diagnostics.html("profile_parse_failed", &html);
        core.diagnostics.event(
            "PROFILE",
            format!("Profile parse failed listing={listing_id}: {error}"),
        );
        error
    })?;
    core.diagnostics
        .phase("PROFILE", "html_parse", parse_started);
    core.diagnostics.event(
        "PROFILE",
        format!(
            "Profile parsed listing={} name={} media={} services_bytes={}",
            listing_id,
            profile.name,
            profile.media.len(),
            profile.services_json.len()
        ),
    );
    if let Some(control) = control {
        control.check()?;
    }
    let storage_started = Instant::now();
    let result = storage::save_observed_profile(core, &target, profile, control);
    core.diagnostics
        .phase("STORAGE", "profile_sqlite_write", storage_started);
    match &result {
        Ok(saved) => core.diagnostics.event(
            "STORAGE",
            format!(
                "Profile saved listing={} snapshot={} new_snapshot={}",
                listing_id, saved.snapshot_id, saved.new_snapshot
            ),
        ),
        Err(error) => core
            .diagnostics
            .event("STORAGE", format!("Profile save failed: {error}")),
    }
    result
}

pub(crate) fn saved_status_targets(core: &LadiesCore) -> Result<Vec<i64>, CoreError> {
    storage::saved_status_targets(core)
}

pub(crate) fn check_saved_status(
    core: &LadiesCore,
    listing_id: i64,
    control: &RequestControl,
) -> Result<String, CoreError> {
    control.check()?;
    let target = storage::listing_target(core, listing_id, true)?;
    let observation = core
        .source_session
        .0
        .get_profile(&target.url, &target.advert_id, Some(control))?;
    let (status, http_status) = match observation {
        network::ProfileObservation::Active(_) => ("ACTIVE", 200),
        network::ProfileObservation::Unavailable(code) => ("UNAVAILABLE", code),
    };
    storage::save_status(core, &target, status, http_status, true, Some(control))?;
    core.diagnostics.event(
        "PROFILE",
        format!("Status checked listing={listing_id} status={status} HTTP={http_status}"),
    );
    Ok(status.into())
}

#[cfg(test)]
impl SearchRequest {
    pub(crate) fn for_test() -> Self {
        Self {
            city: "Leipzig".into(),
            radius_km: 20,
            category: "".into(),
            age_min: 18,
            age_max: 60,
            height_min: 140,
            height_max: 200,
            filters_json: "{}".into(),
            max_pages: 10,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ProfileIdentity {
    pub person_id: i64,
    pub status: String,
    pub profile_url: Option<String>,
    pub person_new: bool,
    pub listing_new: bool,
    pub has_video: bool,
    pub age: Option<String>,
}

/// Only explicit, unambiguous source traits from an existing snapshot.
pub(crate) fn known_age(traits_json: Option<&str>) -> Option<String> {
    let value: serde_json::Value = serde_json::from_str(traits_json?).ok()?;
    let mut ages = value
        .as_object()?
        .iter()
        .filter(|(key, _)| key.eq_ignore_ascii_case("Alter"));
    let (_, age) = ages.next()?;
    if ages.next().is_some() {
        return None;
    }
    let text = match age {
        serde_json::Value::String(text) => text.as_str(),
        serde_json::Value::Array(values) if values.len() == 1 => values[0].as_str()?,
        _ => return None,
    };
    let text = text.trim();
    let digits = text.get(..2)?;
    if !digits.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    let unit = text.get(2..)?.trim();
    if !unit.is_empty() && !matches!(unit.to_ascii_lowercase().as_str(), "jahr" | "jahre") {
        return None;
    }
    Some(digits.to_owned())
}

pub(crate) fn validated_profile_url(value: &str) -> Option<String> {
    parse::valid_profile_url(value)
}

pub(crate) fn profile_identity(
    core: &LadiesCore,
    listing_id: i64,
) -> Result<ProfileIdentity, CoreError> {
    let db = core.connection()?;
    let (person_id, status, url, person_new, listing_new, has_video, traits_json): (
        i64,
        String,
        Option<String>,
        bool,
        bool,
        bool,
        Option<String>,
    ) = db.query_row(
        "SELECT l.person_id,l.current_status,l.current_url,
                COALESCE(unixepoch('now')-unixepoch(p.first_seen_at) BETWEEN 0 AND 129599,0),
                COALESCE(unixepoch('now')-COALESCE(unixepoch(s.published_at),
                    unixepoch(l.published_at),unixepoch(l.first_seen_at)) BETWEEN 0 AND 129599,0),
                CASE WHEN l.current_snapshot_id IS NOT NULL THEN EXISTS(
                    SELECT 1 FROM snapshot_media m WHERE m.snapshot_id=l.current_snapshot_id
                    AND m.kind='VIDEO')
                ELSE COALESCE((SELECT r.has_video FROM search_results r
                    WHERE r.listing_id=l.listing_id
                    ORDER BY r.observed_at DESC,r.search_result_id DESC LIMIT 1),0) END,
                s.traits_json
         FROM listings l JOIN persons p ON p.person_id=l.person_id
         LEFT JOIN listing_snapshots s ON s.snapshot_id=l.current_snapshot_id
         WHERE l.listing_id=?1",
        [listing_id],
        |r| {
            Ok((
                r.get(0)?,
                r.get(1)?,
                r.get(2)?,
                r.get(3)?,
                r.get(4)?,
                r.get(5)?,
                r.get(6)?,
            ))
        },
    )?;
    Ok(ProfileIdentity {
        person_id,
        status: match status.as_str() {
            "ACTIVE" | "INACTIVE" | "UNAVAILABLE" => status,
            _ => "UNKNOWN".into(),
        },
        profile_url: url.and_then(|value| parse::valid_profile_url(&value)),
        person_new,
        listing_new,
        has_video,
        age: known_age(traits_json.as_deref()),
    })
}

#[cfg(test)]
mod profile_identity_tests {
    use super::*;

    #[test]
    fn identity_uses_listing_owner_and_only_persisted_status_and_media() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(Some("Fixture".into())).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "42".into(), None)
            .unwrap();
        let value = profile_identity(&core, listing).unwrap();
        assert_eq!(value.person_id, person);
        assert_eq!(value.status, "UNKNOWN");
        assert!(value.person_new && value.listing_new);
        assert!(!value.has_video);
        assert!(value.age.is_none());
        assert!(value.profile_url.is_none());
        {
            let db = core.connection().unwrap();
            db.execute(
                "UPDATE listings SET current_status='ACTIVE',
                 current_url='https://m.ladies.de/sex-anzeigen/42' WHERE listing_id=?1",
                [listing],
            )
            .unwrap();
            db.execute(
                "UPDATE persons SET first_seen_at='2000-01-01' WHERE person_id=?1",
                [person],
            )
            .unwrap();
        }
        let value = profile_identity(&core, listing).unwrap();
        assert_eq!(value.status, "ACTIVE");
        assert!(!value.person_new);
        assert_eq!(
            value.profile_url.as_deref(),
            Some("https://www.ladies.de/sex-anzeigen/42")
        );
    }

    #[test]
    fn listing_new_uses_snapshot_publication_and_not_first_local_sighting() {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(None).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "43".into(), None)
            .unwrap();
        {
            let db = core.connection().unwrap();
            db.execute(
                "INSERT INTO listing_snapshots (listing_id,fingerprint,published_at)
                 VALUES (?1,'fixture','2000-01-01T00:00:00Z')",
                [listing],
            )
            .unwrap();
            let snapshot = db.last_insert_rowid();
            db.execute(
                "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
                rusqlite::params![snapshot, listing],
            )
            .unwrap();
        }
        assert!(!profile_identity(&core, listing).unwrap().listing_new);
        {
            let db = core.connection().unwrap();
            db.execute(
                "UPDATE listing_snapshots SET published_at=datetime('now','+1 day')
                 WHERE listing_id=?1",
                [listing],
            )
            .unwrap();
        }
        assert!(!profile_identity(&core, listing).unwrap().listing_new);
        {
            let db = core.connection().unwrap();
            db.execute(
                "UPDATE listing_snapshots SET published_at=datetime('now','-36 hours')
                 WHERE listing_id=?1",
                [listing],
            )
            .unwrap();
        }
        assert!(!profile_identity(&core, listing).unwrap().listing_new);
    }
}
