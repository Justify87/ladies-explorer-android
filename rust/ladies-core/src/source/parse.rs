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
        let name = card
            .select(&selector(".card-name"))
            .next()
            .map(text)
            .unwrap_or_default();
        let city = card
            .select(&selector(".card-city"))
            .next()
            .map(text)
            .unwrap_or_default();
        let thumbnail = card
            .select(&selector("img.card-thumb-image"))
            .next()
            .and_then(|image| image.value().attr("src"))
            .and_then(|value| Url::parse("https://www.ladies.de/").ok()?.join(value).ok())
            .filter(|url| url.scheme() == "https")
            .map(|url| url.to_string());
        let classes = card.value().classes().collect::<Vec<_>>();
        let html_lower = card.html().to_lowercase();
        let has_ai_content = [
            "ki-kennzeichnung",
            "ki-generiert",
            "ki-bearbeitet",
            "ai-icon",
            "ai_icon",
            "basic-eu-ai",
            "eu-ai-icon",
            "artificial-intelligence",
            "artificial_intelligence",
        ]
        .iter()
        .any(|marker| html_lower.contains(marker));
        let category = Url::parse(&url)
            .ok()
            .and_then(|parsed| {
                let segments = parsed.path_segments()?.collect::<Vec<_>>();
                let index = segments.iter().position(|s| *s == "sex-anzeigen")?;
                segments.get(index + 1).map(|value| value.replace('-', " "))
            })
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
            has_18plus: card
                .select(&selector(r#"[class*="18"], [title*="18"]"#))
                .next()
                .is_some(),
            has_stars: card
                .select(&selector(r#"[class*="star"], [title*="Star"]"#))
                .next()
                .is_some(),
        });
    }
    profiles
}

pub(super) fn is_radius_transition_placeholder(html: &str, requested_radius: u32) -> bool {
    let doc = Html::parse_fragment(html);
    let Some(next_distance) = doc
        .select(&selector(
            "lds-button#load-adverts-next-distance[data-next-distance]",
        ))
        .next()
        .and_then(|node| node.value().attr("data-next-distance"))
        .and_then(|value| value.parse::<u32>().ok())
    else {
        return false;
    };
    if next_distance <= requested_radius
        || doc
            .select(&selector("[id^='page-section-'][data-page]"))
            .next()
            .is_none()
    {
        return false;
    }
    let cards = doc.select(&selector("div.ladies-card")).collect::<Vec<_>>();
    !cards.is_empty()
        && cards.into_iter().all(|card| {
            card.value()
                .classes()
                .any(|class| class == "affiliate-card")
                && card
                    .select(&selector(
                        "a[href], [data-ref-id], img[src], source[srcset]",
                    ))
                    .next()
                    .is_none()
                && card
                    .select(&selector(".card-name, .card-city"))
                    .all(|node| text(node).trim().is_empty())
        })
}

