//! Local, coarse location evidence. Hidden addresses and profile data never reach a geocoder.
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, OnceLock};

use rusqlite::{Connection, OptionalExtension, params};
use serde::Deserialize;

use crate::{CoreError, LadiesCore, RequestControl};

const DATA_VERSION: &str = "geonames-3a94317a6192e7e576c078538dfe665048d42edf";
const BACKFILL_BATCH: usize = 64;

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

fn city_key(value: &str) -> String {
    value
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase()
}

fn postal_code(value: &str) -> Option<String> {
    let value = value.trim();
    (value.len() == 5 && value.bytes().all(|ch| ch.is_ascii_digit()))
        .then(|| value.to_owned())
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct Evidence {
    city: String,
    postal: String,
}

impl Evidence {
    fn new(city: &str, postal: Option<&str>) -> Self {
        let city = city.trim();
        let prefix = city.split_once(char::is_whitespace).and_then(|(first, rest)| {
            postal_code(first).map(|code| (code, rest.trim()))
        });
        let (embedded, city) = match prefix {
            Some((code, rest)) => (Some(code), rest),
            None => (None, city),
        };
        Self {
            city: city_key(city),
            postal: postal
                .and_then(postal_code)
                .or(embedded)
                .unwrap_or_default(),
        }
    }

    fn from_observations(
        city: &str,
        snapshot_city: Option<&str>,
        snapshot_postal: Option<&str>,
    ) -> Self {
        let card = Self::new(city, None);
        let snapshot = Self::new(snapshot_city.unwrap_or_default(), snapshot_postal);
        if !card.city.is_empty() && card.city == snapshot.city && card.postal.is_empty() {
            snapshot
        } else {
            card
        }
    }
}

#[derive(Default)]
struct Extent {
    points: Vec<(f64, f64)>,
    states: HashSet<String>,
}

impl Extent {
    fn add(&mut self, state: &str, latitude: f64, longitude: f64) {
        self.states.insert(state.to_owned());
        self.points.push((latitude, longitude));
    }

    fn center(&self, city: bool) -> Option<(f64, f64)> {
        let &(first_lat, first_lon) = self.points.first()?;
        let (mut min_lat, mut max_lat, mut min_lon, mut max_lon) =
            (first_lat, first_lat, first_lon, first_lon);
        let (mut lat_sum, mut lon_sum) = (0.0, 0.0);
        for &(lat, lon) in &self.points {
            min_lat = min_lat.min(lat);
            max_lat = max_lat.max(lat);
            min_lon = min_lon.min(lon);
            max_lon = max_lon.max(lon);
            lat_sum += lat;
            lon_sum += lon;
        }
        // Homonyms in different regions are not evidence of a unique city.
        if city && (self.states.len() != 1 || max_lat - min_lat > 0.35 || max_lon - min_lon > 0.6) {
            return None;
        }
        let count = self.points.len() as f64;
        Some((lat_sum / count, lon_sum / count))
    }
}

#[derive(Default)]
struct PostalArea {
    extent: Extent,
    cities: HashSet<String>,
}

#[derive(Default)]
struct Gazetteer {
    postal: HashMap<String, PostalArea>,
    cities: HashMap<String, Extent>,
}

fn valid_point(lat: f64, lon: f64) -> bool {
    (47.0..=56.0).contains(&lat) && (5.0..=16.0).contains(&lon)
}

impl Gazetteer {
    fn parse(data: &str) -> Self {
        let mut result = Self::default();
        for line in data.lines() {
            let fields: Vec<_> = line.split('\t').collect();
            let [postal, city, state, lat, lon] = fields.as_slice() else {
                continue;
            };
            let (Ok(lat), Ok(lon)) = (lat.parse::<f64>(), lon.parse::<f64>()) else {
                continue;
            };
            if postal_code(postal).is_none() || city.is_empty() || state.is_empty() || !valid_point(lat, lon) {
                continue;
            }
            let city = city_key(city);
            let area = result.postal.entry((*postal).to_owned()).or_default();
            area.extent.add(state, lat, lon);
            area.cities.insert(city.clone());
            result.cities.entry(city).or_default().add(state, lat, lon);
        }
        result
    }
}

fn gazetteer() -> &'static Gazetteer {
    static DATA: OnceLock<Gazetteer> = OnceLock::new();
    DATA.get_or_init(|| Gazetteer::parse(include_str!("../data/de-postal.tsv")))
}

