use std::collections::{HashMap, HashSet};
use std::io::Read;
use std::thread;
use std::time::Duration;

use reqwest::blocking::Client;
use serde_json::Value;
use url::Url;

use crate::CoreError;

use super::{Card, SearchRequest, filter, parse};

const BASE: &str = "https://www.ladies.de";
const MAX_RESPONSE_BYTES: u64 = 8 * 1024 * 1024;

struct Response {
    status: u16,
    body: String,
}

trait Transport {
    fn get(&self, url: &str) -> Result<Response, CoreError>;
    fn wait_between_pages(&self) {}
}

pub(super) struct SourceHttp {
    client: Client,
}

impl SourceHttp {
    pub(super) fn new() -> Result<Self, CoreError> {
        let client = Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(25))
            .user_agent(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) \
                 AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36",
            )
            .build()
            .map_err(|e| CoreError::Source(format!("HTTP initialization failed: {e}")))?;
        Ok(Self { client })
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

impl Transport for SourceHttp {
    fn get(&self, url: &str) -> Result<Response, CoreError> {
        let parsed =
            Url::parse(url).map_err(|_| CoreError::InvalidInput("Malformed source URL".into()))?;
        if parsed.scheme() != "https" || parsed.host_str() != Some("www.ladies.de") {
            return Err(CoreError::InvalidInput(
                "HTTP is restricted to www.ladies.de".into(),
            ));
        }
        let mut response = self
            .client
            .get(parsed)
            .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.7")
            .header("DNT", "1")
            .send()
            .map_err(|e| CoreError::Source(format!("Source HTTP request failed: {e}")))?;
        let status = response.status().as_u16();
        let mut bytes = Vec::new();
        response
            .by_ref()
            .take(MAX_RESPONSE_BYTES + 1)
            .read_to_end(&mut bytes)
            .map_err(|e| CoreError::Source(format!("Source HTTP read failed: {e}")))?;
        if bytes.len() as u64 > MAX_RESPONSE_BYTES {
            return Err(CoreError::Source(
                "Source response size limit exceeded".into(),
            ));
        }
        let body = String::from_utf8(bytes)
            .map_err(|_| CoreError::Source("Source response is not UTF-8".into()))?;
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
}

fn resolve_location<T: Transport>(client: &T, city: &str) -> (String, String) {
    let fallback = (filter::slugify_city(city), city.trim().to_owned());
    let mut url = Url::parse(&format!("{BASE}/api/location")).expect("constant source URL");
    url.query_pairs_mut()
        .append_pair("searchString", city)
        .append_pair("searchAddAlleRegionen", "false");
    let Ok(response) = client.get(url.as_str()) else {
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
                    .find_map(|key| map.get(*key).and_then(Value::as_str))
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

fn parse_api(response: Response) -> Result<Option<Value>, CoreError> {
    if response.status == 404 {
        return Ok(None);
    }
    if response.status != 200 {
        return Err(CoreError::Source(format!(
            "Search API HTTP status {}",
            response.status
        )));
    }
    let payload: Value = serde_json::from_str(&response.body)
        .map_err(|e| CoreError::Source(format!("Invalid source search API payload: {e}")))?;
    if !payload.is_object() {
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
    number(payload.get("distanceSearched")).is_none_or(|value| value == requested_radius)
}

fn append_page(profiles: &mut Vec<Card>, payload: &Value) -> Result<(), CoreError> {
    let content = payload
        .get("content")
        .and_then(Value::as_str)
        .ok_or_else(|| CoreError::Source("Search API page missing HTML content".into()))?;
    profiles.extend(parse::parse_cards(content));
    Ok(())
}

pub(super) fn fetch_search(
    client: &SourceHttp,
    request: &SearchRequest,
) -> Result<DownloadedSearch, CoreError> {
    fetch_search_using(client, request)
}

fn fetch_search_using<T: Transport>(
    client: &T,
    request: &SearchRequest,
) -> Result<DownloadedSearch, CoreError> {
    filter::validate_request(request)?;
    let filter_path = filter::filter_path(request)?;
    let (city_slug, city) = resolve_location(client, &request.city);
    let first_url = search_url(&city_slug, request.radius_km, 1, &filter_path, false);
    let first = client.get(&first_url)?;
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
        });
    }
    if first.status != 200 {
        return Err(CoreError::Source(format!(
            "Search HTTP status {}",
            first.status
        )));
    }
    let total_from_html = parse::parse_config(&first.body, "totalPages");
    let server_count = parse::parse_config(&first.body, "advertCount");
    let radius = parse::parse_config(&first.body, "distanceSearched");
    if radius.is_some_and(|radius| radius != request.radius_km) {
        return Err(CoreError::Source(format!(
            "Search radius was expanded by the source: requested {} km, returned {} km",
            request.radius_km,
            radius.unwrap_or_default()
        )));
    }
    let mut cards = parse::parse_cards(&first.body);
    if cards.is_empty() && total_from_html.is_none() && server_count.is_none() {
        return Err(CoreError::Source(
            "Search HTML does not contain expected cards or pagination metadata".into(),
        ));
    }
    if cards.is_empty() && server_count.is_some_and(|count| count > 0) {
        return Err(CoreError::Source(
            "Search advert count is nonzero but cards are missing".into(),
        ));
    }

    let mut total_pages = total_from_html.unwrap_or(1).max(1);
    let mut fetched_pages = 1u32;
    let mut boundary_stopped = false;
    let mut partial = false;
    let mut start = 2;
    let mut server_count = server_count;

    if total_from_html.is_none() {
        // Legacy source behavior: probe API page 2 to discover pagination metadata.
        // Fail closed if this page silently expands the requested radius.
        client.wait_between_pages();
        let response = parse_api(client.get(&search_url(
            &city_slug,
            request.radius_km,
            2,
            &filter_path,
            true,
        ))?)?;
        if let Some(payload) = response {
            fetched_pages += 1;
            if !distance_matches(&payload, request.radius_km) {
                boundary_stopped = true;
                partial = true;
                total_pages = 2;
            } else {
                total_pages = number(payload.pointer("/pagination/totalPages"))
                    .unwrap_or(2)
                    .max(2);
                server_count = number(payload.get("advertCount"));
                if request.max_pages >= 2 {
                    append_page(&mut cards, &payload)?;
                } else {
                    partial = true;
                }
            }
        } else {
            total_pages = 1;
        }
        start = 3;
    }

    if !boundary_stopped {
        for page in start..=total_pages.min(request.max_pages) {
            client.wait_between_pages();
            let response = parse_api(client.get(&search_url(
                &city_slug,
                request.radius_km,
                page,
                &filter_path,
                true,
            ))?)?;
            let Some(payload) = response else {
                partial = true;
                break;
            };
            fetched_pages += 1;
            if !distance_matches(&payload, request.radius_km) {
                boundary_stopped = true;
                partial = true;
                break;
            }
            append_page(&mut cards, &payload)?;
        }
        if total_pages > request.max_pages {
            partial = true;
        }
    }

    let mut ids = HashSet::new();
    cards.retain(|card| ids.insert(card.advert_id.clone()));

    Ok(DownloadedSearch {
        cards,
        city,
        city_slug,
        server_count,
        total_pages,
        fetched_pages,
        partial,
        radius_boundary_stopped: boundary_stopped,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

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
