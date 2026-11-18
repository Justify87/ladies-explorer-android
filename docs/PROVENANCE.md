# Provenance of the Android/Rust scaffold

Technical reference:
- https://github.com/Chethan616/Artefact
- Pin: d003a6a66cdc7bcb395dfb8e9390f90a119acc4c (2026-11-17)
- Original project: Artefact / ClearPDF by Chethan616, ArtCraft Team and
  PdfCraft contributors; MIT OR Apache-2.0.

## What is adapted

The Android variant-aware cargo-ndk Gradle task, variant source-directory
registration and UniFFI binding-generation flow in app/build.gradle.kts
are simplified adaptations of the corresponding mechanisms in
Artefact's android/app/build.gradle.kts.

The Gradle wrapper launcher scripts, wrapper JAR and distribution properties
originate from Artefact's android/gradle wrapper setup. The versions catalog
uses the validated Android/Compose/UniFFI/JNA dependency baseline from Artefact,
but its contents are reduced to what this application actually uses.

The Android/Compose/NDK/Gradle and UniFFI versions generally follow the pinned Artefact
reference. Kotlin 2.3.20 is used instead of upstream 2.3.21 because the latter
failed plugin resolution in GitHub Actions. Android SDK 37.0 is installed by its
actual SDK Manager package ID, not android-37.

The Rust core, UniFFI-exposed API, SQLite domain storage, Compose screens,
Android lifecycle, app theme and project documentation are authored for
Ladies Explorer, not copied from Artefact.

## What is deliberately not adopted

- Artefact's entire Rust document engine and its vendored crates.
- Any PDF/Office/OCR processing, rendering, file viewing or PDF automation.
- Artefact UI screens, document state, widgets, file operations or media editors.
- Artefact's Liquid Glass backdrop module, fonts and document assets.
- Its release signing, F-Droid and product publishing configuration.

License for adapted Artefact code: Apache-2.0, see LICENSE and NOTICE.
Additional third-party dependencies are declared in [the Android version catalog](../gradle/libs.versions.toml) and [Rust manifest](../rust/ladies-core/Cargo.toml). This file tracks the adapted Artefact code; see [LICENSE](../LICENSE) and [NOTICE](../NOTICE) for the retained attribution.
