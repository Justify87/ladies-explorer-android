#!/usr/bin/env python3
"""Conservative source regression gate, not a sandbox or proof of runtime behavior."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
app = root / "app" / "src"
forbidden = re.compile(
    r"\b(?:okhttp3|retrofit2|ktor\.client|com\.android\.volley|java\.net|"
    r"javax\.net|android\.webkit|HttpURLConnection|URLConnection|"
    r"OkHttpClient|OkHttpNetworkFetcherFactory|KtorNetworkFetcherFactory|"
    r"WebView|DownloadManager|openConnection|openStream|Socket|SSLSocket)\b"
)
errors = []
for path in sorted(app.rglob("*")):
    if path.suffix not in {".kt", ".java"}:
        continue
    # Exclude test sources; all production variants are covered.
    if any(part in {"test", "androidTest"} or part.startswith(("testDebug", "testRelease", "androidTest")) for part in path.relative_to(app).parts):
        continue
    for line_no, line in enumerate(path.read_text().splitlines(), 1):
        if forbidden.search(line):
            errors.append(f"{path.relative_to(root)}:{line_no}: direct transport API")
        if "NetworkFetcher.Factory(" in line and path.name != "LadiesApplication.kt":
            errors.append(f"{path.relative_to(root)}:{line_no}: unapproved network fetcher")
for path in [root / "app/build.gradle.kts", root / "gradle/libs.versions.toml"]:
    for line_no, line in enumerate(path.read_text().splitlines(), 1):
        if re.search(r"coil[-.]network[-.](?:okhttp|ktor)|(?:com\.squareup\.okhttp|com\.squareup\.retrofit|io\.ktor|com\.android\.volley)", line):
            errors.append(f"{path.relative_to(root)}:{line_no}: direct HTTP dependency")
loader = app / "main/java/de/justify87/ladiesexplorer/LadiesApplication.kt"
if "RustImageNetworkClient(coreStore::loadDisplayImage)" not in loader.read_text():
    errors.append("Image loader must use the Rust transport")
if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print("Source HTTP architecture check passed (MapLibre SDK transport is out of scope).")
