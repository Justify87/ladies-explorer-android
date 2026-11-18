# Ladies Explorer Android

A native, on-device Android application scaffold with **Kotlin**, **Jetpack Compose /
Material 3 Expressive**, **Rust**, **UniFFI** and **SQLite**.

This is a staged native migration, not yet a complete Ladies Explorer replacement.
The Android app imports legacy SQLite/media bundles locally and provides a native Rust
search/profile extraction engine. Native discovery, search/filter, saved searches and profile screens are implemented in M05; maps remain planned for M06. No personal records are stored in this repository.

## Architecture

- **Android / Kotlin**: Compose UI, application lifecycle, platform integration, ViewModels.
- **Rust**: a native library, the only owner of application persistence and domain logic.
- **UniFFI + JNA**: generated Kotlin bindings; no hand-written JNI or REST service.
- **SQLite**: local app-private database accessed only by Rust.
- **Gradle + cargo-ndk**: Android variant builds compile the native library and generate
  Kotlin bindings automatically into build directories.

There is no embedded Python, Flask, Node.js, messaging client or price-extraction model.

## Scaffold features

- Four native tabs: Entdecken, Suche, Favoriten and Einstellungen, with full-screen profile details.
- Material 3 theme with Android dynamic colors.
- Settings tab shows the Rust-owned database schema version and domain counts.
- Rust unit tests verify schema identity, versioned migrations, saved-search history, source/listing uniqueness, foreign keys, paging and persistence.
- Debug and release build variants; debug installs under a separate application ID.
- GitHub Actions checks Rust tests and assembles the arm64 debug APK.

## Build

Requirements: Java 21, Android SDK Platform 37.0, Android NDK 29.0.14206865,
Rust 1.99, the Android Rust targets, and cargo-ndk 4.1.2. Android Studio can
open the repository root.

Run on macOS/Linux:

    rustup target add aarch64-linux-android x86_64-linux-android
    cargo install cargo-ndk --version 4.1.2 --locked
    ./gradlew :app:assembleDebug -Pladies.abis=arm64-v8a

The APK is written to app/build/outputs/apk/debug/. For a developer machine
without the build toolchain, GitHub Actions builds the APK as an artifact.

The Gradle build automatically runs:
1. cargo-ndk to produce libladies_core.so,
2. uniffi-bindgen to generate Kotlin bindings from that compiled library,
3. the Android application compiler and APK packaging.

### Validation

- cargo fmt --all -- --check (in rust/)
- cargo test --workspace (in rust/)
- ./gradlew :app:assembleDebug -Pladies.abis=arm64-v8a
- Install the APK and open Einstellungen; check schema v2 and refresh the domain counts.

The GitHub Actions run is the authoritative verification of the Android build.
Do not claim installation on a physical device until it has actually been tested.

## Import existing web-app data

Create a versioned ZIP with the Bash exporter and import it using Android's
document picker in Settings. This requires no Python on macOS or Android:

    ./tools/export-legacy.sh /path/to/ladies-webapp/data ~/Downloads/ladies-export.zip

See [docs/DATA-IMPORT.md](docs/DATA-IMPORT.md) for prerequisites, exact
inclusion/exclusion rules, integrity checks and caveats. The generated ZIP
contains personal records; never commit or upload it to GitHub.

## Live source search and profile parsing

M03 adds a typed Rust/UniFFI search API: configured location, source filter
mapping, radius-aware pagination, deduplicated results, SQLite search runs and
profile snapshot extraction. The Compose Search tab uses the Rust filter catalog and native saved-search history.
Source HTML may change and must be verified on-device with real pages.

See [docs/SOURCE-M03.md](docs/SOURCE-M03.md) for behavior, compatibility
choices, offline tests and what is explicitly deferred to M04/M05.

## Lokale Diagnose

Rust-Tracing und Kotlin/Kermit erfassen app-private, rotierende TXT-Logs mit INFO-/DEBUG-Stufen. Ein optionaler HTTP-Recorder sammelt in einer begrenzten Sitzung Metadaten echter Rust- und Coil-3/OkHttp-Netzwerkrequests sowie ausdrücklich aktivierte HTML-/JSON-Antworten (max. 2 MiB pro Textantwort, 25 MiB insgesamt). Die Aufzeichnung ist auch nach jedem Neustart **aus**. Unter Einstellungen lassen sich TXT-Logs und HAR 1.2 als Dateien speichern oder bewusst teilen. Keine automatische Weitergabe.

Anleitung und Datenschutz: [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md).

## What comes next

M04 adds transition-based profile history, explicit identity candidates/merging,
local SHA-256/dHash image archiving and optional video archiving. See
[docs/M04-IDENTITY-MEDIA-HISTORY.md](docs/M04-IDENTITY-MEDIA-HISTORY.md).
The native profile UI and swipe discovery are implemented in M05. See [docs/M05-NATIVE-EXPLORER.md](docs/M05-NATIVE-EXPLORER.md). MapLibre follows in M06.
WhatsApp is an external-open button only; integrated messaging and AI price
extraction are explicitly excluded.

## Upstream provenance

See [docs/PROVENANCE.md](docs/PROVENANCE.md) and [docs/DATA-MIGRATION.md](docs/DATA-MIGRATION.md). The build architecture is adapted
from Artefact, not its features. No Artefact document engine, OCR model, widget,
PDF renderer or liquid-glass module is bundled.

## License

Apache-2.0; see LICENSE and NOTICE.
