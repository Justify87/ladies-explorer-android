use std::collections::HashSet;
use std::io::Read;
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use reqwest::blocking::Client;
use serde_json::Value;
use url::Url;

use crate::{
    CoreError,
    diagnostics::Diagnostics,
    http_capture::{self, CaptureMode, HttpObservation, HttpRecorder},
    operation::RequestControl,
};

use super::{Card, SearchRequest, filter, parse};

const BASE: &str = "https://www.ladies.de";
const MAX_RESPONSE_BYTES: u64 = 8 * 1024 * 1024;

struct Response {
    status: u16,
    body: String,
}

trait Transport {
    fn get(&self, url: &str) -> Result<Response, CoreError>;
    fn get_cancellable(
        &self,
        url: &str,
        control: Option<&RequestControl>,
    ) -> Result<Response, CoreError> {
        if let Some(control) = control {
            control.check()?;
        }
        let response = self.get(url)?;
        if let Some(control) = control {
            control.check()?;
        }
        Ok(response)
    }
    fn diagnostics(&self) -> Option<&Diagnostics> {
        None
    }
    fn log_event(&self, component: &str, message: impl AsRef<str>) {
        if let Some(log) = self.diagnostics() {
            if component == "PARSER" {
                log.debug(component, message);
            } else {
                log.event(component, message);
            }
        }
    }
    fn log_html(&self, stage: &str, html: &str) {
        if let Some(log) = self.diagnostics() {
            log.html(stage, html);
        }
    }
    fn wait_between_pages(&self) {}
}

pub(super) struct SourceHttp {
    client: Client,
    diagnostics: Arc<Diagnostics>,
    recorder: Arc<HttpRecorder>,
}

impl SourceHttp {
    pub(super) fn new(
        diagnostics: Arc<Diagnostics>,
        recorder: Arc<HttpRecorder>,
    ) -> Result<Self, CoreError> {
        let client = Client::builder()
            .cookie_store(true)
            // Redirects are followed explicitly so each actual network response can be captured.
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(25))
            .user_agent(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) \
                 AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36",
            )
            .build()
            .map_err(|e| CoreError::Source(format!("HTTP initialization failed: {e}")))?;
        Ok(Self {
            client,
            diagnostics,
            recorder,
        })
    }

    pub(super) fn get_profile(&self, url: &str) -> Result<String, CoreError> {
        let url = parse::valid_profile_url(url)
            .ok_or_else(|| CoreError::InvalidInput("Invalid Ladies profile URL".into()))?;
        let response = self.get(&url)?;
        if response.status != 200 {
            return Err(CoreError::Source(format!(
                "Profile HTTP status {}",
                response.status
            )));
        }
        Ok(response.body)
    }
}

fn check_redirect(
    log: &Diagnostics,
    requested_path: &str,
    response_path: &str,
    status: u16,
    body: &str,
) -> Result<(), CoreError> {
    if status == 200 && response_path != requested_path {
        log.html("unexpected_redirect", body);
        return Err(CoreError::Source(format!(
            "Die Website hat statt der angeforderten Seite {} die Seite {} geliefert.",
            requested_path, response_path
        )));
    }
    Ok(())
}

fn redirect_target(
    current: &Url,
    status: u16,
    location: Option<&str>,
    followed: usize,
) -> Result<Option<Url>, CoreError> {
    if !matches!(status, 301 | 302 | 303 | 307 | 308) {
        return Ok(None);
    }
    let Some(location) = location else {
        return Ok(None);
    };
    if followed >= 5 {
        return Err(CoreError::Source("Too many source redirects".into()));
    }
    let target = current
        .join(location)
        .map_err(|error| CoreError::Source(format!("Invalid redirect URL: {error}")))?;
    if target.scheme() != "https"
        || !matches!(target.host_str(), Some("www.ladies.de" | "ladies.de"))
    {
        return Err(CoreError::Source(
            "Source redirected outside ladies.de".into(),
        ));
    }
    Ok(Some(target))
}

impl Transport for SourceHttp {
    fn diagnostics(&self) -> Option<&Diagnostics> {
        Some(self.diagnostics.as_ref())
    }

