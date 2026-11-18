use std::collections::{BTreeMap, BTreeSet};
use std::sync::OnceLock;

use serde::Deserialize;

use crate::CoreError;

use super::SearchRequest;

#[derive(Deserialize)]
struct Catalog {
    categories: BTreeMap<String, String>,
    groups: BTreeMap<String, String>,
    values: BTreeMap<String, BTreeMap<String, String>>,
    order: Vec<String>,
}

fn catalog() -> &'static Catalog {
    static CATALOG: OnceLock<Catalog> = OnceLock::new();
    CATALOG.get_or_init(|| {
        serde_json::from_str(include_str!("filter_catalog.json"))
            .expect("the checked-in source filter catalog is valid JSON")
    })
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SourceFilterGroup {
    pub key: String,
    pub options: Vec<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SourceFilterCatalog {
    pub categories: Vec<String>,
    pub groups: Vec<SourceFilterGroup>,
}

pub(crate) fn available() -> SourceFilterCatalog {
    let catalog = catalog();
    let mut categories = vec![String::new()];
    for category in catalog.categories.values() {
        if !categories.contains(category) {
            categories.push(category.clone());
        }
    }
    let groups = catalog
        .order
        .iter()
        .filter_map(|key| {
            catalog.values.get(key).map(|values| SourceFilterGroup {
                key: key.clone(),
                options: values.keys().cloned().collect(),
            })
        })
        .collect();
    SourceFilterCatalog { categories, groups }
}

fn invalid(why: impl Into<String>) -> CoreError {
    CoreError::InvalidInput(why.into())
}

pub(super) fn slugify_city(input: &str) -> String {
    let mut out = String::new();
    for letter in input.trim().to_lowercase().chars() {
        let normalized = match letter {
            'ä' => "ae",
            'ö' => "oe",
            'ü' => "ue",
            'ß' => "ss",
            ',' | '/' | ' ' | '-' => "-",
            'a'..='z' | '0'..='9' => {
                out.push(letter);
                continue;
            }
            _ => continue,
        };
        if !out.is_empty() && !out.ends_with('-') {
            out.push_str(normalized);
        }
    }
    let slug = out.trim_matches('-').to_owned();
    if slug.is_empty() {
        "leipzig".into()
    } else {
        slug
    }
}

pub(crate) fn validate_request(request: &SearchRequest) -> Result<(), CoreError> {
    if request.city.trim().is_empty() || request.city.chars().count() > 120 {
        return Err(invalid("City must contain 1–120 characters"));
    }
    if ![5, 10, 20, 30, 50, 70, 100].contains(&request.radius_km) {
        return Err(invalid("Radius must be 5, 10, 20, 30, 50, 70 or 100 km"));
    }
    if !(18..=60).contains(&request.age_min) || !(request.age_min..=60).contains(&request.age_max) {
        return Err(invalid("Age range must be within 18–60"));
    }
    if !(140..=200).contains(&request.height_min)
        || !(request.height_min..=200).contains(&request.height_max)
    {
        return Err(invalid("Height range must be within 140–200"));
    }
    if !(1..=50).contains(&request.max_pages) {
        return Err(invalid("Page limit must be between 1 and 50"));
    }
    filter_path(request)?;
    Ok(())
}

/// Normalize source aliases and technical filter values to the keys shown by Compose.
pub(crate) fn canonicalize_request(mut request: SearchRequest) -> Result<SearchRequest, CoreError> {
    validate_request(&request)?;
    let catalog = catalog();
    request.category = catalog
        .categories
        .get(request.category.trim_matches('/').trim())
        .ok_or_else(|| invalid("Unknown source category"))?
        .clone();

    let selected: BTreeMap<String, Vec<String>> = serde_json::from_str(&request.filters_json)
        .map_err(|e| invalid(format!("Invalid selected filters JSON: {e}")))?;
    let mut normalized: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    for (raw_group, raw_values) in selected {
        let group = catalog
            .groups
            .get(&raw_group)
            .ok_or_else(|| invalid(format!("Unknown filter group: {raw_group}")))?;
        let allowed = catalog
            .values
            .get(group)
            .ok_or_else(|| invalid(format!("Unknown filter values for {group}")))?;
        for raw_value in raw_values {
            let clean = raw_value.split_whitespace().collect::<Vec<_>>().join(" ");
            let label = if allowed.contains_key(&clean) {
                clean
            } else {
                allowed
                    .iter()
                    .find_map(|(name, slug)| (slug == &clean).then(|| name.clone()))
                    .ok_or_else(|| invalid(format!("Unknown filter value in {group}: {clean}")))?
            };
            normalized.entry(group.clone()).or_default().insert(label);
        }
    }
    request.filters_json = serde_json::to_string(&normalized)
        .map_err(|e| CoreError::Storage(e.to_string()))?;
    Ok(request)
}

pub(super) fn filter_path(request: &SearchRequest) -> Result<String, CoreError> {
    let catalog = catalog();
    let category = catalog
        .categories
        .get(request.category.trim_matches('/').trim())
        .ok_or_else(|| invalid("Unknown source category"))?;
    let chosen: BTreeMap<String, Vec<String>> = serde_json::from_str(&request.filters_json)
        .map_err(|e| invalid(format!("Invalid selected filters JSON: {e}")))?;
    let mut groups: BTreeMap<String, Vec<String>> = BTreeMap::new();

    if request.age_min > 18 || request.age_max < 60 {
        groups.insert(
            "alter".into(),
            vec![request.age_min.to_string(), request.age_max.to_string()],
        );
    }
    if request.height_min > 140 || request.height_max < 200 {
        groups.insert(
            "groesse".into(),
            vec![
                request.height_min.to_string(),
                request.height_max.to_string(),
            ],
        );
    }
    for (raw_group, selected) in chosen {
        if selected.is_empty() {
            continue;
        }
        let mapped = catalog
            .groups
            .get(&raw_group)
            .ok_or_else(|| invalid(format!("Unknown filter group: {raw_group}")))?;
        let allowed = catalog
            .values
            .get(mapped)
            .ok_or_else(|| invalid(format!("Unknown filter values for {mapped}")))?;
        let output = groups.entry(mapped.clone()).or_default();
        for raw_value in selected {
            let clean = raw_value.split_whitespace().collect::<Vec<_>>().join(" ");
            let value = if let Some(mapped_value) = allowed.get(&clean) {
                mapped_value.as_str()
            } else if allowed.values().any(|known| known == &clean) {
                clean.as_str()
            } else {
                return Err(invalid(format!(
                    "Unknown filter value in {mapped}: {clean}"
                )));
            };
            if !output.iter().any(|existing| existing == value) {
                output.push(value.to_owned());
            }
        }
    }
    let segment = catalog
        .order
        .iter()
        .filter_map(|group| {
            groups
                .get(group)
                .map(|values| format!("{group}:{}", values.join(",")))
        })
        .collect::<Vec<_>>()
        .join(";");
    Ok([category.as_str(), segment.as_str()]
        .into_iter()
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>()
        .join("/"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn category_aliases_and_legacy_filter_slugs() {
        let mut request = SearchRequest::for_test();
        request.category = "locations".into();
        request.age_min = 25;
        request.filters_json =
            r#"{"service":["ZK","GV","ZK"],"treffpunkt":["Hausbesuche"],"figur":["schlank"]}"#
                .into();
        let path = filter_path(&request).unwrap();
        assert_eq!(
            path,
            "bordelle-laufhaeuser/alter:25,60;figur:schlank;service:zungenkuss,gv-sex;treff:treffen-haus"
        );
    }

    #[test]
    fn rejects_unknown_groups_values_and_invalid_radius() {
        let mut req = SearchRequest::for_test();
        req.filters_json = r#"{"unknown":["value"]}"#.into();
        assert!(filter_path(&req).is_err());
        req.filters_json = r#"{"service":["invented"]}"#.into();
        assert!(filter_path(&req).is_err());
        req.filters_json = "{}".into();
        req.radius_km = 13;
        assert!(validate_request(&req).is_err());
    }

    #[test]
    fn canonicalizes_imported_aliases_and_slugs_for_the_native_filter_ui() {
        let mut request = SearchRequest::for_test();
        request.category = "locations".into();
        request.filters_json =
            r#"{"treffpunkt":["treffen-haus"],"treff":["Hausbesuche"],"service":["zungenkuss","ZK"]}"#
                .into();
        let canonical = canonicalize_request(request).unwrap();
        assert_eq!(canonical.category, "bordelle-laufhaeuser");
        let selected: serde_json::Value =
            serde_json::from_str(&canonical.filters_json).unwrap();
        assert_eq!(selected["treff"], serde_json::json!(["Hausbesuche"]));
        assert_eq!(selected["service"], serde_json::json!(["ZK"]));
        assert!(selected.get("treffpunkt").is_none());
        assert_eq!(
            filter_path(&canonical).unwrap(),
            "bordelle-laufhaeuser/service:zungenkuss;treff:treffen-haus"
        );
    }

    #[test]
    fn city_slug_preserves_german_transliteration() {
        assert_eq!(slugify_city("Düsseldorf / Mitte"), "duesseldorf-mitte");
        assert_eq!(slugify_city("  Leipzig  "), "leipzig");
        assert_eq!(slugify_city(""), "leipzig");
    }
}
