//! Coarse, app-private map locations. Never geocode a full hidden address.
//! Source: ZIPPOPOTAM for German postcodes, Nominatim for city fallback.
//! Both services are contacted only after an explicit user request.
use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use reqwest::blocking::Client;
use rusqlite::{OptionalExtension, params};
use serde::{Deserialize, Serialize};

use crate::{CoreError, LadiesCore, RequestControl};

const NEGATIVE_TTL_SECS: u64 = 7 * 24 * 60 * 60;
const MAX_GEOCODE_REQUESTS: u32 = 15;

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
    pub failed_lookups: u32,
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

fn candidates(core: &LadiesCore, favorites_only: bool) -> Result<Vec<Candidate>, CoreError> {
    let db = core.connection()?;
    let mut statement = db.prepare(
        "WITH ranked AS (
            SELECT listing_id,person_id,
                   ROW_NUMBER() OVER (PARTITION BY person_id
                     ORDER BY last_seen_at DESC, listing_id DESC) AS rn
            FROM listings
         )
         SELECT l.listing_id,l.person_id,
                COALESCE(NULLIF(s.name,''),NULLIF(r.name,''),NULLIF(p.display_name,''),'Inserat'),
                COALESCE(NULLIF(s.city,''),NULLIF(r.city,''),''),
                s.postal_code,s.hidden_location,u.favorite,
                COALESCE(
                    (SELECT m.remote_url FROM snapshot_media m
                     WHERE m.snapshot_id=l.current_snapshot_id AND m.kind='IMAGE'
                     ORDER BY CASE WHEN m.role='PRIMARY' THEN 0 ELSE 1 END,m.position LIMIT 1),
                    r.thumbnail_url)
         FROM ranked k
         JOIN listings l ON l.listing_id=k.listing_id AND k.rn=1
         JOIN persons p ON p.person_id=l.person_id
         JOIN person_user_state u ON u.person_id=l.person_id
         LEFT JOIN listing_snapshots s ON s.snapshot_id=l.current_snapshot_id
         LEFT JOIN search_results r ON r.search_result_id=(
             SELECT sr.search_result_id FROM search_results sr
             WHERE sr.listing_id=l.listing_id ORDER BY sr.search_run_id DESC LIMIT 1)
         WHERE (?1=0 OR u.favorite=1)
         ORDER BY l.last_seen_at DESC,l.listing_id DESC",
    )?;
    let rows = statement.query_map([favorites_only], |row| {
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
    rows.collect::<rusqlite::Result<Vec<_>>>()
        .map_err(Into::into)
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

fn chosen_location<'a>(
    candidate: &Candidate,
    cache: &'a HashMap<String, CachedLocation>,
) -> (
    Option<(&'a CachedLocation, String)>,
    Option<(String, String, bool)>,
) {
    let (postal_key, city_key) = cache_keys(candidate);
    if let Some(key) = postal_key {
        if let Some(value) = cache.get(&key) {
            if value.valid_point().is_some() {
                return (Some((value, key)), None);
            }
            if !value.negative_fresh() {
                return (
                    None,
                    candidate.postal.as_ref().map(|p| (key, p.clone(), true)),
                );
            }
        } else {
            return (
                None,
                candidate.postal.as_ref().map(|p| (key, p.clone(), true)),
            );
        }
    }
    if let Some(key) = city_key {
        if let Some(value) = cache.get(&key) {
            if value.valid_point().is_some() {
                return (Some((value, key)), None);
            }
            if value.negative_fresh() {
                return (None, None);
            }
        }
        return (None, Some((key, candidate.city.clone(), false)));
    }
    (None, None)
}

fn build_overview(
    people: Vec<Candidate>,
    cache: &HashMap<String, CachedLocation>,
    failed_lookups: u32,
) -> MapOverview {
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
        } else if let Some((key, _, _)) = next {
            pending.insert(key);
        }
    }
    MapOverview {
        unlocated_profiles: total_profiles.saturating_sub(profiles.len() as u32),
        total_profiles,
        profiles,
        pending_locations: pending.len() as u32,
        failed_lookups,
    }
}

pub(crate) fn overview(core: &LadiesCore, favorites_only: bool) -> Result<MapOverview, CoreError> {
    Ok(build_overview(
        candidates(core, favorites_only)?,
        &read_cache(core)?,
        0,
    ))
}