#[derive(Debug, Clone)]
struct Location {
    latitude: f64,
    longitude: f64,
    precision: String,
    provenance: String,
}

impl Location {
    fn new(point: (f64, f64), precision: &str, provenance: &str) -> Self {
        Self {
            latitude: point.0,
            longitude: point.1,
            precision: precision.into(),
            provenance: provenance.into(),
        }
    }
}

#[derive(Deserialize)]
struct LegacyLocation {
    status: String,
    latitude: Option<f64>,
    longitude: Option<f64>,
    precision: Option<String>,
}

fn legacy_location(
    db: &Connection,
    key: &str,
    precision: &str,
    provenance: &str,
) -> Result<Option<Location>, CoreError> {
    let json: Option<String> = db
        .query_row(
            "SELECT value_json FROM app_settings WHERE key=?1",
            [key],
            |row| row.get(0),
        )
        .optional()?;
    Ok(json
        .and_then(|value| serde_json::from_str::<LegacyLocation>(&value).ok())
        .and_then(|record| {
            let (lat, lon) = (record.latitude?, record.longitude?);
            (record.status == "RESOLVED"
                && record.precision.as_deref() == Some(precision)
                && valid_point(lat, lon))
            .then(|| Location::new((lat, lon), precision, provenance))
        }))
}

fn choose(
    db: &Connection,
    evidence: &Evidence,
    data: &Gazetteer,
) -> Result<Option<Location>, CoreError> {
    if !evidence.postal.is_empty() {
        let area = data.postal.get(&evidence.postal);
        // Do not attach a postcode from an inconsistent source city.
        let consistent = area.is_none_or(|area| {
            evidence.city.is_empty()
                || area.cities.contains(&evidence.city)
                || !data.cities.contains_key(&evidence.city)
        });
        if consistent {
            if let Some(location) = legacy_location(
                db,
                &format!("map:postal:DE:{}", evidence.postal),
                "POSTAL",
                "LEGACY_POSTAL",
            )? {
                return Ok(Some(location));
            }
            if let Some(point) = area.and_then(|area| area.extent.center(false)) {
                return Ok(Some(Location::new(point, "POSTAL", "GEONAMES")));
            }
        }
    }
    if evidence.city.is_empty() {
        return Ok(None);
    }
    let extent = data.cities.get(&evidence.city);
    let point = extent.and_then(|extent| extent.center(true));
    if extent.is_some() && point.is_none() {
        return Ok(None);
    }
    if let Some(location) = legacy_location(
        db,
        &format!("map:city:DE:{}", evidence.city),
        "CITY",
        "LEGACY_CITY",
    )? {
        return Ok(Some(location));
    }
    Ok(point.map(|point| Location::new(point, "CITY", "GEONAMES")))
}

type Prepared = (Option<Location>, String);

fn prepared(
    db: &Connection,
    listing_id: i64,
    evidence: &Evidence,
) -> Result<Option<Prepared>, CoreError> {
    Ok(db
        .query_row(
            "SELECT latitude,longitude,precision,provenance,dataset_version
             FROM listing_locations WHERE listing_id=?1 AND city_key=?2 AND postal_key=?3",
            params![listing_id, evidence.city, evidence.postal],
            |row| {
                let latitude: Option<f64> = row.get(0)?;
                let longitude: Option<f64> = row.get(1)?;
                let precision: Option<String> = row.get(2)?;
                let provenance: String = row.get(3)?;
                let version: String = row.get(4)?;
                let location = latitude
                    .zip(longitude)
                    .zip(precision)
                    .filter(|((lat, lon), _)| valid_point(*lat, *lon))
                    .map(|((latitude, longitude), precision)| Location {
                        latitude,
                        longitude,
                        precision,
                        provenance,
                    });
                Ok((location, version))
            },
        )
        .optional()?)
}

