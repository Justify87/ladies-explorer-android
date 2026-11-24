//! Coarse German locations; never geocode a full hidden address or call providers without user initiation.
use std::collections::{BTreeMap, HashMap, HashSet};
use std::io::Read;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use reqwest::blocking::Client;
use rusqlite::params;
use serde::{Deserialize, Serialize};

use crate::http_capture::{self, CaptureMode, HttpObservation};
use crate::{CoreError, LadiesCore, RequestControl};

const NEGATIVE_TTL_SECS: u64 = 7 * 24 * 60 * 60;
const MAX_GEOCODE_REQUESTS: u32 = 15;
const CITY_INTERVAL: Duration = Duration::from_millis(1100);

/// Hold for the entire resolve operation to preserve fairness and provider pacing.
#[derive(Default)]
pub(crate) struct GeocodeState {
    last_city_request: Option<Instant>,
    last_attempt: HashMap<String, u64>,
    sequence: u64,
}

impl GeocodeState {
    fn ordered(
        &mut self,
        pending: BTreeMap<String, (String, bool)>,
    ) -> Vec<(String, (String, bool))> {
        self.last_attempt.retain(|key, _| pending.contains_key(key));
        let mut pending: Vec<_> = pending.into_iter().collect();
        pending.sort_by_key(|(key, (_, postal))| {
            (self.last_attempt.get(key).copied().unwrap_or(0), !*postal)
        });
        pending
    }

    fn attempted(&mut self, key: &str) {
        self.sequence += 1;
        self.last_attempt.insert(key.to_owned(), self.sequence);
    }