    fn get(&self, url: &str) -> Result<Response, CoreError> {
        let parsed =
            Url::parse(url).map_err(|_| CoreError::InvalidInput("Malformed source URL".into()))?;
        if parsed.scheme() != "https" || parsed.host_str() != Some("www.ladies.de") {
            return Err(CoreError::InvalidInput(
                "HTTP is restricted to www.ladies.de".into(),
            ));
        }
        let accept = if parsed.path().starts_with("/api/") {
            "application/json, */*"
        } else {
            "text/html, application/xhtml+xml, */*"
        };
        let requested_path = parsed.path().to_owned();
        let request_started = Instant::now();
        let mut current_url = parsed;
        let mut followed = 0usize;
        let (mut response, mut observation, started, capture_epoch) = loop {
            let started = Instant::now();
            let remaining = Duration::from_secs(25).saturating_sub(request_started.elapsed());
            if remaining.is_zero() {
                return Err(CoreError::Source("Source HTTP request timed out".into()));
            }
            let request = self
                .client
                .get(current_url.clone())
                .timeout(remaining)
                .header("Accept", accept)
                .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.7")
                .header("Referer", BASE)
                .header("DNT", "1")
                .build()
                .map_err(|error| {
                    CoreError::Source(format!("Source HTTP request failed: {error}"))
                })?;
            let (capture_mode, capture_epoch) = self.recorder.mode_epoch();
            let mut observation = (capture_mode != CaptureMode::Off).then(|| HttpObservation {
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
            let sent = self.client.execute(request);
            let response = match sent {
                Ok(response) => response,
                Err(error) => {
                    if let Some(mut obs) = observation.take() {
                        obs.headers_elapsed_ms = started.elapsed().as_millis() as u64;
                        obs.error = Some(error.to_string());
                        self.recorder.record_for_epoch(obs, capture_epoch);
                    }
                    self.diagnostics
                        .error("HTTP", format!("GET failure {url}: {error}"));
                    return Err(CoreError::Source(format!(
                        "Source HTTP request failed: {error}"
                    )));
                }
            };
            let status = response.status().as_u16();
            if matches!(status, 301 | 302 | 303 | 307 | 308) {
                let location = response
                    .headers()
                    .get(reqwest::header::LOCATION)
                    .and_then(|header| header.to_str().ok());
                let next = redirect_target(response.url(), status, location, followed);
                if next.as_ref().is_err() || matches!(next, Ok(Some(_))) {
                    if let Some(mut obs) = observation.take() {
                        obs.headers_elapsed_ms = started.elapsed().as_millis() as u64;
                        obs.status = Some(status);
                        obs.http_version = format!("{:?}", response.version());
                        obs.response_headers = http_capture::headers(response.headers());
                        obs.response_size = response.content_length();
                        obs.mime = response
                            .headers()
                            .get(reqwest::header::CONTENT_TYPE)
                            .and_then(|header| header.to_str().ok())
                            .map(ToOwned::to_owned);
                        obs.body_omitted = Some("redirect_body_not_read".into());
                        self.recorder.record_for_epoch(obs, capture_epoch);
                    }
                }
                if let Some(target) = next? {
                    current_url = target;
                    followed += 1;
                    continue;
                }
                // A 3xx without Location is a terminal response and body is processed below.
            }
            break (response, observation, started, capture_epoch);
        };
        let header_duration = started.elapsed();
        let status = response.status().as_u16();
        let response_type = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|value| value.to_str().ok())
            .unwrap_or("not specified")
            .to_owned();
        if let Some(obs) = observation.as_mut() {
            obs.status = Some(status);
            obs.http_version = format!("{:?}", response.version());
            obs.headers_elapsed_ms = header_duration.as_millis() as u64;
            obs.final_url = response.url().to_string();
            obs.response_headers = http_capture::headers(response.headers());
            obs.mime = Some(response_type.clone());
            obs.response_size = response.content_length();
        }
        self.diagnostics.debug(
            "HTTP",
            format!(
                "Response status={status} type={response_type} final_url={} content_length={:?}",
                response.url(),
                response.content_length()
            ),
        );
        self.diagnostics
            .phase("HTTP", "request_until_headers", started);
        let redirected_path = response.url().path().to_owned();
        let body_started = Instant::now();
        let mut bytes = Vec::new();
        let read_result = response
            .by_ref()
            .take(MAX_RESPONSE_BYTES + 1)
            .read_to_end(&mut bytes);
        if let Some(obs) = observation.as_mut() {
            obs.body_elapsed_ms = body_started.elapsed().as_millis() as u64;
            obs.response_size = Some(bytes.len() as u64);
        }
        self.diagnostics
            .phase("HTTP", "response_body_read", body_started);
        if let Err(error) = read_result {
            if let Some(mut obs) = observation.take() {
                obs.error = Some(error.to_string());
                obs.body_omitted = Some("read_failed".into());
                self.recorder.record_for_epoch(obs, capture_epoch);
            }
            return Err(CoreError::Source(format!(
                "Source HTTP read failed: {error}"
            )));
        }
        if bytes.len() as u64 > MAX_RESPONSE_BYTES {
            if let Some(mut obs) = observation.take() {
                obs.error = Some("Source response size limit exceeded".into());
                obs.body_omitted = Some("source_limit".into());
                self.recorder.record_for_epoch(obs, capture_epoch);
            }
            return Err(CoreError::Source(
                "Source response size limit exceeded".into(),
            ));
        }
        let body = match String::from_utf8(bytes) {
            Ok(body) => body,
            Err(_) => {
                if let Some(mut obs) = observation.take() {
                    obs.error = Some("Source response is not UTF-8".into());
                    obs.body_omitted = Some("invalid_utf8".into());
                    self.recorder.record_for_epoch(obs, capture_epoch);
                }
                return Err(CoreError::Source("Source response is not UTF-8".into()));
            }
        };
        if let Some(mut obs) = observation {
            obs.response_size = Some(body.len() as u64);
            if self.recorder.mode() == CaptureMode::Text
                && body.len() <= http_capture::MAX_BODY_BYTES
            {
                obs.body = Some(body.clone());
            } else if body.len() > http_capture::MAX_BODY_BYTES {
                obs.body_omitted = Some("body_over_2_mib".into());
            }
            self.recorder.record_for_epoch(obs, capture_epoch);
        }
        self.diagnostics.debug(
            "HTTP",
            format!("Response bytes={} status={status}", body.len()),
        );
        check_redirect(
            &self.diagnostics,
            &requested_path,
            &redirected_path,
            status,
            &body,
        )?;
        if status >= 400 {
            self.diagnostics.html("http_error", &body);
        }
        Ok(Response { status, body })
    }

    fn wait_between_pages(&self) {
        thread::sleep(Duration::from_millis(350));
    }
}

pub(super) struct DownloadedSearch {
    pub cards: Vec<Card>,
    pub city: String,
    pub city_slug: String,
    pub server_count: Option<u32>,
    pub total_pages: u32,
    pub fetched_pages: u32,
    pub partial: bool,
    pub radius_boundary_stopped: bool,
    pub radius_verified: bool,
}

fn resolve_location<T: Transport>(
    client: &T,
    city: &str,
    control: Option<&RequestControl>,
) -> (String, String) {
    let fallback = (filter::slugify_city(city), city.trim().to_owned());
    let mut url = Url::parse(&format!("{BASE}/api/location")).expect("constant source URL");
    url.query_pairs_mut()
        .append_pair("searchString", city)
        .append_pair("searchAddAlleRegionen", "false");
    let Ok(response) = client.get_cancellable(url.as_str(), control) else {
        return fallback;
    };
    if response.status != 200 {
        return fallback;
    }
    let Ok(payload) = serde_json::from_str::<Value>(&response.body) else {
        return fallback;
    };
    let mut best: Option<(i32, String, String)> = None;
    fn inspect(node: &Value, wanted: &str, best: &mut Option<(i32, String, String)>) {
        if let Value::Object(map) = node {
            let get = |keys: &[&str]| -> Option<String> {
                keys.iter()
                    .find_map(|wanted| {
                        map.iter()
                            .find(|(key, _)| key.eq_ignore_ascii_case(wanted))
                            .and_then(|(_, value)| value.as_str())
                    })
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .map(str::to_owned)
            };
            if let (Some(slug), Some(name)) = (
                get(&["slug", "seoSlug", "urlSlug"]),
                get(&["name", "title", "ort", "locationName"]),
            ) {
                let candidate = name.to_lowercase();
                let score = if candidate == wanted {
                    100
                } else if candidate.starts_with(wanted) {
                    70
                } else if candidate.contains(wanted) {
                    40
                } else {
                    0
                };
                if score > 0
                    && slug
                        .chars()
                        .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-')
                    && best.as_ref().is_none_or(|(value, _, _)| score > *value)
                {
                    *best = Some((score, slug, name));
                }
            }
            for value in map.values() {
                inspect(value, wanted, best);
            }
        } else if let Value::Array(items) = node {
            for value in items {
                inspect(value, wanted, best);
            }
        }
    }
    inspect(&payload, &city.trim().to_lowercase(), &mut best);
    best.map(|(_, slug, name)| (slug, name)).unwrap_or(fallback)
}

fn search_url(city_slug: &str, radius: u32, page: u32, filter_path: &str, api: bool) -> String {
    let api_segment = if api { "/api" } else { "" };
    let filter_segment = if filter_path.is_empty() {
        String::new()
    } else {
        format!("/{filter_path}")
    };
    format!(
        "{BASE}{api_segment}/{city_slug}/sex-anzeigen{filter_segment}?umkreis={radius}&page={page}"
    )
}

fn parse_api(response: Response, logger: Option<&Diagnostics>) -> Result<Option<Value>, CoreError> {
    if response.status == 404 {
        return Ok(None);
    }
    if response.status != 200 {
        return Err(CoreError::Source(format!(
            "Search API HTTP status {}",
            response.status
        )));
    }
    let parse_started = Instant::now();
    let payload: Value = serde_json::from_str(&response.body).map_err(|e| {
        if let Some(log) = logger {
            log.html("invalid_api_json", &response.body);
        }
        CoreError::Source(format!("Invalid source search API payload: {e}"))
    })?;
    if let Some(log) = logger {
        log.phase("PARSER", "json_parse", parse_started);
    }
    if !payload.is_object() {
        if let Some(log) = logger {
            log.html("invalid_api_shape", &response.body);
        }
        return Err(CoreError::Source(
            "Search API response is not an object".into(),
        ));
    }
    Ok(Some(payload))
}

fn number(value: Option<&Value>) -> Option<u32> {
    value.and_then(|v| {
        v.as_u64()
            .and_then(|n| u32::try_from(n).ok())
            .or_else(|| v.as_str().and_then(|s| s.parse().ok()))
    })
}

fn distance_matches(payload: &Value, requested_radius: u32) -> bool {
    match payload.get("distanceSearched") {
        None => true,
        Some(value) => number(Some(value)) == Some(requested_radius),
    }
}

#[derive(PartialEq, Eq)]
enum AppendPageOutcome {
    Cards,
    RadiusTransition,
}

fn append_page(
    profiles: &mut Vec<Card>,
    payload: &Value,
    requested_radius: u32,
    page: u32,
    logger: Option<&Diagnostics>,
) -> Result<AppendPageOutcome, CoreError> {
    let content = payload
        .get("content")
        .and_then(Value::as_str)
        .ok_or_else(|| CoreError::Source("Search API page missing HTML content".into()))?;
    let parsed = parse::parse_cards(content);
    if let Some(log) = logger {
        log.event(
            "PARSER",
            format!(
                "API page={page} cards={} {}",
                parsed.len(),
                parse::search_page_diagnostics(content)
            ),
        );
    }
    if parsed.is_empty() && !content.trim().is_empty() {
        if parse::is_radius_transition_placeholder(content, requested_radius) {
            return Ok(AppendPageOutcome::RadiusTransition);
        }
        if let Some(log) = logger {
            log.html("api_page_unrecognized_cards", content);
        }
        return Err(CoreError::Source(format!(
            "Die Folgeseite enthält keine erkennbaren Anzeigenkarten. {}",
            parse::search_page_diagnostics(content)
        )));
    }
    profiles.extend(parsed);
    Ok(AppendPageOutcome::Cards)
}

pub(super) fn fetch_search(
    client: &SourceHttp,
    request: &SearchRequest,
    control: Option<&RequestControl>,
) -> Result<DownloadedSearch, CoreError> {
    fetch_search_controlled(client, request, control)
}

#[cfg(test)]
fn fetch_search_using<T: Transport>(
    client: &T,
    request: &SearchRequest,
) -> Result<DownloadedSearch, CoreError> {
    fetch_search_controlled(client, request, None)
}

fn fetch_search_controlled<T: Transport>(
    client: &T,
    request: &SearchRequest,
    control: Option<&RequestControl>,
) -> Result<DownloadedSearch, CoreError> {
    let check = || -> Result<(), CoreError> {
        if let Some(control) = control {
            control.check()?;
        }
        Ok(())
    };
    check()?;
    filter::validate_request(request)?;
    let filter_path = filter::filter_path(request)?;
    let (city_slug, city) = resolve_location(client, &request.city, control);
    check()?;
    client.log_event(
        "SEARCH",
        format!(
            "Resolved location={} slug={} radius={} max_pages={} filter_path={}",
            city, city_slug, request.radius_km, request.max_pages, filter_path
        ),
    );
    let first = client.get_cancellable(
        &search_url(&city_slug, request.radius_km, 1, &filter_path, false),
        control,
    )?;
    if first.status == 404 {
        return Ok(DownloadedSearch {
            cards: Vec::new(),
            city,
            city_slug,
            server_count: Some(0),
            total_pages: 0,
            fetched_pages: 1,
            partial: false,
            radius_boundary_stopped: false,
            radius_verified: false,
        });
    }
    if first.status != 200 {
        return Err(CoreError::Source(format!(
            "Search HTTP status {}",
            first.status
        )));
    }

    let first_parse_started = Instant::now();
    let mut total_from_html = parse::parse_config(&first.body, "totalPages");
    let mut server_count = parse::parse_config(&first.body, "advertCount");
    let mut observed_radius = parse::parse_config(&first.body, "distanceSearched");
    if observed_radius.is_some_and(|radius| radius != request.radius_km) {
        return Err(CoreError::Source(format!(
            "Search radius was expanded by the source: requested {} km, returned {} km",
            request.radius_km,
            observed_radius.unwrap_or_default()
        )));
    }
    let mut cards = parse::parse_cards(&first.body);
    if let Some(log) = client.diagnostics() {
        log.phase("PARSER", "first_html_cards", first_parse_started);
    }
    client.log_event(
        "PARSER",
        format!(
            "Search page=1 status={} cards={} pages={:?} advert_count={:?} observed_radius={:?} {}",
            first.status,
            cards.len(),
            total_from_html,
            server_count,
            observed_radius,
            parse::search_page_diagnostics(&first.body)
        ),
    );
    if cards.is_empty()
        && (server_count != Some(0) || total_from_html.is_some_and(|pages| pages > 1))
    {
        // The browser HAR shows the same search cards in an API 'content' field.
        // Try that first-page representation before treating HTML parser drift as fatal.
        let diagnostic = parse::search_page_diagnostics(&first.body);
        client.log_html("search_page_1_unrecognized", &first.body);
        client.log_event("SEARCH", "Attempting JSON API fallback for page 1");
        let first_api = client
            .get_cancellable(
                &search_url(&city_slug, request.radius_km, 1, &filter_path, true),
                control,
            )
            .and_then(|response| parse_api(response, client.diagnostics()))
            .map_err(|err| {
                CoreError::Source(format!(
                    "Such-HTML unlesbar ({diagnostic}); API-Fallback fehlgeschlagen: {err}"
                ))
            })?
            .ok_or_else(|| {
                CoreError::Source(format!(
                    "Such-HTML unlesbar ({diagnostic}); API-Seite 1 wurde nicht gefunden"
                ))
            })?;
        let api_radius = number(first_api.get("distanceSearched"));
        if api_radius != Some(request.radius_km) {
            return Err(CoreError::Source(format!(
                "Such-HTML unlesbar ({diagnostic}); API-Radius nicht bestätigt"
            )));
        }
        let api_html = first_api
            .get("content")
            .and_then(Value::as_str)
            .ok_or_else(|| {
                CoreError::Source(format!(
                    "Such-HTML unlesbar ({diagnostic}); API enthält kein Karten-HTML"
                ))
            })?;
        let recovered = parse::parse_cards(api_html);
        if recovered.is_empty() {
            client.log_html("api_page_1_unrecognized_cards", api_html);
            return Err(CoreError::Source(format!(
                "Keine erkennbaren Anzeigenkarten in HTML oder API. HTML: {diagnostic}; API: {}",
                parse::search_page_diagnostics(api_html)
            )));
        }
        cards = recovered;
        total_from_html = number(first_api.pointer("/pagination/totalPages"));
        server_count = number(first_api.get("advertCount"));
        observed_radius = api_radius;
    }
    let mut radius_verified = observed_radius == Some(request.radius_km);
    let mut partial = !radius_verified;
    if cards.is_empty() {
        return Ok(DownloadedSearch {
            cards,
            city,
            city_slug,
            server_count,
            total_pages: 0,
            fetched_pages: 1,
            partial,
            radius_boundary_stopped: false,
            radius_verified,
        });
    }

    let mut total_pages = total_from_html.unwrap_or(1).max(1);
    let mut fetched_pages = 1u32;
    let mut boundary_stopped = false;
    let mut next_page = 2;
    if total_from_html.is_none() {
        if request.max_pages == 1 {
            // Do not fetch beyond the caller's limit just to discover pagination.
            partial = true;
        } else {
            check()?;
            client.wait_between_pages();
            check()?;
            let probe = parse_api(
                client.get_cancellable(
                    &search_url(&city_slug, request.radius_km, 2, &filter_path, true),
                    control,
                )?,
                client.diagnostics(),
            )?;
            if let Some(payload) = probe {
                fetched_pages += 1;
                match payload.get("distanceSearched") {
                    None => {
                        radius_verified = false;
                        partial = true;
                    }
                    Some(_) if !distance_matches(&payload, request.radius_km) => {
                        radius_verified = false;
                        partial = true;
                        boundary_stopped = true;
                    }
                    _ => {}
                }
                if !boundary_stopped {
                    total_pages = number(payload.pointer("/pagination/totalPages"))
                        .unwrap_or(2)
                        .max(2);
                    if let Some(count) = number(payload.get("advertCount")) {
                        server_count = Some(count);
                    }
                    if append_page(
                        &mut cards,
                        &payload,
                        request.radius_km,
                        2,
                        client.diagnostics(),
                    )? == AppendPageOutcome::RadiusTransition
                    {
                        boundary_stopped = true;
                        client
                            .log_event("SEARCH", "Stopped at page=2 radius transition placeholder");
                    }
                }
            } else {
                total_pages = 1;
            }
        }
        next_page = 3;
    }

    if !boundary_stopped {
        for page in next_page..=total_pages.min(request.max_pages) {
            check()?;
            client.wait_between_pages();
            check()?;
            let response = parse_api(
                client.get_cancellable(
                    &search_url(&city_slug, request.radius_km, page, &filter_path, true),
                    control,
                )?,
                client.diagnostics(),
            )?;
            let Some(payload) = response else {
                partial = true;
                break;
            };
            fetched_pages += 1;
            match payload.get("distanceSearched") {
                None => {
                    radius_verified = false;
                    partial = true;
                }
                Some(_) if !distance_matches(&payload, request.radius_km) => {
                    radius_verified = false;
                    partial = true;
                    boundary_stopped = true;
                    break;
                }
                _ => {}
            }
            if append_page(
                &mut cards,
                &payload,
                request.radius_km,
                page,
                client.diagnostics(),
            )? == AppendPageOutcome::RadiusTransition
            {
                boundary_stopped = true;
                client.log_event(
                    "SEARCH",
                    format!("Stopped at page={page} radius transition placeholder"),
                );
                break;
            }
        }
        if total_pages > request.max_pages {
            partial = true;
        }
    }

    let parsed_cards = cards.len();
    let mut ids = HashSet::new();
    cards.retain(|card| ids.insert(card.advert_id.clone()));
    let count_mismatch = server_count.is_some_and(|count| cards.len() as u32 != count);
    if count_mismatch || (boundary_stopped && server_count.is_none()) {
        partial = true;
    }
    client.log_event(
        "SEARCH",
        format!(
            "Search completed cards={} parsed_cards={} duplicates={} server_count={:?} count_mismatch={} fetched_pages={} total_pages={} partial={} radius_verified={} boundary_stopped={}",
            cards.len(),
            parsed_cards,
            parsed_cards - cards.len(),
            server_count,
            count_mismatch,
            fetched_pages,
            total_pages,
            partial,
            radius_verified,
            boundary_stopped
        ),
    );
    check()?;
    Ok(DownloadedSearch {
        cards,
        city,
        city_slug,
        server_count,
        total_pages,
        fetched_pages,
        partial,
        radius_boundary_stopped: boundary_stopped,
        radius_verified,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::collections::HashMap;

    #[derive(Default)]
    struct Mock {
        pages: HashMap<String, (u16, String)>,
    }

    impl Mock {
        fn with(mut self, url: &str, status: u16, body: &str) -> Self {
            self.pages.insert(url.to_owned(), (status, body.to_owned()));
            self
        }
    }

    impl Transport for Mock {
        fn get(&self, url: &str) -> Result<Response, CoreError> {
            let (status, body) = self
                .pages
                .get(url)
                .unwrap_or_else(|| panic!("Unexpected {url}"));
            Ok(Response {
                status: *status,
                body: body.clone(),
            })
        }
    }

    fn location() -> &'static str {
        r#"{"results":[{"slug":"leipzig","name":"Leipzig"}]}"#
    }

    fn card(id: u32) -> String {
        format!(
            "<div class='ladies-card'><a class='card-link' data-ref-id='{id}' href='/sex-anzeigen/huren-hostessen/{id}'></a><span class='card-name'>Name {id}</span><span class='card-city'>Leipzig</span></div>"
        )
    }

    fn with_city(mock: Mock) -> Mock {
        mock.with(
            "https://www.ladies.de/api/location?searchString=Leipzig&searchAddAlleRegionen=false",
            200,
            location(),
        )
    }

    #[test]
    fn cancelled_search_stops_before_any_network_request() {
        let control = RequestControl::default();
        control.cancel();
        assert!(
            fetch_search_controlled(&Mock::default(), &SearchRequest::for_test(), Some(&control))
                .is_err()
        );
    }

    #[test]
    fn november_location_api_uses_title_case_keys() {
        let client = Mock::default().with(
            "https://www.ladies.de/api/location?searchString=Leipzi&searchAddAlleRegionen=false",
            200,
            r#"[{"Name":"Leipzig","Slug":"leipzig","Type":"top-ort","LocationId":42}]"#,
        );
        let (slug, display) = resolve_location(&client, "Leipzi", None);
        assert_eq!(slug, "leipzig");
        assert_eq!(display, "Leipzig");
    }

    #[test]
    fn november_har_card_and_search_config_shapes_are_supported() {
        // Synthetic IDs and names, exact HTML class and metadata shapes from the browser HAR.
        let html = r#"<div class="card-gallery-premium ladies-card top-teaser">
            <a class="card-link" data-owg-id="9988" data-ref-id="9988"
               href="/fkk-saunaclubs/test-studio"></a>
            <div class="card-name card-text"><span>Studio</span></div></div>
            <div class="card-gallery-premium ladies-card">
            <a class="card-link" data-owg-id="12345" data-ref-id="12345"
               href="/sex-anzeigen/huren-hostessen/beispiel-12345"></a>
            <div class="card-name card-text"><span>Beispiel</span></div>
            <div class="card-city card-text"><span>Leipzig</span></div></div>
            totalPages: Number.parseInt(replaceWithDefault("8", 1)),
            advertCount: Number.parseInt(replaceWithDefault("203", 1)),
            distanceSearched: Number.parseInt(replaceWithDefault("20", 1))"#;
        let cards = parse::parse_cards(html);
        assert_eq!(cards.len(), 1);
        assert_eq!(cards[0].advert_id, "12345");
        assert_eq!(parse::parse_config(html, "totalPages"), Some(8));
        assert_eq!(parse::parse_config(html, "advertCount"), Some(203));
        assert_eq!(parse::parse_config(html, "distanceSearched"), Some(20));
    }

    #[test]
    fn recovers_first_page_from_recorded_api_contract_if_html_has_no_cards() {
        let mut request = SearchRequest::for_test();
        request.max_pages = 1;
        let payload = json!({
            "pagination":{"currentPage":1,"totalPages":2},
            "advertCount":2,
            "distanceSearched":"20",
            "content":card(12345),
        });
        let client = with_city(
            Mock::default()
                .with(
                    &search_url("leipzig", 20, 1, "", false),
                    200,
                    "<html><title>Search unavailable</title></html>",
                )
                .with(
                    &search_url("leipzig", 20, 1, "", true),
                    200,
                    &payload.to_string(),
                ),
        );
        let result = fetch_search_using(&client, &request).unwrap();
        assert_eq!(result.cards.len(), 1);
        assert!(result.radius_verified);
        assert!(result.partial);
        assert_eq!(result.total_pages, 2);
        assert_eq!(result.fetched_pages, 1);
    }

    #[test]
    fn rejected_html_and_invalid_api_cannot_create_false_search_results() {
        let payload = json!({
            "pagination":{"currentPage":1,"totalPages":1},
            "advertCount":5,
            "distanceSearched":"20",
            "content":"<div class='challenge'>Access check</div>",
        });
        let client = with_city(
            Mock::default()
                .with(
                    &search_url("leipzig", 20, 1, "", false),
                    200,
                    "<html><title>Checking your browser</title></html>",
                )
                .with(
                    &search_url("leipzig", 20, 1, "", true),
                    200,
                    &payload.to_string(),
                ),
        );
        let err = fetch_search_using(&client, &SearchRequest::for_test())
            .err()
            .expect("invalid HTML must fail");
        assert!(format!("{err}").contains("HTML oder API"));
    }

    #[test]
    fn api_page_one_with_unverified_radius_is_rejected() {
        let payload = json!({
            "pagination":{"currentPage":1,"totalPages":1},
            "advertCount":1,
            "content":card(12345),
        });
        let client = with_city(
            Mock::default()
                .with(
                    &search_url("leipzig", 20, 1, "", false),
                    200,
                    "<html><title>Unavailable</title></html>",
                )
                .with(
                    &search_url("leipzig", 20, 1, "", true),
                    200,
                    &payload.to_string(),
                ),
        );
        assert!(fetch_search_using(&client, &SearchRequest::for_test()).is_err());
    }

    #[test]
    fn redirect_target_preserves_validated_get_and_rejects_external_hosts() {
        let origin = Url::parse("https://www.ladies.de/leipzig/sex-anzeigen").unwrap();
        assert_eq!(
            redirect_target(&origin, 302, Some("/jugendschutz?session=1"), 0)
                .unwrap()
                .unwrap()
                .as_str(),
            "https://www.ladies.de/jugendschutz?session=1",
        );
        assert_eq!(
            redirect_target(&origin, 302, Some("https://ladies.de/path"), 1)
                .unwrap()
                .unwrap()
                .host_str(),
            Some("ladies.de"),
        );
        assert!(redirect_target(&origin, 302, Some("https://evil.example/path"), 0).is_err());
        assert!(redirect_target(&origin, 302, Some("http://www.ladies.de/path"), 0).is_err());
        assert!(redirect_target(&origin, 302, Some("/path"), 5).is_err());
        assert!(
            redirect_target(&origin, 304, Some("/not-a-redirect"), 0)
                .unwrap()
                .is_none()
        );
        assert!(redirect_target(&origin, 302, None, 0).unwrap().is_none());
    }

    #[test]
    fn rejected_redirect_does_not_leak_response_html_to_debug_log() {
        let directory = tempfile::tempdir().unwrap();
        let log = Diagnostics::new(directory.path().join("ladies.sqlite").to_str().unwrap());
        log.set_debug(true);
        let html = "<html><body>Alter prüfen – Weiterleitung</body></html>";
        let result = check_redirect(&log, "/leipzig/sex-anzeigen", "/jugendschutz", 200, html);
        assert!(matches!(result, Err(CoreError::Source(_))));
        let saved =
            std::fs::read_to_string(directory.path().join("diagnostics/ladies-debug.log")).unwrap();
        assert!(saved.contains("body discarded stage=unexpected_redirect"));
        assert!(!saved.contains(html));
        assert!(
            check_redirect(
                &log,
                "/leipzig/sex-anzeigen",
                "/leipzig/sex-anzeigen",
                200,
                html
            )
            .is_ok()
        );
    }

    #[test]
    fn radius_transition_preserves_previous_pages_as_partial() {
        let first = format!(
            "{} totalPages: Number.parseInt(replaceWithDefault(\"3\", 1)), \
             advertCount: Number.parseInt(replaceWithDefault(\"5\", 1)), \
             distanceSearched: Number.parseInt(replaceWithDefault(\"20\", 1))",
            card(1)
        );
        let transition = r#"<div hidden id="page-section-3" data-page="3"></div>
            <h3>3 Anzeigen im Umkreis bis 20 km</h3>
            <div class="card-gallery-standard affiliate-card ladies-card">
                <p class="card-name">&nbsp;</p><p class="card-city">&nbsp;</p>
            </div>
            <lds-button id="load-adverts-next-distance" data-next-distance="30"></lds-button>"#;
        let client = with_city(
            Mock::default()
                .with(&search_url("leipzig", 20, 1, "", false), 200, &first)
                .with(
                    &search_url("leipzig", 20, 2, "", true),
                    200,
                    &json!({"content":card(2), "distanceSearched":20}).to_string(),
                )
                .with(
                    &search_url("leipzig", 20, 3, "", true),
                    200,
                    &json!({"content":transition, "distanceSearched":20}).to_string(),
                ),
        );
        let result = fetch_search_using(&client, &SearchRequest::for_test()).unwrap();
        assert_eq!(result.cards.len(), 2);
        assert_eq!(result.server_count, Some(5));
        assert_eq!(result.fetched_pages, 3);
        assert_eq!(result.total_pages, 3);
        assert!(result.partial);
        assert!(result.radius_boundary_stopped);
        assert!(result.radius_verified);
    }

    #[test]
    fn verified_radius_transition_with_matching_count_is_complete() {
        let first = format!(
            "{} totalPages: Number.parseInt(replaceWithDefault(\"3\", 1)), \
             advertCount: Number.parseInt(replaceWithDefault(\"2\", 1)), \
             distanceSearched: Number.parseInt(replaceWithDefault(\"20\", 1))",
            card(1)
        );
        let transition = r#"<div hidden id="page-section-3" data-page="3"></div>
            <div class="affiliate-card ladies-card"><p class="card-name">&nbsp;</p></div>
            <lds-button id="load-adverts-next-distance" data-next-distance="30"></lds-button>"#;
        let client = with_city(
            Mock::default()
                .with(&search_url("leipzig", 20, 1, "", false), 200, &first)
                .with(
                    &search_url("leipzig", 20, 2, "", true),
                    200,
                    &json!({"content":card(2), "distanceSearched":20}).to_string(),
                )
                .with(
                    &search_url("leipzig", 20, 3, "", true),
                    200,
                    &json!({"content":transition, "distanceSearched":20}).to_string(),
                ),
        );
        let result = fetch_search_using(&client, &SearchRequest::for_test()).unwrap();
        assert_eq!(result.cards.len(), 2);
        assert_eq!(result.server_count, Some(2));
        assert!(result.radius_boundary_stopped);
        assert!(result.radius_verified);
        assert!(!result.partial);
    }

    #[test]
    fn radius_transition_in_page_two_probe_is_not_a_parser_error() {
        let first = format!(
            "{} distanceSearched: Number.parseInt(replaceWithDefault(\"20\", 1))",
            card(1)
        );
        let transition = r#"<div hidden id="page-section-2" data-page="2"></div>
            <div class="affiliate-card ladies-card"><p class="card-name">&nbsp;</p></div>
            <lds-button id="load-adverts-next-distance" data-next-distance="30"></lds-button>"#;
        let client = with_city(
            Mock::default()
                .with(&search_url("leipzig", 20, 1, "", false), 200, &first)
                .with(
                    &search_url("leipzig", 20, 2, "", true),
                    200,
                    &json!({"content":transition, "distanceSearched":20,
                            "pagination":{"totalPages":2}})
                    .to_string(),
                ),
        );
        let result = fetch_search_using(&client, &SearchRequest::for_test()).unwrap();
        assert_eq!(result.cards.len(), 1);
        assert_eq!(result.fetched_pages, 2);
        assert!(result.partial && result.radius_boundary_stopped);
    }

    #[test]
    fn unknown_markup_with_radius_button_still_fails_closed() {
        let first = format!(
            "{} totalPages: Number.parseInt(replaceWithDefault(\"2\", 1)), \
             distanceSearched: Number.parseInt(replaceWithDefault(\"20\", 1))",
            card(1)
        );
        let invalid = r#"<div hidden id="page-section-2" data-page="2"></div>
            <div class="ladies-card"><a href="/sex-anzeigen/new-format">Changed card</a></div>
            <lds-button id="load-adverts-next-distance" data-next-distance="30"></lds-button>"#;
        let client = with_city(
            Mock::default()
                .with(&search_url("leipzig", 20, 1, "", false), 200, &first)
                .with(
                    &search_url("leipzig", 20, 2, "", true),
                    200,
                    &json!({"content":invalid, "distanceSearched":20}).to_string(),
                ),
        );
        assert!(fetch_search_using(&client, &SearchRequest::for_test()).is_err());
    }

    #[test]
    fn real_cards_on_a_page_with_radius_button_are_not_dropped() {
        let first = format!(
            "{} totalPages: Number.parseInt(replaceWithDefault(\"2\", 1)), \
             advertCount: Number.parseInt(replaceWithDefault(\"2\", 1)), \
             distanceSearched: Number.parseInt(replaceWithDefault(\"20\", 1))",
            card(1)
        );
        let html = format!(
            "{}<lds-button id='load-adverts-next-distance' data-next-distance='30'></lds-button>",
            card(2)
        );
        let client = with_city(
            Mock::default()
                .with(&search_url("leipzig", 20, 1, "", false), 200, &first)
                .with(
                    &search_url("leipzig", 20, 2, "", true),
                    200,
                    &json!({"content":html, "distanceSearched":20}).to_string(),
                ),
        );
        let result = fetch_search_using(&client, &SearchRequest::for_test()).unwrap();
        assert_eq!(result.cards.len(), 2);
        assert!(!result.partial);
        assert!(!result.radius_boundary_stopped);
    }
    #[test]
    fn exact_radius_pagination_deduplicates_and_is_complete() {
        let first = format!(
            "{} totalPages: Number.parseInt(replaceWithDefault(\"3\", 1)), distanceSearched: Number.parseInt(replaceWithDefault(\"20\",1))",
            card(1)
        );
        let client = with_city(
            Mock::default()
                .with(&search_url("leipzig", 20, 1, "", false), 200, &first)
                .with(
                    &search_url("leipzig", 20, 2, "", true),
                    200,
                    &json!({"content": format!("{}{}", card(2),card(1)), "distanceSearched":20})
                        .to_string(),
                )
                .with(
                    &search_url("leipzig", 20, 3, "", true),
                    200,
                    &json!({"content": card(3), "distanceSearched":20}).to_string(),
                ),
        );
        let found = fetch_search_using(&client, &SearchRequest::for_test()).unwrap();
        assert_eq!(found.cards.len(), 3);
        assert_eq!(found.total_pages, 3);
        assert_eq!(found.fetched_pages, 3);
        assert!(!found.partial);
    }

    #[test]
    fn stops_before_importing_auto_expanded_radius_cards() {
        let first = format!(
            "{} totalPages: Number.parseInt(replaceWithDefault(\"3\", 1))",
            card(1)
        );
        let client = with_city(
            Mock::default()
                .with(&search_url("leipzig", 20, 1, "", false), 200, &first)
                .with(
                    &search_url("leipzig", 20, 2, "", true),
                    200,
                    &json!({"content": card(2), "distanceSearched":50}).to_string(),
                ),
        );
        let found = fetch_search_using(&client, &SearchRequest::for_test()).unwrap();
        assert_eq!(found.cards.len(), 1);
        assert!(found.partial && found.radius_boundary_stopped);
    }

    #[test]
    fn page_one_radius_expansion_fails_instead_of_saving_wrong_results() {
        let first = format!(
            "{} distanceSearched: Number.parseInt(replaceWithDefault(\"50\", 1))",
            card(1)
        );
        let client =
            with_city(Mock::default().with(&search_url("leipzig", 20, 1, "", false), 200, &first));
        assert!(fetch_search_using(&client, &SearchRequest::for_test()).is_err());
    }

    #[test]
    fn invalid_radius_metadata_never_silently_passes() {
        assert!(!distance_matches(
            &json!({"distanceSearched":"unknown"}),
            20
        ));
        assert!(!distance_matches(&json!({"distanceSearched":null}), 20));
        assert!(distance_matches(&json!({"content":"ok"}), 20));
    }

    #[test]
    fn server_count_mismatch_is_reported_as_partial() {
        let first = format!(
            "{} totalPages: Number.parseInt(replaceWithDefault(\"1\", 1)), advertCount: Number.parseInt(replaceWithDefault(\"2\", 1))",
            card(1)
        );
        let client =
            with_city(Mock::default().with(&search_url("leipzig", 20, 1, "", false), 200, &first));
        let found = fetch_search_using(&client, &SearchRequest::for_test()).unwrap();
        assert_eq!(found.cards.len(), 1);
        assert!(found.partial);
    }

    #[test]
    fn first_page_404_is_empty_and_missing_metadata_probes_page_two() {
        let empty =
            with_city(Mock::default().with(&search_url("leipzig", 20, 1, "", false), 404, ""));
        let found = fetch_search_using(&empty, &SearchRequest::for_test()).unwrap();
        assert!(found.cards.is_empty());
        assert_eq!(found.total_pages, 0);
        let fallback = with_city(Mock::default()
            .with(&search_url("leipzig",20,1,"",false), 200, &card(1))
            .with(&search_url("leipzig",20,2,"",true), 200,
                &json!({"content": card(2), "distanceSearched":"20", "pagination":{"totalPages":2}}).to_string()));
        let result = fetch_search_using(&fallback, &SearchRequest::for_test()).unwrap();
        assert_eq!(result.cards.len(), 2);
    }

    #[test]
    fn total_pages_without_count_never_turns_invalid_card_markup_into_empty_results() {
        let html = r#"<div class="ladies-card"><a class="unknown-card" href="/something"></a></div>
            totalPages: Number.parseInt(replaceWithDefault("1", 1))"#;
        let client = with_city(
            Mock::default()
                .with(&search_url("leipzig", 20, 1, "", false), 200, html)
                .with(&search_url("leipzig", 20, 1, "", true), 404, ""),
        );
        assert!(matches!(
            fetch_search_using(&client, &SearchRequest::for_test()),
            Err(CoreError::Source(_))
        ));
    }

    #[test]
    fn explicit_zero_advert_count_can_be_empty() {
        let html = r#"advertCount: Number.parseInt(replaceWithDefault("0", 0))
          totalPages: Number.parseInt(replaceWithDefault("1", 1))
          distanceSearched: Number.parseInt(replaceWithDefault("20", 20))"#;
        let client =
            with_city(Mock::default().with(&search_url("leipzig", 20, 1, "", false), 200, html));
        let result = fetch_search_using(&client, &SearchRequest::for_test()).unwrap();
        assert!(result.cards.is_empty());
        assert!(!result.partial);
        assert!(result.radius_verified);
    }

    #[test]
    fn absent_radius_is_explicitly_unverified_and_incomplete() {
        let first = format!(
            "{} totalPages: Number.parseInt(replaceWithDefault(\"2\",1))",
            card(1)
        );
        let client = with_city(
            Mock::default()
                .with(&search_url("leipzig", 20, 1, "", false), 200, &first)
                .with(
                    &search_url("leipzig", 20, 2, "", true),
                    200,
                    &json!({"content":card(2)}).to_string(),
                ),
        );
        let result = fetch_search_using(&client, &SearchRequest::for_test()).unwrap();
        assert_eq!(result.cards.len(), 2);
        assert!(result.partial);
        assert!(!result.radius_verified);
    }

    #[test]
    fn html_api_parser_drift_does_not_commit_silent_missing_page() {
        let first = format!(
            "{} totalPages: Number.parseInt(replaceWithDefault(\"2\",1))
             distanceSearched: Number.parseInt(replaceWithDefault(\"20\",1))",
            card(1)
        );
        let client = with_city(
            Mock::default()
                .with(&search_url("leipzig", 20, 1, "", false), 200, &first)
                .with(
                    &search_url("leipzig", 20, 2, "", true),
                    200,
                    &json!({"content":"<div class='unknown-listing'>New</div>","distanceSearched":20}).to_string(),
                )
        );
        assert!(fetch_search_using(&client, &SearchRequest::for_test()).is_err());
    }

    #[test]
    fn pagination_limit_marks_partial_response() {
        let first = format!(
            "{} totalPages: Number.parseInt(replaceWithDefault(\"4\", 1))",
            card(1)
        );
        let client =
            with_city(Mock::default().with(&search_url("leipzig", 20, 1, "", false), 200, &first));
        let mut req = SearchRequest::for_test();
        req.max_pages = 1;
        let found = fetch_search_using(&client, &req).unwrap();
        assert_eq!(found.cards.len(), 1);
        assert!(found.partial);
    }
}