fn prepare_one(
    db: &Connection,
    listing_id: i64,
    evidence: &Evidence,
    data: &Gazetteer,
) -> Result<(), CoreError> {
    if prepared(db, listing_id, evidence)?
        .is_some_and(|(location, version)| location.is_some() || version == DATA_VERSION)
    {
        return Ok(());
    }
    let location = choose(db, evidence, data)?;
    db.execute(
        "INSERT INTO listing_locations
         (listing_id,city_key,postal_key,latitude,longitude,precision,provenance,dataset_version)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8)
         ON CONFLICT(listing_id,city_key,postal_key) DO UPDATE SET
         latitude=excluded.latitude,longitude=excluded.longitude,precision=excluded.precision,
         provenance=excluded.provenance,dataset_version=excluded.dataset_version",
        params![
            listing_id,
            evidence.city,
            evidence.postal,
            location.as_ref().map(|value| value.latitude),
            location.as_ref().map(|value| value.longitude),
            location.as_ref().map(|value| value.precision.as_str()),
            location.as_ref().map_or("NONE", |value| value.provenance.as_str()),
            DATA_VERSION,
        ],
    )?;
    Ok(())
}

pub(crate) fn prepare_evidence(
    db: &Connection,
    listing_id: i64,
    city: &str,
    postal: Option<&str>,
) -> Result<(), CoreError> {
    prepare_one(db, listing_id, &Evidence::new(city, postal), gazetteer())
}

struct Candidate {
    listing_id: i64,
    person_id: i64,
    name: String,
    city: String,
    evidence: Evidence,
    favorite: bool,
    thumbnail_url: Option<String>,
}

