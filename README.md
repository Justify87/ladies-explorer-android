# Ladies Explorer Android

A native, on-device Android application scaffold with **Kotlin**, **Jetpack Compose /
Material 3 Expressive**, **Rust**, **UniFFI** and **SQLite**.

This is intentionally **a technical skeleton, not a functional port of Ladies Explorer**.
No person profiles, scraping, identity matching, map, swiping or personal data have been
imported. Those features belong to the subsequent migration milestones.

## Architecture

- **Android / Kotlin**: Compose UI, application lifecycle, platform integration, ViewModels.
- **Rust**: a native library, the only owner of application persistence and domain logic.
- **UniFFI + JNA**: generated Kotlin bindings; no hand-written JNI or REST service.
- **SQLite**: local app-private database accessed only by Rust.
- **Gradle + cargo-ndk**: Android variant builds compile the native library and generate
  Kotlin bindings automatically into build directories.

There is no embedded Python, Flask, Node.js, messaging client or price-extraction model.

## Scaffold features

- Three native placeholder tabs: Entdecken, Suche and Einstellungen.
- Material 3 theme with Android dynamic colors.
- Settings tab exercises Rust → SQLite → Rust → Kotlin round trips and error handling.
- A Rust unit test exercises upsert, read and the invalid-path error.
- Debug and release build variants; debug installs under a separate application ID.
- GitHub Actions checks Rust tests and assembles the arm64 debug APK.

## Build

Requirements: Java 21, Android SDK Platform 37, Android NDK 29.0.14206865,
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
- Install the APK and open Einstellungen; save, refresh and read the SQLite test value.

The GitHub Actions run is the authoritative verification of the Android build.
Do not claim installation on a physical device until it has actually been tested.

## What comes next

Search and profile extraction, SQLite schema migration, media archiving, person
identity/history, swipe discovery and MapLibre will be implemented separately.
WhatsApp is an external-open button only; integrated messaging and AI price
extraction are explicitly excluded.

## Upstream provenance

See [docs/PROVENANCE.md](docs/PROVENANCE.md). The build architecture is adapted
from Artefact, not its features. No Artefact document engine, OCR model, widget,
PDF renderer or liquid-glass module is bundled.

## License

Apache-2.0; see LICENSE and NOTICE.