enum Lookup {
    Resolved(f64, f64, &'static str),
    NotFound,
}

fn geocode(client: &Client, value: &str, postal: bool) -> Result<Lookup, CoreError> {
    let response = if postal {
        client
            .get(format!("https://api.zippopotam.us/DE/{value}"))
            .send()
    } else {
        client
            .get("https://nominatim.openstreetmap.org/search")
            .query(&[
                ("q", format!("{value}, Germany")),
                ("format", "jsonv2".into()),
                ("limit", "1".into()),
                ("countrycodes", "de".into()),
            ])
            .send()
    }
    .map_err(|error| CoreError::Source(format!("Geocoding network request failed: {error}")))?;
    if response.status() == reqwest::StatusCode::NOT_FOUND {
        return Ok(Lookup::NotFound);
    }
    let response = response.error_for_status().map_err(|error| {
        CoreError::Source(format!("Geocoding provider rejected the request: {error}"))
    })?;
    let payload: serde_json::Value = response
        .json()
        .map_err(|error| CoreError::Source(format!("Invalid geocoding response: {error}")))?;
    let mut coordinates = Vec::new();
    if postal {
        for place in payload["places"].as_array().into_iter().flatten() {
            if let (Some(lat), Some(lon)) = (
                place["latitude"]
                    .as_str()
                    .and_then(|s| s.parse::<f64>().ok()),
                place["longitude"]
                    .as_str()
                    .and_then(|s| s.parse::<f64>().ok()),
            ) {
                coordinates.push((lat, lon));
            }
        }
    } else if let Some(first) = payload.as_array().and_then(|items| items.first()) {
        if let (Some(lat), Some(lon)) = (
            first["lat"].as_str().and_then(|s| s.parse::<f64>().ok()),
            first["lon"].as_str().and_then(|s| s.parse::<f64>().ok()),
        ) {
            coordinates.push((lat, lon));
        }
    }
    let coordinates: Vec<_> = coordinates
        .into_iter()
        .filter(|(lat, lon)| {
            lat.is_finite()
                && lon.is_finite()
                && (47.0..=56.0).contains(lat)
                && (5.0..=16.0).contains(lon)
        })
        .collect();
    if coordinates.is_empty() {
        return Ok(Lookup::NotFound);
    }
    Ok(Lookup::Resolved(
        coordinates.iter().map(|v| v.0).sum::<f64>() / coordinates.len() as f64,
        coordinates.iter().map(|v| v.1).sum::<f64>() / coordinates.len() as f64,
        if postal { "POSTAL" } else { "CITY" },
    ))
}

/// Explicit, bounded geocoding, with no full address sent to the provider.
/// Errors do not poison the cache; negative results expire after seven days.
pub(crate) fn resolve(
    core: &LadiesCore,
    favorites_only: bool,
    max_requests: u32,
    control: &Arc<RequestControl>,
) -> Result<MapOverview, CoreError> {
    if !(1..=MAX_GEOCODE_REQUESTS).contains(&max_requests) {
        return Err(CoreError::InvalidInput(
            "Geocode batch must contain 1–15 requests".into(),
        ));
    }
    let people = candidates(core, favorites_only)?;
    let mut cache = read_cache(core)?;
    let mut pending = BTreeMap::new();
    for person in &people {
        let (_, candidate) = chosen_location(person, &cache);
        if let Some((key, value, postal)) = candidate {
            pending.entry(key).or_insert((value, postal));
        }
    }
    let client = Client::builder()
        .timeout(Duration::from_secs(9))
        .user_agent("LadiesExplorerAndroid/0.1 (github.com/Justify87/ladies-explorer-android)")
        .build()
        .map_err(|error| CoreError::Source(error.to_string()))?;
    let mut failed = 0u32;
    let mut last_city_request: Option<Instant> = None;
    for (key, (value, postal)) in pending.into_iter().take(max_requests as usize) {
        control.check()?;
        if !postal {
            // Nominatim's public service requires a strict <=1 request/second rate.
            if let Some(previous) = last_city_request {
                let elapsed = previous.elapsed();
                if elapsed < Duration::from_millis(1100) {
                    std::thread::sleep(Duration::from_millis(1100) - elapsed);
                }
            }
            last_city_request = Some(Instant::now());
        }
        let response = geocode(&client, &value, postal);
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
            Err(_) => {
                failed += 1;
                continue;
            }
        };
        save_cache(core, &key, &entry)?;
        cache.insert(key, entry);
    }
    Ok(build_overview(people, &cache, failed))
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

    #[test]
    fn no_fake_coordinates_and_deduplicated_people() {
        let core = seed();
        let view = overview(&core, false).unwrap();
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
        let view = overview(&core, false).unwrap();
        assert_eq!(view.profiles.len(), 2);
        assert!(view.profiles.iter().all(|p| p.precision == "POSTAL"));
        assert_eq!(view.pending_locations, 0);
        assert_eq!(view.unlocated_profiles, 0);
        core.set_favorite(1, true).unwrap();
        let view = overview(&core, true).unwrap();
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
        let view = overview(&core, false).unwrap();
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
        assert_eq!(overview(&core, false).unwrap().profiles.len(), 0);
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
        let view = overview(&core, false).unwrap();
        assert_eq!(view.profiles.len(), 2);
        assert!(view.profiles.iter().all(|p| p.precision == "CITY"));
    }

    #[test]
    fn postal_parser_accepts_only_five_digit_tokens() {
        assert_eq!(postal_from("in 04229 Leipzig"), Some("04229".into()));
        assert_eq!(postal_from("123456789"), None);
        assert_eq!(postal_from("Keine Adresse"), None);
    }
}