    fn wait_for_city(&mut self, control: &RequestControl) -> Result<(), CoreError> {
        if let Some(previous) = self.last_city_request {
            while previous.elapsed() < CITY_INTERVAL {
                control.check()?;
                std::thread::sleep(
                    CITY_INTERVAL
                        .saturating_sub(previous.elapsed())
                        .min(Duration::from_millis(50)),
                );
            }
        }
        control.check()?;
        self.last_city_request = Some(Instant::now());
        Ok(())
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MapProfile {
    pub listing_id: i64,
    pub person_id: i64,
    pub name: String,
    pub city: String,
    pub location_label: String,
    pub latitude: f64,
    pub longitude: f64,
    pub precision: String,
    pub favorite: bool,
    pub thumbnail_url: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MapOverview {
    pub profiles: Vec<MapProfile>,
    pub total_profiles: u32,
    pub unlocated_profiles: u32,
    pub pending_locations: u32,
}

#[derive(Debug, Clone)]
struct Candidate {
    listing_id: i64,
    person_id: i64,
    name: String,
    city: String,
    postal: Option<String>,
    favorite: bool,
    thumbnail_url: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct CachedLocation {
    status: String,
    latitude: Option<f64>,
    longitude: Option<f64>,
    precision: Option<String>,
    updated_at: u64,
}

impl CachedLocation {
    fn valid_point(&self) -> Option<(f64, f64)> {
        if self.status != "RESOLVED" {
            return None;
        }
        let (latitude, longitude) = (self.latitude?, self.longitude?);
        // The source and the geocoder are explicitly restricted to Germany.
        (latitude.is_finite()
            && longitude.is_finite()
            && (47.0..=56.0).contains(&latitude)
            && (5.0..=16.0).contains(&longitude))
        .then_some((latitude, longitude))
    }

    fn negative_fresh(&self) -> bool {
        self.status == "NOT_FOUND" && now().saturating_sub(self.updated_at) < NEGATIVE_TTL_SECS
    }
}

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn postal_from(value: &str) -> Option<String> {
    // Boundary checks prevent treating an arbitrary longer number as a German postcode.
    value
        .split(|ch: char| !ch.is_ascii_digit())
        .find(|digits| digits.len() == 5)
        .map(str::to_owned)
}

fn cache_keys(candidate: &Candidate) -> (Option<String>, Option<String>) {
    let postal = candidate
        .postal
        .as_ref()
        .map(|p| format!("map:postal:DE:{p}"));
    let city = candidate
        .city
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase();
    let city = (!city.is_empty()).then(|| format!("map:city:DE:{city}"));
    (postal, city)
}

fn candidates(
    core: &LadiesCore,
    listing_ids: &[i64],
    run_id: Option<i64>,
) -> Result<Vec<Candidate>, CoreError> {
    let ids = serde_json::to_string(listing_ids).map_err(|e| CoreError::Storage(e.to_string()))?;
    let db = core.connection()?;
    let mut statement = db.prepare(
        "SELECT l.listing_id,l.person_id,
                CASE WHEN ?2 IS NOT NULL THEN COALESCE(r.name,'')
                     ELSE COALESCE(NULLIF(s.name,''),NULLIF(r.name,''),NULLIF(p.display_name,''),'Inserat') END,
                CASE WHEN ?2 IS NOT NULL THEN COALESCE(r.city,'')
                     ELSE COALESCE(NULLIF(s.city,''),NULLIF(r.city,''),'') END,
                s.postal_code,s.hidden_location,u.favorite,
                CASE WHEN ?2 IS NOT NULL THEN r.thumbnail_url ELSE COALESCE(
                    (SELECT m.remote_url FROM snapshot_media m
                     WHERE m.snapshot_id=l.current_snapshot_id AND m.kind='IMAGE'
                     ORDER BY CASE WHEN m.role='PRIMARY' THEN 0 ELSE 1 END,m.position LIMIT 1),
                    r.thumbnail_url) END
         FROM json_each(?1) requested
         JOIN listings l ON l.listing_id=requested.value
         JOIN persons p ON p.person_id=l.person_id
         JOIN person_user_state u ON u.person_id=l.person_id
         LEFT JOIN listing_snapshots s ON s.snapshot_id=l.current_snapshot_id AND s.listing_id=l.listing_id
         LEFT JOIN search_results r ON r.search_result_id=(
             SELECT sr.search_result_id FROM search_results sr
             WHERE sr.listing_id=l.listing_id AND (?2 IS NULL OR sr.search_run_id=?2)
             ORDER BY sr.search_run_id DESC,sr.search_result_id DESC LIMIT 1)
         WHERE (?2 IS NULL OR r.search_result_id IS NOT NULL)
         ORDER BY CAST(requested.key AS INTEGER)",
    )?;
    let rows = statement.query_map(params![ids, run_id], |row| {
        let explicit_postal: Option<String> = row.get(4)?;
        let hidden: Option<String> = row.get(5)?;
        let postal = explicit_postal
            .as_deref()
            .and_then(postal_from)
            .or_else(|| hidden.as_deref().and_then(postal_from));
        Ok(Candidate {
            listing_id: row.get(0)?,
            person_id: row.get(1)?,
            name: row.get(2)?,
            city: row.get(3)?,
            postal,
            favorite: row.get::<_, i64>(6)? != 0,
            thumbnail_url: row.get(7)?,
        })
    })?;
    let people = rows.collect::<rusqlite::Result<Vec<_>>>()?;
    if people.len() != listing_ids.len() {
        return Err(CoreError::InvalidInput(
            "Kartenmitgliedschaft hat sich geändert".into(),
        ));
    }
    Ok(people)
}

fn read_cache(core: &LadiesCore) -> Result<HashMap<String, CachedLocation>, CoreError> {
    let db = core.connection()?;
    let mut statement =
        db.prepare("SELECT key,value_json FROM app_settings WHERE key LIKE 'map:%'")?;
    let rows = statement.query_map([], |row| {
        Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
    })?;
    Ok(rows
        .collect::<rusqlite::Result<Vec<_>>>()?
        .into_iter()
        .filter_map(|(key, value)| {
            serde_json::from_str::<CachedLocation>(&value)
                .ok()
                .map(|record| (key, record))
        })
        .collect())
}

fn save_cache(core: &LadiesCore, key: &str, entry: &CachedLocation) -> Result<(), CoreError> {
    let json =
        serde_json::to_string(entry).map_err(|error| CoreError::Storage(error.to_string()))?;
    core.connection()?.execute(
        "INSERT INTO app_settings(key,value_json)
         VALUES (?1,?2)
         ON CONFLICT(key) DO UPDATE SET value_json=excluded.value_json,
             updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')",
        params![key, json],
    )?;
    Ok(())
}

type LocationChoice<'a> = (
    Option<(&'a CachedLocation, String)>,
    Option<(String, String, bool)>,
);

fn chosen_location<'a>(
    candidate: &Candidate,
    cache: &'a HashMap<String, CachedLocation>,
) -> LocationChoice<'a> {
    let (postal_key, city_key) = cache_keys(candidate);
    let mut pending = None;
    if let Some(key) = postal_key {
        let cached = cache.get(&key);
        if let Some(value) = cached.filter(|value| value.valid_point().is_some()) {
            return (Some((value, key)), None);
        }
        if !cached.is_some_and(CachedLocation::negative_fresh) {
            pending = candidate.postal.as_ref().map(|p| (key, p.clone(), true));
        }
    }
    if let Some(key) = city_key {
        let cached = cache.get(&key);
        if let Some(value) = cached.filter(|value| value.valid_point().is_some()) {
            // Keep the offline city marker while a finer postal fix is pending.
            return (Some((value, key)), pending);
        }
        if pending.is_none() && !cached.is_some_and(CachedLocation::negative_fresh) {
            pending = Some((key, candidate.city.clone(), false));
        }
    }
    (None, pending)
}

fn build_overview(people: Vec<Candidate>, cache: &HashMap<String, CachedLocation>) -> MapOverview {
    let total_profiles = people.len().min(u32::MAX as usize) as u32;
    let mut profiles = Vec::new();
    let mut pending = HashSet::new();
    for person in people {
        let (location, next) = chosen_location(&person, cache);
        if let Some((cached, _key)) = location {
            if let Some((latitude, longitude)) = cached.valid_point() {
                let location_label = match (&person.postal, cached.precision.as_deref()) {
                    (Some(postal), Some("POSTAL")) if !person.city.trim().is_empty() => {
                        format!("{postal} {}", person.city)
                    }
                    (Some(postal), Some("POSTAL")) => postal.clone(),
                    _ => person.city.clone(),
                };
                profiles.push(MapProfile {
                    listing_id: person.listing_id,
                    person_id: person.person_id,
                    name: person.name,
                    city: person.city,
                    location_label,
                    latitude,
                    longitude,
                    precision: cached.precision.clone().unwrap_or_else(|| "CITY".into()),
                    favorite: person.favorite,
                    thumbnail_url: person.thumbnail_url,
                });
            }
        }
        if let Some((key, _, _)) = next {
            pending.insert(key);
        }
    }
    MapOverview {
        unlocated_profiles: total_profiles.saturating_sub(profiles.len() as u32),
        total_profiles,
        profiles,
        pending_locations: pending.len() as u32,
    }
}

pub(crate) fn overview(
    core: &LadiesCore,
    listing_ids: &[i64],
    run_id: Option<i64>,
) -> Result<MapOverview, CoreError> {
    Ok(build_overview(
        candidates(core, listing_ids, run_id)?,
        &read_cache(core)?,
    ))
}

#[derive(Debug, PartialEq)]
enum Lookup {
    Resolved(f64, f64, &'static str),
    NotFound,
}

#[derive(Deserialize)]
struct PostalResponse {
    places: Vec<PostalPlace>,
}

#[derive(Deserialize)]
struct PostalPlace {
    latitude: String,
    longitude: String,
}

#[derive(Deserialize)]
struct CityPlace {
    lat: String,
    lon: String,
}

fn provider_error(error: impl std::fmt::Display) -> CoreError {
    CoreError::Source(format!("Invalid geocoding response: {error}"))
}

fn checked_coordinates(lat: &str, lon: &str) -> Result<(f64, f64), CoreError> {
    let lat: f64 = lat.parse().map_err(provider_error)?;
    let lon: f64 = lon.parse().map_err(provider_error)?;
    if !(47.0..=56.0).contains(&lat) || !(5.0..=16.0).contains(&lon) {
        return Err(provider_error("coordinates outside supported region"));
    }
    Ok((lat, lon))
}

fn parse_geocoding(body: &str, postal: bool) -> Result<Lookup, CoreError> {
    if postal {
        let payload: PostalResponse = serde_json::from_str(body).map_err(provider_error)?;
        if payload.places.is_empty() {
            return Ok(Lookup::NotFound);
        }
        let mut latitude = 0.0;
        let mut longitude = 0.0;
        for place in &payload.places {
            let (lat, lon) = checked_coordinates(&place.latitude, &place.longitude)?;
            latitude += lat;
            longitude += lon;
        }
        let count = payload.places.len() as f64;
        Ok(Lookup::Resolved(
            latitude / count,
            longitude / count,
            "POSTAL",
        ))
    } else {
        let payload: Vec<CityPlace> = serde_json::from_str(body).map_err(provider_error)?;
        let Some(place) = payload.first() else {
            return Ok(Lookup::NotFound);
        };
        let (lat, lon) = checked_coordinates(&place.lat, &place.lon)?;
        Ok(Lookup::Resolved(lat, lon, "CITY"))
    }
}

fn geocode(
    core: &LadiesCore,
    client: &Client,
    value: &str,
    postal: bool,
) -> Result<Lookup, CoreError> {
    let request = if postal {
        client.get(format!("https://api.zippopotam.us/DE/{value}"))
    } else {
        client
            .get("https://nominatim.openstreetmap.org/search")
            .query(&[
                ("q", format!("{value}, Germany")),
                ("format", "jsonv2".into()),
                ("limit", "1".into()),
                ("countrycodes", "de".into()),
            ])
    }
    .build()
    .map_err(|error| CoreError::Source(format!("Geocoding request failed: {error}")))?;

    execute_geocoding(core, client, request, postal)
}

fn execute_geocoding(
    core: &LadiesCore,
    client: &Client,
    request: reqwest::blocking::Request,
    postal: bool,
) -> Result<Lookup, CoreError> {
    let started = Instant::now();
    let (mode, epoch) = core.recorder.mode_epoch();
    let mut observation = (mode != CaptureMode::Off).then(|| HttpObservation {
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
        is_media: false,
        error: None,
        body_omitted: None,
    });
    let result = (|| {
        let sent = client.execute(request);
        if let Some(obs) = observation.as_mut() {
            obs.headers_elapsed_ms = started.elapsed().as_millis() as u64;
        }
        let mut response = sent.map_err(|error| {
            CoreError::Source(format!("Geocoding network request failed: {error}"))
        })?;
        let status = response.status();
        if let Some(obs) = observation.as_mut() {
            obs.status = Some(status.as_u16());
            obs.http_version = format!("{:?}", response.version());
            obs.final_url = response.url().to_string();
            obs.response_headers = http_capture::headers(response.headers());
            obs.mime = response
                .headers()
                .get(reqwest::header::CONTENT_TYPE)
                .and_then(|header| header.to_str().ok())
                .map(ToOwned::to_owned);
            obs.response_size = response.content_length();
        }
        let body_started = Instant::now();
        let mut bytes = Vec::new();
        let read = response
            .by_ref()
            .take(http_capture::MAX_BODY_BYTES as u64 + 1)
            .read_to_end(&mut bytes);
        if let Some(obs) = observation.as_mut() {
            obs.body_elapsed_ms = body_started.elapsed().as_millis() as u64;
            obs.response_size = Some(bytes.len() as u64);
        }
        read.map_err(provider_error)?;
        if bytes.len() > http_capture::MAX_BODY_BYTES {
            if let Some(obs) = observation.as_mut() {
                obs.body_omitted = Some("body_over_2_mib".into());
            }
            return Err(provider_error("response size limit exceeded"));
        }
        let body = String::from_utf8(bytes).map_err(provider_error)?;
        if let Some(obs) = observation.as_mut()
            && mode == CaptureMode::Text
        {
            obs.body = Some(body.clone());
        }
        // Postal 404 means not found; city-search 404 is an HTTP failure.
        if postal && status == reqwest::StatusCode::NOT_FOUND {
            return Ok(Lookup::NotFound);
        }
        if !status.is_success() {
            return Err(CoreError::Source(format!(
                "Geocoding provider returned HTTP {status}"
            )));
        }
        parse_geocoding(&body, postal)
    })();
    if let Some(mut obs) = observation {
        if let Err(error) = &result {
            obs.error = Some(error.to_string());
        }
        core.recorder.record_for_epoch(obs, epoch);
    }
    result
}

/// Serialize and pace explicit geocoding; rotate failures behind untried locations.
pub(crate) fn resolve(
    core: &LadiesCore,
    listing_ids: &[i64],
    run_id: Option<i64>,
    max_requests: u32,
    control: &Arc<RequestControl>,
) -> Result<u32, CoreError> {
    if !(1..=MAX_GEOCODE_REQUESTS).contains(&max_requests) {
        return Err(CoreError::InvalidInput(
            "Geocode batch must contain 1–15 requests".into(),
        ));
    }
    let mut scheduler = core
        .geocoding
        .try_lock()
        .map_err(|_| CoreError::Source("Standortauflösung läuft bereits".into()))?;
    control.check()?;
    let people = candidates(core, listing_ids, run_id)?;
    let cache = read_cache(core)?;
    let mut pending = BTreeMap::new();
    for person in &people {
        let (_, candidate) = chosen_location(person, &cache);
        if let Some((key, value, postal)) = candidate {
            pending.entry(key).or_insert((value, postal));
        }
    }
    let pending = scheduler.ordered(pending);
    let client = Client::builder()
        .timeout(Duration::from_secs(9))
        .user_agent("LadiesExplorerAndroid/0.1 (github.com/Justify87/ladies-explorer-android)")
        .build()
        .map_err(|error| CoreError::Source(error.to_string()))?;
    let mut failed = 0u32;
    for (key, (value, postal)) in pending.into_iter().take(max_requests as usize) {
        control.check()?;
        if !postal {
            scheduler.wait_for_city(control)?;
        }
        scheduler.attempted(&key);
        let response = geocode(core, &client, &value, postal);
        control.check()?;
        let entry = match response {
            Ok(Lookup::Resolved(lat, lon, precision)) => CachedLocation {
                status: "RESOLVED".into(),
                latitude: Some(lat),
                longitude: Some(lon),
                precision: Some(precision.into()),
                updated_at: now(),
            },
            Ok(Lookup::NotFound) => CachedLocation {
                status: "NOT_FOUND".into(),
                latitude: None,
                longitude: None,
                precision: None,
                updated_at: now(),
            },
            Err(error) => {
                core.diagnostics.error("MAP", error.to_string());
                failed += 1;
                continue;
            }
        };
        save_cache(core, &key, &entry)?;
        scheduler.last_attempt.remove(&key);
    }
    Ok(failed)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn seed() -> LadiesCore {
        let core = Arc::try_unwrap(LadiesCore::new(":memory:".into()).unwrap())
            .ok()
            .unwrap();
        let first = core.create_person(Some("Ada".into())).unwrap();
        let second = core.create_person(Some("Bea".into())).unwrap();
        core.add_listing(first, None, "LADIES".into(), "one".into(), None)
            .unwrap();
        let listing = core
            .add_listing(first, None, "LADIES".into(), "two".into(), None)
            .unwrap();
        let second_listing = core
            .add_listing(second, None, "LADIES".into(), "three".into(), None)
            .unwrap();
        let db = core.connection().unwrap();
        db.execute(
            "INSERT INTO listing_snapshots(listing_id,fingerprint,city,postal_code,hidden_location)
             VALUES (?1,'snap1','Leipzig','04229','Private Str. 99')",
            [listing],
        )
        .unwrap();
        let snap = db.last_insert_rowid();
        db.execute(
            "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
            params![snap, listing],
        )
        .unwrap();
        db.execute(
            "INSERT INTO listing_snapshots(listing_id,fingerprint,city,postal_code)
             VALUES (?1,'snap2','Leipzig','04229')",
            [second_listing],
        )
        .unwrap();
        let snap = db.last_insert_rowid();
        db.execute(
            "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
            params![snap, second_listing],
        )
        .unwrap();
        drop(db);
        core
    }

    fn member_ids(core: &LadiesCore, favorites_only: bool) -> Vec<i64> {
        crate::discovery::page(core, favorites_only, 100, None, &[])
            .unwrap()
            .cards
            .into_iter()
            .map(|card| card.listing_id)
            .collect()
    }

    #[test]
    fn exact_run_listing_is_mapped_without_substituting_a_person_representative() {
        let core = seed();
        {
            let db = core.connection().unwrap();
            db.execute("INSERT INTO search_runs (search_run_id) VALUES (1),(2)", [])
                .unwrap();
            db.execute(
                "INSERT INTO search_results (search_run_id,listing_id,position,name,city)
                 VALUES (1,1,1,'Run A','Leipzig'),(2,3,1,'Outside','Leipzig')",
                [],
            )
            .unwrap();
        }
        save_cache(
            &core,
            "map:city:DE:leipzig",
            &CachedLocation {
                status: "RESOLVED".into(),
                latitude: Some(51.34),
                longitude: Some(12.37),
                precision: Some("CITY".into()),
                updated_at: now(),
            },
        )
        .unwrap();
        let view = overview(&core, &[1], Some(1)).unwrap();
        assert_eq!(view.total_profiles, 1);
        assert_eq!(view.profiles[0].listing_id, 1);
        assert_eq!(view.profiles[0].name, "Run A");
        assert!(overview(&core, &[3], Some(1)).is_err());
        assert_eq!(overview(&core, &[], Some(1)).unwrap().total_profiles, 0);
    }

    #[test]
    fn no_fake_coordinates_and_deduplicated_people() {
        let core = seed();
        let view = overview(&core, &member_ids(&core, false), None).unwrap();
        assert_eq!(view.total_profiles, 2);
        assert!(view.profiles.is_empty());
        assert_eq!(view.pending_locations, 1);
    }

    #[test]
    fn cached_postal_location_is_reused_by_both_people() {
        let core = seed();
        save_cache(
            &core,
            "map:postal:DE:04229",
            &CachedLocation {
                status: "RESOLVED".into(),
                latitude: Some(51.325),
                longitude: Some(12.335),
                precision: Some("POSTAL".into()),
                updated_at: now(),
            },
        )
        .unwrap();
        let view = overview(&core, &member_ids(&core, false), None).unwrap();
        assert_eq!(view.profiles.len(), 2);
        assert!(view.profiles.iter().all(|p| p.precision == "POSTAL"));
        assert_eq!(view.pending_locations, 0);
        assert_eq!(view.unlocated_profiles, 0);
        core.set_favorite(1, true).unwrap();
        let view = overview(&core, &member_ids(&core, true), None).unwrap();
        assert_eq!(view.total_profiles, 1);
        assert!(view.profiles[0].favorite);
    }

    #[test]
    fn negative_postal_uses_city_fallback_and_invalid_cache_never_maps() {
        let core = seed();
        save_cache(
            &core,
            "map:postal:DE:04229",
            &CachedLocation {
                status: "NOT_FOUND".into(),
                latitude: None,
                longitude: None,
                precision: None,
                updated_at: now(),
            },
        )
        .unwrap();
        let view = overview(&core, &member_ids(&core, false), None).unwrap();
        assert_eq!(view.pending_locations, 1);
        save_cache(
            &core,
            "map:city:DE:leipzig",
            &CachedLocation {
                status: "RESOLVED".into(),
                latitude: Some(0.0),
                longitude: Some(0.0),
                precision: Some("CITY".into()),
                updated_at: now(),
            },
        )
        .unwrap();
        assert_eq!(
            overview(&core, &member_ids(&core, false), None)
                .unwrap()
                .profiles
                .len(),
            0
        );
        save_cache(
            &core,
            "map:city:DE:leipzig",
            &CachedLocation {
                status: "RESOLVED".into(),
                latitude: Some(51.34),
                longitude: Some(12.37),
                precision: Some("CITY".into()),
                updated_at: now(),
            },
        )
        .unwrap();
        let view = overview(&core, &member_ids(&core, false), None).unwrap();
        assert_eq!(view.profiles.len(), 2);
        assert!(view.profiles.iter().all(|p| p.precision == "CITY"));
    }

    #[test]
    fn provider_responses_distinguish_empty_results_from_malformed_data() {
        assert_eq!(parse_geocoding("[]", false).unwrap(), Lookup::NotFound);
        assert_eq!(
            parse_geocoding(r#"{"places":[]}"#, true).unwrap(),
            Lookup::NotFound
        );
        for body in [
            "{}",
            "null",
            r#"[{"lat":"51"}]"#,
            r#"[{"lat":"NaN","lon":"12"}]"#,
        ] {
            assert!(parse_geocoding(body, false).is_err());
        }
        assert!(parse_geocoding(r#"{"error":"maintenance"}"#, true).is_err());
        assert!(parse_geocoding(r#"{"places":[{"latitude":"0","longitude":"0"}]}"#, true).is_err());
        assert_eq!(
            parse_geocoding(r#"[{"lat":"51.3","lon":"12.3"}]"#, false).unwrap(),
            Lookup::Resolved(51.3, 12.3, "CITY")
        );
    }

    #[test]
    fn fair_scheduler_moves_failed_requests_behind_untried_locations() {
        let mut scheduler = GeocodeState::default();
        let pending: BTreeMap<_, _> = (0..20)
            .map(|i| (format!("postal:{i:02}"), (i.to_string(), true)))
            .collect();
        let first = scheduler.ordered(pending.clone());
        for (key, _) in first.iter().take(15) {
            scheduler.attempted(key);
        }
        let next = scheduler.ordered(pending);
        assert_eq!(next[0].0, "postal:15");
        assert_eq!(next[5].0, "postal:00");
    }

    #[test]
    fn city_fallback_remains_visible_while_postcode_is_pending() {
        let core = seed();
        save_cache(
            &core,
            "map:city:DE:leipzig",
            &CachedLocation {
                status: "RESOLVED".into(),
                latitude: Some(51.34),
                longitude: Some(12.37),
                precision: Some("CITY".into()),
                updated_at: now(),
            },
        )
        .unwrap();
        let view = overview(&core, &member_ids(&core, false), None).unwrap();
        assert_eq!(view.profiles.len(), 2);
        assert_eq!(view.pending_locations, 1);
        assert!(view.profiles.iter().all(|p| p.precision == "CITY"));
    }

    #[test]
    fn overlapping_resolution_is_rejected_without_network_access() {
        let core = seed();
        let _lease = core.geocoding.lock().unwrap();
        assert!(matches!(
            resolve(
                &core,
                &member_ids(&core, false),
                None,
                15,
                &RequestControl::new()
            ),
            Err(CoreError::Source(reason)) if reason == "Standortauflösung läuft bereits"
        ));
    }

    #[test]
    fn geocoder_records_real_http_response_in_existing_har_session() {
        use std::io::{BufRead, BufReader, Write};
        use std::net::TcpListener;

        let root = tempfile::tempdir().unwrap();
        let database = root.path().join("map.sqlite");
        let core = LadiesCore::new(database.to_string_lossy().into_owned()).unwrap();
        core.recorder.set_mode(CaptureMode::Text);
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let worker = std::thread::spawn(move || {
            listener.set_nonblocking(true).unwrap();
            let deadline = Instant::now() + Duration::from_secs(5);
            let mut stream = loop {
                match listener.accept() {
                    Ok((stream, _)) => break stream,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        assert!(
                            Instant::now() < deadline,
                            "local HTTP client did not connect"
                        );
                        std::thread::sleep(Duration::from_millis(10));
                    }
                    Err(error) => panic!("{error}"),
                }
            };
            stream
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            let mut reader = BufReader::new(&mut stream);
            loop {
                let mut line = String::new();
                if reader.read_line(&mut line).unwrap() == 0 || line == "\r\n" {
                    break;
                }
            }
            drop(reader);
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]")
                .unwrap();
        });
        let client = Client::builder()
            .no_proxy()
            .timeout(Duration::from_secs(3))
            .build()
            .unwrap();
        let url = format!("http://{address}/search");
        let request = client.get(&url).build().unwrap();
        let result = execute_geocoding(&core, &client, request, false);
        worker.join().unwrap();
        assert_eq!(result.unwrap(), Lookup::NotFound);
        assert_eq!(core.recorder.status().entries, 1);
        let export = core.recorder.export(root.path()).unwrap();
        let har: serde_json::Value =
            serde_json::from_slice(&std::fs::read(export).unwrap()).unwrap();
        let entry = &har["log"]["entries"][0];
        assert_eq!(entry["request"]["url"], url);
        assert_eq!(entry["response"]["status"], 200);
        assert_eq!(entry["response"]["content"]["text"], "[]");
    }

    #[test]
    fn cancelled_city_request_does_not_wait_or_claim_a_new_slot() {
        let previous = Instant::now();
        let mut state = GeocodeState {
            last_city_request: Some(previous),
            ..Default::default()
        };
        let control = RequestControl::new();
        control.cancel();
        assert!(matches!(
            state.wait_for_city(&control),
            Err(CoreError::Source(reason)) if reason == "Operation cancelled"
        ));
        assert_eq!(state.last_city_request, Some(previous));
    }

    #[test]
    fn postal_parser_accepts_only_five_digit_tokens() {
        assert_eq!(postal_from("in 04229 Leipzig"), Some("04229".into()));
        assert_eq!(postal_from("123456789"), None);
        assert_eq!(postal_from("Keine Adresse"), None);
    }
}