fn candidates(
    db: &Connection,
    listing_ids: &[i64],
    run_id: Option<i64>,
) -> Result<Vec<Candidate>, CoreError> {
    let ids = serde_json::to_string(listing_ids).map_err(|e| CoreError::Storage(e.to_string()))?;
    let mut statement = db.prepare(
        "SELECT l.listing_id,l.person_id,
                CASE WHEN ?2 IS NOT NULL THEN COALESCE(r.name,'')
                     ELSE COALESCE(NULLIF(s.name,''),NULLIF(r.name,''),NULLIF(p.display_name,''),'Inserat') END,
                CASE WHEN ?2 IS NOT NULL THEN COALESCE(r.city,'')
                     ELSE COALESCE(NULLIF(s.city,''),NULLIF(r.city,''),'') END,
                s.city,s.postal_code,u.favorite,
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
        let city: String = row.get(3)?;
        let snapshot_city: Option<String> = row.get(4)?;
        let snapshot_postal: Option<String> = row.get(5)?;
        let evidence = Evidence::from_observations(
            &city,
            snapshot_city.as_deref(),
            snapshot_postal.as_deref(),
        );
        Ok(Candidate {
            listing_id: row.get(0)?,
            person_id: row.get(1)?,
            name: row.get(2)?,
            city,
            evidence,
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

/// Backfill old observations in small transactions; a cancelled batch is rolled back.
pub(crate) fn prepare(
    core: &LadiesCore,
    listing_ids: &[i64],
    run_id: Option<i64>,
    control: &Arc<RequestControl>,
) -> Result<(), CoreError> {
    control.check()?;
    let _lease = core
        .location_preparation
        .try_lock()
        .map_err(|_| CoreError::Source("Standortaufbereitung läuft bereits".into()))?;
    let data = gazetteer();
    for batch in listing_ids.chunks(BACKFILL_BATCH) {
        control.check()?;
        let mut db = core.connection()?;
        let tx = db.transaction()?;
        for candidate in candidates(&tx, batch, run_id)? {
            control.check()?;
            prepare_one(&tx, candidate.listing_id, &candidate.evidence, data)?;
        }
        control.check()?;
        tx.commit()?;
    }
    Ok(())
}

pub(crate) fn overview(
    core: &LadiesCore,
    listing_ids: &[i64],
    run_id: Option<i64>,
) -> Result<MapOverview, CoreError> {
    let db = core.connection()?;
    let people = candidates(&db, listing_ids, run_id)?;
    let total_profiles = people.len().min(u32::MAX as usize) as u32;
    let mut profiles = Vec::new();
    let mut pending = HashSet::new();
    for person in people {
        let location = match prepared(&db, person.listing_id, &person.evidence)? {
            Some((Some(location), _)) => location,
            Some((None, version)) if version == DATA_VERSION => continue,
            _ => {
                pending.insert(person.evidence);
                continue;
            }
        };
        let location_label = if location.precision == "POSTAL" {
            if person.city.is_empty() {
                person.evidence.postal.clone()
            } else {
                format!("{} {}", person.evidence.postal, person.city)
            }
        } else {
            person.city.clone()
        };
        profiles.push(MapProfile {
            listing_id: person.listing_id,
            person_id: person.person_id,
            name: person.name,
            city: person.city,
            location_label,
            latitude: location.latitude,
            longitude: location.longitude,
            precision: location.precision,
            favorite: person.favorite,
            thumbnail_url: person.thumbnail_url,
        });
    }
    Ok(MapOverview {
        unlocated_profiles: total_profiles.saturating_sub(profiles.len() as u32),
        total_profiles,
        profiles,
        pending_locations: pending.len() as u32,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture_data() -> Gazetteer {
        Gazetteer::parse(
            "12345\tTeststadt\tAA\t51.0\t12.0\n12346\tTeststadt\tAA\t51.1\t12.1\n\
             23456\tDoppelort\tAA\t51.0\t12.0\n34567\tDoppelort\tBB\t53.0\t10.0\n\
             45678\tAndere Stadt\tBB\t53.0\t10.0\n98765\tUngültig\tAA\t0\t0\n",
        )
    }

    fn seed() -> Arc<LadiesCore> {
        let core = LadiesCore::new(":memory:".into()).unwrap();
        let person = core.create_person(Some("Fixture".into())).unwrap();
        let listing = core
            .add_listing(person, None, "LADIES".into(), "fixture".into(), None)
            .unwrap();
        let db = core.connection().unwrap();
        db.execute(
            "INSERT INTO listing_snapshots(listing_id,fingerprint,city,postal_code,hidden_location)
             VALUES (?1,'fixture','Leipzig','04229','Private Straße 99')",
            [listing],
        )
        .unwrap();
        let snap = db.last_insert_rowid();
        db.execute(
            "UPDATE listings SET current_snapshot_id=?1 WHERE listing_id=?2",
            params![snap, listing],
        )
        .unwrap();
        drop(db);
        core
    }

    #[test]
    fn coarse_priority_and_ambiguous_or_unknown_city() {
        let db = Connection::open_in_memory().unwrap();
        let mut db = db;
        crate::schema::migrate(&mut db).unwrap();
        let data = fixture_data();
        let postal = choose(&db, &Evidence::new("Teststadt", Some("12345")), &data)
            .unwrap()
            .unwrap();
        assert_eq!(postal.precision, "POSTAL");
        assert_eq!((postal.latitude, postal.longitude), (51.0, 12.0));
        let city = choose(&db, &Evidence::new("Teststadt", Some("45678")), &data)
            .unwrap()
            .unwrap();
        assert_eq!(city.precision, "CITY");
        assert!((city.latitude - 51.05).abs() < 0.0001);
        for name in ["Doppelort", "Unbekannt", "Ungültig"] {
            assert!(choose(&db, &Evidence::new(name, None), &data).unwrap().is_none());
        }
    }

    #[test]
    fn legacy_valid_cache_is_reused_and_negative_does_not_block_offline_data() {
        let core = seed();
        let db = core.connection().unwrap();
        let evidence = Evidence::new("Teststadt", Some("12345"));
        db.execute(
            "INSERT INTO app_settings(key,value_json) VALUES (?1,?2)",
            params![
                "map:postal:DE:12345",
                r#"{"status":"RESOLVED","latitude":51.2,"longitude":12.2,"precision":"POSTAL"}"#
            ],
        )
        .unwrap();
        prepare_one(&db, 1, &evidence, &fixture_data()).unwrap();
        let (location, _) = prepared(&db, 1, &evidence).unwrap().unwrap();
        assert_eq!(location.unwrap().provenance, "LEGACY_POSTAL");
        db.execute("DELETE FROM app_settings WHERE key='map:postal:DE:12345'", [])
            .unwrap();
        prepare_one(&db, 1, &evidence, &fixture_data()).unwrap();
        assert_eq!(
            prepared(&db, 1, &evidence).unwrap().unwrap().0.unwrap().latitude,
            51.2
        );
        db.execute(
            "INSERT INTO app_settings(key,value_json) VALUES (?1,?2)",
            params!["map:postal:DE:12346", r#"{"status":"NOT_FOUND"}"#],
        )
        .unwrap();
        let other = Evidence::new("Teststadt", Some("12346"));
        prepare_one(&db, 1, &other, &fixture_data()).unwrap();
        assert_eq!(
            prepared(&db, 1, &other).unwrap().unwrap().0.unwrap().provenance,
            "GEONAMES"
        );
    }

    #[test]
    fn backfill_is_automatic_data_preparation_and_exact_membership_is_preserved() {
        let core = seed();
        assert!(overview(&core, &[1], None).unwrap().profiles.is_empty());
        prepare(&core, &[1], None, &RequestControl::new()).unwrap();
        let view = overview(&core, &[1], None).unwrap();
        assert_eq!(view.profiles.len(), 1);
        assert_eq!(view.profiles[0].precision, "POSTAL");
        assert_eq!(view.pending_locations, 0);
        assert!(overview(&core, &[999], None).is_err());
        {
            let db = core.connection().unwrap();
            db.execute("INSERT INTO search_runs(search_run_id) VALUES (1)", [])
                .unwrap();
            db.execute(
                "INSERT INTO search_results(search_run_id,listing_id,position,name,city)
                 VALUES (1,1,1,'Run fixture','Berlin')",
                [],
            )
            .unwrap();
        }
        prepare(&core, &[1], Some(1), &RequestControl::new()).unwrap();
        let view = overview(&core, &[1], Some(1)).unwrap();
        assert_eq!(view.profiles[0].name, "Run fixture");
        assert_eq!(view.profiles[0].precision, "CITY");
        assert_eq!(view.profiles[0].city, "Berlin");
        assert_eq!(overview(&core, &[1], None).unwrap().profiles[0].precision, "POSTAL");
        assert!(overview(&core, &[1], Some(2)).is_err());
    }

    #[test]
    fn cancellation_and_overlapping_lease_do_not_write() {
        let core = seed();
        let control = RequestControl::new();
        control.cancel();
        assert!(prepare(&core, &[1], None, &control).is_err());
        let _lease = core.location_preparation.lock().unwrap();
        assert!(prepare(&core, &[1], None, &RequestControl::new()).is_err());
        assert_eq!(overview(&core, &[1], None).unwrap().pending_locations, 1);
    }

    #[test]
    fn bundled_data_has_expected_coverage_without_private_inputs() {
        let data = gazetteer();
        assert_eq!(data.postal.len(), 8307);
        assert!(data.postal.contains_key("04229"));
        assert!(data.cities.get("berlin").unwrap().center(true).is_some());
        assert_eq!(Evidence::new("04229 Leipzig", None).postal, "04229");
        assert_eq!(Evidence::new("Leipzig", Some("Private Str. 04229")).postal, "");
    }
}
