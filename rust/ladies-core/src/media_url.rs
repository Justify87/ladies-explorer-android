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

