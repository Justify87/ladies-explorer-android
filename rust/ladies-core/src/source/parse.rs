use std::collections::{BTreeMap, HashSet};

use regex::Regex;
use scraper::{ElementRef, Html, Selector};
use serde_json::json;
use url::Url;

use crate::CoreError;

use super::{Card, MediaReference, Profile};

fn selector(css: &str) -> Selector {
    Selector::parse(css).expect("constant CSS selector")
}

fn clean(value: &str) -> String {
    value.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn text(node: ElementRef<'_>) -> String {
    clean(&node.text().collect::<Vec<_>>().join(" "))
}

fn first_text(root: &Html, css: &str) -> Option<String> {
    root.select(&selector(css))
        .next()
        .map(text)
        .filter(|value| !value.is_empty())
}

fn attr(root: &Html, css: &str, key: &str) -> Option<String> {
    root.select(&selector(css))
        .next()
        .and_then(|node| node.value().attr(key))
        .map(clean)
        .filter(|value| !value.is_empty())
}

pub(super) fn valid_profile_url(value: &str) -> Option<String> {
    let base = Url::parse("https://www.ladies.de/").ok()?;
    let parsed = base.join(value.trim()).ok()?;
    if parsed.scheme() != "https"
        || !matches!(parsed.host_str(), Some("www.ladies.de" | "m.ladies.de"))
        || !parsed.path().starts_with("/sex-anzeigen/")
        || parsed.username() != ""
        || parsed.password().is_some()
    {
        return None;
    }
    Some(format!("https://www.ladies.de{}", parsed.path()))
}

pub(super) fn parse_cards(html: &str) -> Vec<Card> {
    let document = Html::parse_document(html);
    let mut profiles = Vec::new();
    for card in document.select(&selector("div.ladies-card")) {
        let Some(link) = card.select(&selector("a.card-link[data-ref-id]")).next() else {
            continue;
        };
        let Some(id) = link.value().attr("data-ref-id").map(clean) else {
            continue;
        };
        if id.is_empty() || !id.bytes().all(|c| c.is_ascii_digit()) {
            continue;
        }
        let Some(url) = link.value().attr("href").and_then(valid_profile_url) else {
            continue;
        };
        let city = card
            .select(&selector(".card-city"))
            .next()
            .map(text)
            .unwrap_or_default();
        let name = card
            .select(&selector(".card-name"))
            .next()
            .map(text)
            .unwrap_or_default();
        let thumbnail = card
            .select(&selector("img.card-thumb-image"))
            .next()
            .and_then(|image| image.value().attr("src"))
            .and_then(|url| Url::parse("https://www.ladies.de/").ok()?.join(url).ok())
            .filter(|url| url.scheme() == "https")
            .map(|url| url.to_string());
        let classes = card.value().classes().collect::<Vec<_>>();
        let ai_markers = [
            "ki-kennzeichnung",
            "ki-generiert",
            "ki-bearbeitet",
            "ai-icon",
            "ai_icon",
            "basic-eu-ai",
            "eu-ai-icon",
            "artificial-intelligence",
            "artificial_intelligence",
        ];
        let has_ai_content = ai_markers
            .iter()
            .any(|marker| card.html().to_lowercase().contains(marker));
        let parts: Vec<_> = Url::parse(&url)
            .ok()
            .map(|parsed| {
                parsed
                    .path_segments()
                    .map(|segments| segments.map(str::to_owned).collect())
                    .unwrap_or_default()
            })
            .unwrap_or_default();
        let category = parts
            .iter()
            .position(|segment| segment == "sex-anzeigen")
            .and_then(|pos| parts.get(pos + 1))
            .map(|value| value.replace('-', " "))
            .unwrap_or_default();
        profiles.push(Card {
            advert_id: id,
            name,
            city,
            category,
            profile_url: url,
            thumbnail_url: thumbnail,
            has_video: card
                .select(&selector(".card-videos-marker"))
                .next()
                .is_some(),
            is_premium: classes.contains(&"card-gallery-premium"),
            has_ai_content,
        });
    }
    profiles
}

pub(super) fn parse_config(html: &str, field: &str) -> Option<u32> {
    let pattern = format!(
        r#"(?i){}\s*:\s*Number\.parseInt\(replaceWithDefault\("(-?\d+)""#,
        regex::escape(field)
    );
    let re = Regex::new(&pattern).ok()?;
    let raw = re.captures(html)?.get(1)?.as_str();
    raw.parse::<u32>().ok()
}

fn group_values(doc: &Html, root: &str, item: &str, heading: &str, value: &str) -> String {
    let mut groups: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for container in doc.select(&selector(root)) {
        for row in container.select(&selector(item)) {
            let Some(key) = row.select(&selector(heading)).next().map(text) else {
                continue;
            };
            if key.is_empty() {
                continue;
            }
            let values = groups.entry(key).or_default();
            for node in row.select(&selector(value)) {
                let text = text(node);
                if !text.is_empty() && !values.contains(&text) {
                    values.push(text);
                }
            }
        }
    }
    groups.retain(|_, values| !values.is_empty());
    serde_json::to_string(&groups).expect("string map serialization")
}

fn opening_times(doc: &Html) -> String {
    let mut hours = BTreeMap::new();
    for row in doc.select(&selector(".opening-times")) {
        let Some(day) = row
            .select(&selector(".attribute-heading strong"))
            .next()
            .map(text)
        else {
            continue;
        };
        let full = text(row);
        let value = clean(full.strip_prefix(&day).unwrap_or(&full));
        if !day.is_empty() && !value.is_empty() {
            hours.insert(day, value);
        }
    }
    if hours.is_empty() {
        for row in doc.select(&selector(r#"[itemprop="openingHoursSpecification"]"#)) {
            let day = row
                .select(&selector(r#"[itemprop="dayOfWeek"]"#))
                .next()
                .and_then(|node| node.value().attr("href"))
                .and_then(|url| url.rsplit('/').next())
                .unwrap_or("");
            let value = row
                .select(&selector(r#"[itemprop="description"]"#))
                .next()
                .map(|node| {
                    node.value()
                        .attr("content")
                        .map(clean)
                        .unwrap_or_else(|| text(node))
                })
                .unwrap_or_default();
            let translated = match day {
                "Monday" => "Montag",
                "Tuesday" => "Dienstag",
                "Wednesday" => "Mittwoch",
                "Thursday" => "Donnerstag",
                "Friday" => "Freitag",
                "Saturday" => "Samstag",
                "Sunday" => "Sonntag",
                _ => day,
            };
            if !translated.is_empty() && !value.is_empty() {
                hours.insert(translated.to_owned(), value);
            }
        }
    }
    serde_json::to_string(&hours).expect("time map serialization")
}

fn revision_token(url: &str) -> Option<String> {
    Url::parse(url)
        .ok()?
        .query_pairs()
        .find(|(key, _)| key == "h")
        .map(|(_, val)| val.into_owned())
}

fn media_url(raw: &str) -> Option<String> {
    let url = Url::parse(raw.trim().trim_end_matches([')', ',', ';'])).ok()?;
    if url.scheme() != "https" {
        return None;
    }
    let host = url.host_str()?;
    if !(host.starts_with("bilder") || host.starts_with("video"))
        || !host.ends_with(".ladies-cdn.de")
    {
        return None;
    }
    Some(url.to_string())
}

fn media_ref(
    url: &str,
    kind: &str,
    position: u32,
    photo_no: Option<u32>,
    variant: &str,
) -> MediaReference {
    MediaReference {
        url: url.to_owned(),
        kind: kind.to_owned(),
        position,
        role: if photo_no == Some(1) {
            "PRIMARY"
        } else {
            "GALLERY"
        }
        .into(),
        variant: Some(variant.to_owned()),
        revision_token: revision_token(url),
    }
}

fn gallery_media(doc: &Html, html: &str) -> Vec<MediaReference> {
    let photo_re = Regex::new(
        r#"(?i)https://bilder\d+\.ladies-cdn\.de/Bilder/\d+/\d+-([AF])(\d+)\.JPG(?:\?[^\s"'<>)]*)?"#
    ).expect("photo regex");
    let video_re = Regex::new(
        r#"(?i)https://video\d+\.ladies-cdn\.de/[^\s"'<>]+?\.(webm|mp4)(?:\?[^\s"'<>)]*)?"#,
    )
    .expect("video regex");
    let mut photos: BTreeMap<u32, (String, String)> = BTreeMap::new();
    for m in photo_re.captures_iter(html) {
        let url = m.get(0).unwrap().as_str().trim_end_matches([')', ',', ';']);
        let Some(url) = media_url(url) else { continue };
        let Some(number) = m.get(2).and_then(|m| m.as_str().parse::<u32>().ok()) else {
            continue;
        };
        let variant = m.get(1).unwrap().as_str().to_ascii_uppercase();
        if variant == "A" || !photos.contains_key(&number) {
            photos.insert(number, (url, variant));
        }
    }
    let mut ordered = Vec::new();
    let mut used_photos = HashSet::new();
    let mut used_videos = HashSet::new();
    let mut add_photo = |candidate: &str, position: u32, ordered: &mut Vec<MediaReference>| {
        let Some(found) = photo_re.captures(candidate) else {
            return;
        };
        let Some(no) = found.get(2).and_then(|m| m.as_str().parse::<u32>().ok()) else {
            return;
        };
        if !used_photos.insert(no) {
            return;
        }
        if let Some((url, variant)) = photos.get(&no) {
            ordered.push(media_ref(url, "IMAGE", position, Some(no), variant));
        }
    };
    if let Some(main) = doc
        .select(&selector(".auftrag-main-thumb[data-index]"))
        .next()
    {
        let position = main
            .value()
            .attr("data-index")
            .and_then(|value| value.parse::<u32>().ok())
            .unwrap_or(0)
            + 1;
        if let Some(src) = main
            .select(&selector("img"))
            .next()
            .and_then(|node| node.value().attr("src"))
        {
            add_photo(src, position, &mut ordered);
        }
    }
    for node in doc.select(&selector(".girl-galerie .gallery_item[data-index]")) {
        let Some(position) = node
            .value()
            .attr("data-index")
            .and_then(|s| s.parse::<u32>().ok())
            .and_then(|n| n.checked_add(1))
        else {
            continue;
        };
        let Some(href) = node.value().attr("href") else {
            continue;
        };
        if let Some(found) = video_re.find(href) {
            let url = found.as_str().to_owned();
            if used_videos.insert(url.clone()) {
                let variant = Url::parse(&url)
                    .ok()
                    .and_then(|parsed| parsed.path().rsplit('.').next().map(str::to_lowercase))
                    .unwrap_or_else(|| "mp4".into());
                ordered.push(media_ref(&url, "VIDEO", position, None, &variant));
            }
        } else {
            add_photo(href, position, &mut ordered);
        }
    }
    let mut position = ordered.iter().map(|m| m.position).max().unwrap_or(0);
    for (no, (url, variant)) in &photos {
        if used_photos.insert(*no) {
            position += 1;
            ordered.push(media_ref(url, "IMAGE", position, Some(*no), variant));
        }
    }
    for found in video_re.find_iter(html) {
        let url = found.as_str().trim_end_matches([')', ',', ';']).to_owned();
        if used_videos.insert(url.clone()) && media_url(&url).is_some() {
            position += 1;
            let variant = if url.to_ascii_lowercase().contains(".webm") {
                "webm"
            } else {
                "mp4"
            };
            ordered.push(media_ref(&url, "VIDEO", position, None, variant));
        }
    }
    ordered.sort_by_key(|m| (m.position, m.kind.clone()));
    ordered
}

pub(super) fn parse_profile(html: &str, fallback: &Card) -> Result<Profile, CoreError> {
    let doc = Html::parse_document(html);
    if doc
        .select(&selector(
            "h2.page-heading.kuenstlername, .share-advert-name strong,              .einzelansicht-text, .auftrag-main-thumb[data-index]",
        ))
        .next()
        .is_none()
    {
        return Err(CoreError::Source(
            "Response is not a recognized Ladies profile page".into(),
        ));
    }
    if let Some(found_id) = Regex::new(r#"(?i)advertId:\s*replaceWithDefault\("(\d+)""#)
        .expect("advert regex")
        .captures(html)
        .and_then(|m| m.get(1))
    {
        if found_id.as_str() != fallback.advert_id {
            return Err(CoreError::Source(format!(
                "Profile advert ID mismatch: expected {} but got {}",
                fallback.advert_id,
                found_id.as_str()
            )));
        }
    }
    let name = first_text(&doc, "h2.page-heading.kuenstlername")
        .or_else(|| first_text(&doc, ".share-advert-name strong"))
        .unwrap_or_else(|| fallback.name.clone());
    let city = first_text(
        &doc,
        ".address-details [itemprop='addressLocality'] .important",
    )
    .or_else(|| first_text(&doc, ".share-advert-place"))
    .unwrap_or_else(|| fallback.city.clone());
    if name.is_empty() && city.is_empty() {
        return Err(CoreError::Source(
            "Profile page is missing identifying content".into(),
        ));
    }
    let description = first_text(&doc, ".einzelansicht-text > div:first-child").unwrap_or_default();
    let canonical_id = Regex::new(r#"(?i)canonicalId:\s*replaceWithDefault\("([^"]*)""#)
        .expect("canonical regex")
        .captures(html)
        .and_then(|m| m.get(1))
        .map(|m| clean(m.as_str()))
        .filter(|s| !s.is_empty());
    let published_at = attr(
        &doc,
        r#"meta[property="article:published_time"]"#,
        "content",
    )
    .filter(|text| text.starts_with("20"));
    let phone = doc
        .select(&selector(r#"[itemprop="telephone"]"#))
        .next()
        .map(|node| {
            node.value()
                .attr("content")
                .map(clean)
                .unwrap_or_else(|| text(node))
        })
        .filter(|value| !value.is_empty());
    let traits = group_values(
        &doc,
        ".trait-attributes",
        ".attribute-items",
        "strong",
        ".col-xs-7 p",
    );
    let services = group_values(
        &doc,
        ".standard-attributes",
        ".list-group",
        ".attribute-heading strong",
        ".attribute-item span",
    );
    let media = gallery_media(&doc, html);
    Ok(Profile {
        advert_id: fallback.advert_id.clone(),
        name,
        city: city.strip_prefix("in ").unwrap_or(&city).to_owned(),
        description,
        postal_code: attr(&doc, r#"[itemprop="postalCode"]"#, "content")
            .or_else(|| first_text(&doc, r#"[itemprop="postalCode"]"#)),
        hidden_location: attr(&doc, ".address-details[data-ort]", "data-ort")
            .or_else(|| attr(&doc, "[data-ort]", "data-ort")),
        source_profile_id: attr(&doc, "[data-profile-id]", "data-profile-id"),
        canonical_id,
        phone_display: phone,
        published_at,
        traits_json: traits,
        services_json: services,
        opening_times_json: opening_times(&doc),
        media,
    })
}

pub(super) fn fingerprint(profile: &Profile) -> String {
    let content = json!({
        "name": profile.name,
        "city": profile.city,
        "postal_code": profile.postal_code,
        "hidden_location": profile.hidden_location,
        "description": profile.description,
        "source_profile_id": profile.source_profile_id,
        "canonical_id": profile.canonical_id,
        "phone": profile.phone_display,
        "published_at": profile.published_at,
        "traits": profile.traits_json,
        "services": profile.services_json,
        "opening": profile.opening_times_json,
        "media": profile.media,
    });
    use sha2::{Digest, Sha256};
    format!("{:x}", Sha256::digest(content.to_string().as_bytes()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn example_card() -> Card {
        Card {
            advert_id: "123456".into(),
            name: "Fallback".into(),
            city: "Leipzig".into(),
            category: "Huren Hostessen".into(),
            profile_url: "https://www.ladies.de/sex-anzeigen/huren-hostessen/example".into(),
            thumbnail_url: None,
            has_video: false,
            is_premium: false,
            has_ai_content: false,
        }
    }

    #[test]
    fn cards_use_legacy_selectors_and_ignore_unsafe_links() {
        let html = r#"<div class="ladies-card card-gallery-premium">
            <a class="card-link" data-ref-id="123456" href="/sex-anzeigen/huren-hostessen/example"></a>
            <div class="card-name">Anna</div><div class="card-city">Leipzig</div>
            <img class="card-thumb-image" src="https://bilder1.ladies-cdn.de/Bilder/12/456-F1.JPG">
            <span class="card-videos-marker"></span><span class="ki-kennzeichnung"></span>
            </div>
            <div class="ladies-card"><a class="card-link" data-ref-id="7" href="https://evil.example/sex-anzeigen/7"></a></div>"#;
        let cards = parse_cards(html);
        assert_eq!(cards.len(), 1);
        assert_eq!(cards[0].advert_id, "123456");
        assert_eq!(cards[0].category, "huren hostessen");
        assert!(cards[0].has_video && cards[0].is_premium && cards[0].has_ai_content);
    }

    #[test]
    fn search_config_uses_embedded_site_metadata() {
        let html = r#"totalPages: Number.parseInt(replaceWithDefault("3", 1)), distanceSearched: Number.parseInt(replaceWithDefault("20", 5))"#;
        assert_eq!(parse_config(html, "totalPages"), Some(3));
        assert_eq!(parse_config(html, "distanceSearched"), Some(20));
    }

    #[test]
    fn extracts_profile_fields_and_mixed_media_order() {
        let html = r#"<h2 class="page-heading kuenstlername">Anna</h2>
            <div class="address-details" data-ort="12345 Leipzig"><span itemprop="addressLocality"><span class="important">in Leipzig</span></span></div>
            <span data-profile-id="owner-1"></span>
            <span itemprop="postalCode" content="04229"></span>
            <span itemprop="telephone" content="+49 123456"></span>
            <div class="einzelansicht-text"><div>Hello world</div></div>
            <div class="trait-attributes"><div class="attribute-items"><strong>Haare</strong><div class="col-xs-7"><p>Blond</p></div></div></div>
            <div class="standard-attributes"><div class="list-group"><div class="attribute-heading"><strong>Service</strong></div><div class="attribute-item"><span>ZK</span></div></div></div>
            <div class="auftrag-main-thumb" data-index="0"><img src="https://bilder1.ladies-cdn.de/Bilder/12/456-F1.JPG"/></div>
            <div class="girl-galerie"><a class="gallery_item gallery_video" data-index="1" href="https://video1.ladies-cdn.de/clip.webm"></a>
            <a class="gallery_item" data-index="2" href="https://bilder1.ladies-cdn.de/Bilder/12/456-F2.JPG"></a></div>
            <img src="https://bilder1.ladies-cdn.de/Bilder/12/456-A1.JPG"/>
            <script>advertId: replaceWithDefault("123456", ""), canonicalId: replaceWithDefault("c-1", "")</script>"#;
        let p = parse_profile(html, &example_card()).unwrap();
        assert_eq!(p.name, "Anna");
        assert_eq!(p.city, "Leipzig");
        assert_eq!(p.postal_code.as_deref(), Some("04229"));
        assert_eq!(p.media.len(), 3);
        assert_eq!(p.media[0].position, 1);
        assert!(p.media[0].url.contains("-A1.JPG"));
        assert_eq!(p.media[1].kind, "VIDEO");
        assert_eq!(p.media[2].position, 3);
        assert!(p.traits_json.contains("Blond"));
    }

    #[test]
    fn rejects_unrecognized_challenge_page_even_with_search_fallback() {
        let html = "<html><body><h1>Checking your browser</h1></body></html>";
        assert!(matches!(
            parse_profile(html, &example_card()),
            Err(CoreError::Source(_))
        ));
    }

    #[test]
    fn rejects_mismatched_advert_id() {
        let h = r#"<h2 class="page-heading kuenstlername">Other</h2><script>advertId: replaceWithDefault("987", "")</script>"#;
        assert!(parse_profile(h, &example_card()).is_err());
    }
}
