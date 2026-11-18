# Ladies Explorer Android

Native, lokal genutzte Android-App mit Kotlin, Jetpack Compose / Material 3 Expressive, Rust, UniFFI und SQLite. Die App übernimmt relevante Daten und fachliche Regeln der früheren Web-App in einen eigenständigen Android-Stack. Kein separater Server, kein Python-/Flask-/Node.js-Runtime, keine Cloud-Datenbank.

## Aktueller Funktionsumfang

- Entdecken (Swipe, LIKE/DISLIKE, später), native Suche und Filter, gespeicherte Suchen mit Ergebnis-Historie, Favoriten, Karte und Einstellungen.
- Profildetails mit Mediengalerie, lokalem Bildarchiv, Offline-Fallback auf gespeicherte Profile, Änderungshistorie und explizit bestätigbaren Identitätskandidaten.
- Lokaler Import vorhandener Web-App-Daten, native MapLibre-Karte mit groben Ortszentren und ausdrücklich gestarteter Geocodierung.
- Lokales Debug-/Performance-Logging und optionaler HTTP-Mitschnitt mit TXT-/HAR-Export.
- Externe Video-/WhatsApp-Intents; kein integrierter Messenger oder automatische KI-/OCR-Preisextraktion.

Der Code ist keine vollständig abgenommene Web-App-Ablösung. Offene Anforderungen (u. a. Notizen, Begegnungen und Gesamtintegration), Planabweichungen und Geräteabnahme stehen im [Roadmap-Index](docs/ROADMAP.md); die operative M05-Planung ist dort verlinkt.

## Aktive Weiterentwicklung

M05 ist zur vollständigen Frontend-Parität wieder geöffnet. Nächster Schritt: T0.1 im [Aufgabenregister](docs/M05-TASKS.md); M06/M07 nicht vorziehen. Einstieg für Agenten: [AGENTS.md](AGENTS.md).

## Architektur

Kotlin/Compose besitzt UI, Navigation und Lifecycle; Rust besitzt fachliche Logik und die app-private SQLite-Datenbank. UniFFI/JNA verbindet beide Schichten. Gradle baut die Rust-Bibliothek über cargo-ndk, erzeugt Kotlin-Bindings per UniFFI und verpackt das APK. Keine handgeschriebene JNI-/REST-Schicht. Die technischen Verträge und Module beschreibt [ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Build und Installation

Voraussetzungen: Java 21, Android SDK Platform 37.0 (SDK-Paket `platforms;android-37.0`), NDK 29.0.14206865, Rust 1.99.0, cargo-ndk 4.1.2. Maßgebliche Build-Konfiguration: [Gradle-App-Build](app/build.gradle.kts), [Android-Versionen](gradle/libs.versions.toml), [Rust-Toolchain](rust/rust-toolchain.toml), [CI-Workflow](.github/workflows/android.yml). Android Studio kann das Repository direkt öffnen.

macOS/Linux:

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
./gradlew :app:assembleDebug -Pladies.abis=arm64-v8a
```

APK unter `app/build/outputs/apk/debug/`. Für Entwickler ohne lokale Android-/Rust-Toolchain stellt [GitHub Actions](https://github.com/Justify87/ladies-explorer-android/actions) nach erfolgreichem Build das Artefakt `ladies-explorer-android-arm64-debug` bereit. Debug hat eine eigene Application-ID; Debug- und Release-Varianten können damit getrennt installiert werden. Für x86_64-Emulatoren die passende ABI bauen.

## Prüfung / CI

Der einzelne [Android-and-Rust-Workflow](.github/workflows/android.yml) läuft für PRs, Pushes auf main und manuelle Starts. Er prüft den Legacy-Exporter (Bash-Syntax und synthetischer ZIP/SQLite-Roundtrip), Rust-Formatierung, gelockte Workspace-Tests, Clippy, den Android-arm64-Build, JVM-Unit-Tests und Android Lint und veröffentlicht das Debug-APK. Die CI-Konfiguration ist maßgeblich; Rust-Abhängigkeiten werden über Cargo.lock reproduziert.

Wesentliche lokale Prüfungen:

```sh
bash -n tools/export-legacy.sh
(cd rust && cargo fmt --all -- --check && cargo test --workspace --locked && cargo clippy --workspace --all-targets --locked)
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug -Pladies.abis=arm64-v8a --stacktrace
```

Eine grüne CI beweist keinen erfolgreichen Import realer Daten, keine funktionierende Website-Suche oder native Geräte-UX. Reale Android-Abnahme separat nach [ROADMAP.md](docs/ROADMAP.md); nach Installation unter Einstellungen Datenbankstatus (Schema v2) kontrollieren.

## Datenübernahme

Einmaliges lokales Exportarchiv aus der bisherigen Web-App erzeugen und unter Einstellungen über die Android-Dateiauswahl importieren:

```sh
./tools/export-legacy.sh /pfad/zur/ladies-webapp/data ~/Downloads/ladies-export.zip
```

Format, Voraussetzungen, Ausschlüsse und Integrität: [DATA-IMPORT.md](docs/DATA-IMPORT.md). Exportarchive, private Profile und Diagnose-Rohdaten niemals in GitHub einchecken.

## Dokumentation

| Dokument | Zuständigkeit |
| --- | --- |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Native Architektur, SQLite, Identität, Historie, Medien, UI-Datenflüsse |
| [SOURCE.md](docs/SOURCE.md) | Quellen-/Suchvertrag, Filter, Pagination, Parser, gespeicherte Suchen und belegte HAR-Erkenntnisse |
| [DATA-IMPORT.md](docs/DATA-IMPORT.md) | Legacy-Export und Import |
| [DIAGNOSTICS.md](docs/DIAGNOSTICS.md) | Lokale Logs, Messungen, HTTP-Mitschnitt, Export und Prüfschritte |
| [MAP.md](docs/MAP.md) | Karte, Geocoding, Cache, Lifecycle und Abnahme |
| [ROADMAP.md](docs/ROADMAP.md) | Implementierungsplan, Soll/Ist und offene Anforderungen |
| [M05-PLAN.md](docs/M05-PLAN.md) | Aktives M05-Soll, Design, Architektur, Scope und Abnahme |
| [M05-PARITY.md](docs/M05-PARITY.md) | Legacy-/Native-Paritätsmatrix mit stabilen Anforderungs-IDs |
| [M05-TASKS.md](docs/M05-TASKS.md) | Operative Aufgaben, Abhängigkeiten, Status, Review-Gates |
| [AGENTS.md](AGENTS.md) | Ausführungs-Harness für Agenten |
| [PROVENANCE.md](docs/PROVENANCE.md) | Artefact-Referenz und Codeherkunft |

Bei fachlichen Änderungen die zuständige Dokumentation im selben PR aktualisieren. Historische Meilensteinberichte sind über Git verfügbar. Keine zusätzlichen parallelen Spezifikationen.

## Lizenz

Apache-2.0; [LICENSE](LICENSE), [NOTICE](NOTICE). Die technische Build-Basis wurde selektiv aus Artefact adaptiert, nicht dessen Features; siehe [PROVENANCE.md](docs/PROVENANCE.md).
