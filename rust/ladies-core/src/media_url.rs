use url::Url;

use crate::CoreError;

// The HTML parser and HTTP downloader must agree on permitted media hosts.
pub(crate) fn validate_media_url(raw: &str, kind: Option<&str>) -> Result<Url, CoreError> {
    let base = Url::parse("https://www.ladies.de/").expect("constant source URL");
    let url = base
        .join(raw.trim())
        .map_err(|_| CoreError::Media("Malformed source media URL".into()))?;
    let host = url.host_str().unwrap_or_default();
    let numbered = |prefix: &str| {
        host.strip_prefix(prefix)
            .and_then(|s| s.strip_suffix(".ladies-cdn.de"))
            .is_some_and(|digits| !digits.is_empty() && digits.bytes().all(|b| b.is_ascii_digit()))
    };
    let source = matches!(host, "www.ladies.de" | "m.ladies.de");
    let accepted = match kind {
        Some("IMAGE") => source || numbered("bilder"),
        Some("VIDEO") => source || numbered("video"),
        None => source || numbered("bilder") || numbered("video"),
        _ => false,
    };
    if url.scheme() != "https"
        || url.port().is_some()
        || !url.username().is_empty()
        || url.password().is_some()
        || !accepted
    {
        return Err(CoreError::Media(
            "Media host or scheme is not allowed".into(),
        ));
    }
    Ok(url)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn permitted_media_urls_preserve_kind_and_resolve_relative_paths() {
        for (raw, kind, expected) in [
            (
                "https://bilder1.ladies-cdn.de/Bilder/1.jpg",
                Some("IMAGE"),
                "https://bilder1.ladies-cdn.de/Bilder/1.jpg",
            ),
            (
                "https://video2.ladies-cdn.de/a.mp4",
                Some("VIDEO"),
                "https://video2.ladies-cdn.de/a.mp4",
            ),
            (
                "https://m.ladies.de/Bilder/gallery.jpg",
                Some("IMAGE"),
                "https://m.ladies.de/Bilder/gallery.jpg",
            ),
            (
                "/Bilder/a.jpg?h=revision",
                Some("IMAGE"),
                "https://www.ladies.de/Bilder/a.jpg?h=revision",
            ),
            (
                "//video12.ladies-cdn.de/a.mp4",
                None,
                "https://video12.ladies-cdn.de/a.mp4",
            ),
        ] {
            assert_eq!(validate_media_url(raw, kind).unwrap().as_str(), expected);
        }
    }

    #[test]
    fn disallowed_media_targets_fail_closed() {
        for (raw, kind) in [
            (
                "https://bilder1.ladies-cdn.de.evil.test/a.jpg",
                Some("IMAGE"),
            ),
            ("http://bilder1.ladies-cdn.de/a.jpg", Some("IMAGE")),
            ("https://127.0.0.1/a.jpg", Some("IMAGE")),
            ("https://bilder.ladies-cdn.de/a.jpg", Some("IMAGE")),
            ("https://bilderx.ladies-cdn.de/a.jpg", Some("IMAGE")),
            ("https://bilder1.ladies-cdn.de/a.jpg", Some("VIDEO")),
            ("https://video2.ladies-cdn.de/a.mp4", Some("IMAGE")),
            ("https://bilder1.ladies-cdn.de:444/a.jpg", Some("IMAGE")),
            ("https://user@bilder1.ladies-cdn.de/a.jpg", Some("IMAGE")),
            (
                "https://user:pass@bilder1.ladies-cdn.de/a.jpg",
                Some("IMAGE"),
            ),
            ("https://www.ladies.de/a.jpg", Some("OTHER")),
        ] {
            assert!(
                matches!(
                    validate_media_url(raw, kind),
                    Err(CoreError::Media(reason))
                        if reason == "Media host or scheme is not allowed"
                ),
                "{raw} ({kind:?})"
            );
        }
    }
}