pub(super) fn search_page_diagnostics(html: &str) -> String {
    let doc = Html::parse_document(html);
    let title = first_text(&doc, "title").unwrap_or_else(|| "ohne Titel".into());
    let title: String = title.chars().take(90).collect();
    let lowered = html.to_ascii_lowercase();
    let containers = doc.select(&selector("div.ladies-card")).count();
    let links = doc.select(&selector("a.card-link[data-ref-id]")).count();
    let plausible_links = doc
        .select(&selector("a.card-link[data-ref-id]"))
        .filter(|link| {
            link.value()
                .attr("data-ref-id")
                .is_some_and(|id| !id.is_empty() && id.bytes().all(|c| c.is_ascii_digit()))
                && link
                    .value()
                    .attr("href")
                    .and_then(valid_profile_url)
                    .is_some()
        })
        .count();
    let page_kind = if plausible_links > 0 {
        "Suchseite mit Anzeigen"
    } else if [
        "cf-chl",
        "challenge-platform",
        "checking your browser",
        "just a moment",
        "verify you are human",
        "captcha",
    ]
    .iter()
    .any(|marker| lowered.contains(marker))
    {
        "Bot-Verifizierung"
    } else if lowered.contains("ich bin über 18 jahre")
        || lowered.contains("enthält erotische inhalte")
    {
        "Altersabfrage oder Website-Startseite"
    } else if lowered.contains("anzeigen für dich gefunden") {
        "Suchseite mit Ergebnisüberschrift"
    } else {
        "nicht erkannte HTML-Seite"
    };
    format!(
        "Diagnose: Typ={page_kind}; Titel=\"{title}\"; Größe={} Bytes;          Kartencontainer={containers}; Anzeigenlinks={links}; gültige Links={plausible_links}.",
        html.len()
    )
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

fn media_url(raw: &str) -> Option<Url> {
    let base = Url::parse("https://www.ladies.de/").ok()?;
    let url = base
        .join(
            raw.trim()
                .trim_matches(['"', '\''])
                .trim_end_matches([')', ',', ';']),
        )
        .ok()?;
    let kind = if video_variant(&url).is_some() {
        "VIDEO"
    } else {
        "IMAGE"
    };
    crate::media::validate_media_url(url.as_str(), kind).ok()
}

fn image_number(url: &Url) -> Option<(u32, String)> {
    let re = Regex::new(r"(?i)^/Bilder/\d+/\d+-([AF])(\d+)\.jpe?g$").expect("photo filename regex");
    let match_ = re.captures(url.path())?;
    Some((
        match_.get(2)?.as_str().parse::<u32>().ok()?,
        match_.get(1)?.as_str().to_ascii_uppercase(),
    ))
}

fn video_variant(url: &Url) -> Option<String> {
    let path = url.path().to_ascii_lowercase();
    let extension = path.rsplit('.').next()?;
    matches!(extension, "mp4" | "webm").then(|| extension.to_owned())
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
    let mut images: BTreeMap<u32, (String, String)> = BTreeMap::new();
    let mut videos: Vec<(String, String)> = Vec::new();
    let mut video_urls = HashSet::new();

    let mut collect = |raw: &str| {
        let Some(url) = media_url(raw) else { return };
        if let Some((number, variant)) = image_number(&url) {
            let normalized = url.to_string();
            if variant == "A" || !images.contains_key(&number) {
                images.insert(number, (normalized, variant));
            }
        } else if let Some(variant) = video_variant(&url) {
            let key = url.to_string();
            if video_urls.insert(key.clone()) {
                videos.push((key, variant));
            }
        }
    };

    // A variants and supplementary photos are often outside the visible gallery.
    for node in doc.select(&selector("img")) {
        for field in ["src", "data-src"] {
            if let Some(value) = node.value().attr(field) {
                collect(value);
            }
        }
    }
    let css_url = Regex::new(r#"(?i)url\(\s*['"]?([^'")]+)['"]?\s*\)"#).expect("CSS url regex");
    for node in doc.select(&selector("[style]")) {
        if let Some(style) = node.value().attr("style") {
            for capture in css_url.captures_iter(style) {
                if let Some(value) = capture.get(1) {
                    collect(value.as_str());
                }
            }
        }
    }
    let raw_image = Regex::new(
        r#"(?i)(?:https?:)?//bilder\d+\.ladies-cdn\.de/Bilder/\d+/\d+-[AF]\d+\.JPG(?:\?[^\s"'<>)]*)?"#
    ).expect("raw photo regex");
    let raw_video = Regex::new(
        r#"(?i)(?:https?:)?//video\d+\.ladies-cdn\.de/[^\s"'<>]+?\.(?:webm|mp4)(?:\?[^\s"'<>)]*)?"#,
    )
    .expect("raw video regex");
    for value in raw_image.find_iter(html).chain(raw_video.find_iter(html)) {
        collect(value.as_str());
    }
    for node in doc.select(&selector(
        ".auftrag-main-thumb img, .girl-galerie .gallery_item",
    )) {
        if let Some(value) = node
            .value()
            .attr("src")
            .or_else(|| node.value().attr("href"))
        {
            collect(value);
        }
    }

    let mut ordered = Vec::new();
    let mut used_photos = HashSet::new();
    let mut used_videos = HashSet::new();
    let mut add_image = |raw: &str, position: u32, output: &mut Vec<MediaReference>| {
        let Some(url) = media_url(raw) else { return };
        let Some((number, _)) = image_number(&url) else {
            return;
        };
        if let Some((best_url, variant)) = images.get(&number) {
            if used_photos.insert(number) {
                output.push(media_ref(
                    best_url,
                    "IMAGE",
                    position,
                    Some(number),
                    variant,
                ));
            }
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
            .and_then(|value| value.checked_add(1))
            .unwrap_or(1);
        if let Some(src) = main
            .select(&selector("img"))
            .next()
            .and_then(|img| img.value().attr("src"))
        {
            add_image(src, position, &mut ordered);
        }
    }
    for node in doc.select(&selector(".girl-galerie .gallery_item[data-index]")) {
        let Some(position) = node
            .value()
            .attr("data-index")
            .and_then(|value| value.parse::<u32>().ok())
            .and_then(|value| value.checked_add(1))
        else {
            continue;
        };
        let Some(href) = node.value().attr("href") else {
            continue;
        };
        if let Some(url) = media_url(href) {
            if let Some(variant) = video_variant(&url) {
                let key = url.to_string();
                if used_videos.insert(key.clone()) {
                    ordered.push(media_ref(&key, "VIDEO", position, None, &variant));
                }
                continue;
            }
        }
        add_image(href, position, &mut ordered);
    }

    let mut next_position = ordered.iter().map(|item| item.position).max().unwrap_or(0);
    for (number, (url, variant)) in &images {
        if used_photos.insert(*number) {
            next_position += 1;
            ordered.push(media_ref(
                url,
                "IMAGE",
                next_position,
                Some(*number),
                variant,
            ));
        }
    }
    for (url, variant) in videos {
        if used_videos.insert(url.clone()) {
            next_position += 1;
            ordered.push(media_ref(&url, "VIDEO", next_position, None, &variant));
        }
    }
    ordered.sort_by_key(|item| (item.position, item.kind.clone()));
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

pub(super) fn fingerprint(profile: &Profile, category: Option<&str>) -> String {
    let content = json!({
        "category": category,
        "name": profile.name,
        "category": category,
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
            has_18plus: false,
            has_stars: false,
        }
    }

    #[test]
    fn november_debug_radius_transition_is_not_an_advert_card() {
        let html = r#"<div hidden id="page-section-8" data-page="8"></div>
<h3>5 Anzeigen im Umkreis bis 20 km</h3>
<div property="advertCard" prefix="globals: globals" class="card-gallery-standard affiliate-card ladies-card">
    <div class="card-thumb-image-container"></div>
    <p property="globals:city" class="card-city card-text">&nbsp;</p>
    <p property="globals:name" class="card-name card-location-name card-text">&nbsp;</p>
</div>
<lds-button id='load-adverts-next-distance' variant='primary' data-next-distance='30'>Umkreis bis 30 km</lds-button>"#;
        assert!(parse_cards(html).is_empty());
        assert!(is_radius_transition_placeholder(html, 20));
        assert!(!is_radius_transition_placeholder(html, 30));
        assert!(!is_radius_transition_placeholder(
            &html.replace("data-next-distance='30'", "data-next-distance='bad'"),
            20
        ));
    }

    #[test]
    fn radius_transition_does_not_hide_unknown_listing_markup() {
        let html = r#"<div hidden id="page-section-2" data-page="2"></div>
            <div class="ladies-card"><a href="/sex-anzeigen/new-format">Changed card</a></div>
            <lds-button id="load-adverts-next-distance" data-next-distance="30"></lds-button>"#;
        assert!(!is_radius_transition_placeholder(html, 20));
        let missing_button = r#"<div hidden id="page-section-2" data-page="2"></div>
            <div class="affiliate-card ladies-card"><p class="card-name">&nbsp;</p></div>"#;
        assert!(!is_radius_transition_placeholder(missing_button, 20));
    }

    #[test]
    fn search_diagnostics_prioritize_valid_cards_over_embedded_challenge_code() {
        let html = r#"<html><title>Leipzig</title><script>const captcha = true;</script>
            <div class="ladies-card"><a class="card-link" data-ref-id="1"
            href="/sex-anzeigen/huren-hostessen/1"></a></div></html>"#;
        let details = search_page_diagnostics(html);
        assert!(details.contains("Typ=Suchseite mit Anzeigen"));
        assert!(details.contains("gültige Links=1"));
    }
    #[test]
    fn search_diagnostics_distinguish_challenge_from_rejected_card_links() {
        let challenge = "<html><head><title>Just a moment...</title></head>\
            <body><div id='cf-chl-widget'>Checking your browser</div></body></html>";
        let details = search_page_diagnostics(challenge);
        assert!(details.contains("Bot-Verifizierung"));
        assert!(details.contains("Kartencontainer=0"));

        let unrecognized_card = "<html><head><title>Anzeigen</title></head><body>\
            <div class='ladies-card'><a class='card-link' data-ref-id='42' \
            href='/unbekannt/42'>Test</a></div></body></html>";
        let details = search_page_diagnostics(unrecognized_card);
        assert!(details.contains("Kartencontainer=1"));
        assert!(details.contains("Anzeigenlinks=1"));
        assert!(details.contains("gültige Links=0"));
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
    fn relative_gallery_urls_and_css_photos_preserve_mixed_order() {
        let html = r#"<h2 class="page-heading kuenstlername">Anna</h2>
            <div class="auftrag-main-thumb" data-index="0"><img src="//bilder1.ladies-cdn.de/Bilder/12/456-F1.JPG"/></div>
            <div style="background-image:url('//bilder1.ladies-cdn.de/Bilder/12/456-A1.JPG?h=abc')"></div>
            <div class="girl-galerie">
              <a class="gallery_item gallery_video" data-index="1" href="//video1.ladies-cdn.de/clip.webm"></a>
              <a class="gallery_item" data-index="2" href="//bilder1.ladies-cdn.de/Bilder/12/456-F2.JPG"></a>
            </div>"#;
        let profile = parse_profile(html, &example_card()).unwrap();
        assert_eq!(profile.media.len(), 3);
        assert_eq!(profile.media[0].position, 1);
        assert!(profile.media[0].url.contains("456-A1.JPG"));
        assert_eq!(profile.media[0].revision_token.as_deref(), Some("abc"));
        assert_eq!(profile.media[1].kind, "VIDEO");
        assert_eq!(profile.media[1].position, 2);
        assert_eq!(profile.media[2].position, 3);
    }

    #[test]
    fn anonymized_october_har_media_shapes_keep_revision_tokens() {
        // Synthetic IDs with URL shapes observed in the October browser archives.
        let html = r#"<h2 class="page-heading kuenstlername">Beispiel</h2>
            <div class="auftrag-main-thumb" data-index="0">
              <img src="https://bilder1.ladies-cdn.de/Bilder/321/12345678-F1.JPG?h=preview">
            </div>
            <div class="girl-galerie">
              <a class="gallery_item" data-index="1"
                 href="//bilder1.ladies-cdn.de/Bilder/321/12345678-F2.JPG?h=thumb"></a>
            </div>
            <img src="https://bilder1.ladies-cdn.de/Bilder/321/12345678-A2.JPG?h=full">"#;
        let profile = parse_profile(html, &example_card()).unwrap();
        assert_eq!(profile.media.len(), 2);
        assert_eq!(profile.media[0].position, 1);
        assert_eq!(profile.media[0].variant.as_deref(), Some("F"));
        assert_eq!(profile.media[0].revision_token.as_deref(), Some("preview"));
        assert_eq!(profile.media[1].position, 2);
        assert_eq!(profile.media[1].variant.as_deref(), Some("A"));
        assert_eq!(profile.media[1].revision_token.as_deref(), Some("full"));
    }

    #[test]
    fn fallback_video_order_follows_source_not_sorted_url() {
        let html = r#"<h2 class="page-heading kuenstlername">Anna</h2>
        <script>
          let a="https://video2.ladies-cdn.de/z.webm";
          let b="https://video1.ladies-cdn.de/a.mp4";
        </script>"#;
        let profile = parse_profile(html, &example_card()).unwrap();
        assert_eq!(profile.media.len(), 2);
        assert!(profile.media[0].url.contains("video2.ladies-cdn.de/z.webm"));
        assert!(profile.media[1].url.contains("video1.ladies-cdn.de/a.mp4"));
    }

    #[test]
    fn search_cards_preserve_legacy_markers() {
        let cards = parse_cards(
            r#"<div class="ladies-card">
              <a class="card-link" data-ref-id="123" href="/sex-anzeigen/huren-hostessen/123"></a>
              <i class="icon-18plus"></i><span class="star-marker"></span>
            </div>"#,
        );
        assert_eq!(cards.len(), 1);
        assert!(cards[0].has_18plus);
        assert!(cards[0].has_stars);
    }

    #[test]
    fn rejects_mismatched_advert_id() {
        let h = r#"<h2 class="page-heading kuenstlername">Other</h2><script>advertId: replaceWithDefault("987", "")</script>"#;
        assert!(parse_profile(h, &example_card()).is_err());
    }
}
