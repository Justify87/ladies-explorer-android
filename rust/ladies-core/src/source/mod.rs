mod filter;
mod network;
mod parse;
mod storage;

use crate::{CoreError, LadiesCore, diagnostics};

pub(crate) struct SourceSession(network::SourceHttp);

impl SourceSession {
    pub(crate) fn new() -> Result<Self, CoreError> {
        network::SourceHttp::new().map(Self)
    }
}

#[derive(Debug, Clone, uniffi::Record)]
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
pub struct SearchResponse {
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

pub(crate) fn search(
    core: &LadiesCore,
    request: SearchRequest,
) -> Result<SearchResponse, CoreError> {
    diagnostics::event("SEARCH", format!(
        "Search requested city={} radius={} category={} max_pages={}",
        request.city, request.radius_km, request.category, request.max_pages
    ));
    filter::validate_request(&request)?;
    let downloaded = network::fetch_search(&core.source_session.0, &request)
        .map_err(|error| {
            diagnostics::event("SEARCH", format!("Search fetch failed: {error}"));
            error
        })?;
    let saved = storage::save_search(core, &request, downloaded);
    match &saved {
        Ok(response) => diagnostics::event("STORAGE", format!(
            "Search saved run={} cards={}",
            response.search_run_id, response.profiles.len()
        )),
        Err(error) => diagnostics::event("STORAGE", format!("Search save failed: {error}")),
    }
    saved
}

pub(crate) fn load_profile(
    core: &LadiesCore,
    listing_id: i64,
) -> Result<ProfileResponse, CoreError> {
    diagnostics::event("PROFILE", format!("Profile requested listing={listing_id}"));
    let (url, advert_id, name, city, category) = storage::listing_target(core, listing_id)?;
    let html = core.source_session.0.get_profile(&url)?;
    let fallback = Card {
        advert_id,
        name,
        city,
        category,
        profile_url: url.clone(),
        thumbnail_url: None,
        has_video: false,
        is_premium: false,
        has_ai_content: false,
        has_18plus: false,
        has_stars: false,
    };
    let profile = parse::parse_profile(&html, &fallback).map_err(|error| {
        diagnostics::html("profile_parse_failed", &html);
        diagnostics::event("PROFILE", format!("Profile parse failed listing={listing_id}: {error}"));
        error
    })?;
    diagnostics::event("PROFILE", format!(
        "Profile parsed listing={} name={} media={} services_bytes={}",
        listing_id, profile.name, profile.media.len(), profile.services_json.len()
    ));
    let result = storage::save_profile(core, listing_id, &url, profile);
    match &result {
        Ok(saved) => diagnostics::event("STORAGE", format!(
            "Profile saved listing={} snapshot={} new_snapshot={}",
            listing_id, saved.snapshot_id, saved.new_snapshot
        )),
        Err(error) => diagnostics::event("STORAGE", format!("Profile save failed: {error}")),
    }
    result
}

#[cfg(test)]
impl SearchRequest {
    fn for_test() -> Self {
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
