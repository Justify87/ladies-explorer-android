# Ladies Explorer Android

Native, lokal genutzte Android-App mit Kotlin, Jetpack Compose / Material 3 Expressive, Rust, UniFFI und SQLite. Die App verwaltet ihren eigenen nativen Datenbestand; Produktgrenzen siehe unten. Kein separater Server, kein Python-/Flask-/Node.js-Runtime, keine Cloud-Datenbank.

## Produkt

Entdecken, Suche, Profile, Medien, Personen, gespeicherte Suchen, Karte und lokale Diagnose. Verbindlicher Funktionsumfang und Grenzen: [PRODUCT.md](docs/PRODUCT.md). Fortschritt, offene Aufgaben und Abnahme stehen im [Arbeits- und Fortschrittsregister](docs/ROADMAP.md).

## Aktive Weiterentwicklung

Den nächsten ausführbaren Schritt dem [Arbeitsregister](docs/ROADMAP.md) entnehmen. Einstieg für Agenten: [AGENTS.md](AGENTS.md).

## Architektur

Technische Architektur und Grenzen: [ARCHITECTURE.md](docs/ARCHITECTURE.md). Gradle baut die native Bibliothek und Bindings für das APK.

## Build und Installation

### Voraussetzungen

- Java 21, Android SDK Platform 37.0 (Paket `platforms;android-37.0`), NDK 29.0.14206865, Rust 1.99.0 und cargo-ndk 4.1.2.
- Maßgebliche Konfiguration: [Gradle-App-Build](app/build.gradle.kts), [Android-Versionen](gradle/libs.versions.toml), [Rust-Toolchain](rust/rust-toolchain.toml), [CI-Workflow](.github/workflows/android.yml). Android Studio kann das Repository direkt öffnen.

### Lokaler Debug-Build (macOS/Linux)

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
./gradlew :app:assembleDebug -Pladies.abis=arm64-v8a
```

### APK und Installation

- Ausgabe: `app/build/outputs/apk/debug/`. Für x86_64-Emulatoren die passende ABI bauen.
- Ohne lokale Android-/Rust-Toolchain: [GitHub Actions](https://github.com/Justify87/ladies-explorer-android/actions) → Android and Rust → Run workflow (Branch auswählen). Nach erfolgreichem manuellem Lauf steht `ladies-explorer-android-arm64-debug` 7 Tage zum Download bereit.
- Debug besitzt eine eigene Application-ID; Debug und Release lassen sich getrennt installieren.

## Prüfung / CI

Der [Android-and-Rust-Workflow](.github/workflows/android.yml) läuft bei PRs, Pushes auf main und manuellen Starts. **Core and contracts** und **Android integration** prüfen unabhängig Rust fmt/test/clippy, Quellen- und APK-Architekturverträge, native synthetische Fixtures, ARM64-Rust/UniFFI, Debug- und Instrumentierungstest-APK, JVM-Tests und Lint. Gradle führt die Aufgaben gemeinsam mit `--continue` aus. Cargo.lock ist maßgeblich. Instrumentierungstests werden gebaut, nicht automatisch auf einem Gerät ausgeführt.

### CI-Ausführungsmodi

| Modus | Bedingung | Erwartetes Verhalten |
| --- | --- | --- |
| `full` | Build-/Test-relevante Änderung, manueller Start oder nicht sicher verifizierbarer Änderungsumfang | Core- und Android-Prüfungen ausführen |
| `docs` | Vollständig verifizierter reiner Diff regulärer `.md`-Dateien im Repository-Root, unter `docs/` oder direkt unter `tools/`; PR gegen `main` oder verifizierter `main`-Push | Core und Android überspringen; kein vorausgehendes APK/CI nötig |

- `docs` erfasst auch geänderte, neue, gelöschte und umbenannte erlaubte Markdown-Dateien. Markdown in Build-Input-Verzeichnissen, gemischte Diffs und Typ-/Moduswechsel erfordern `full`.
### Gate und Artefakte

- Scope-Job und abschließendes `build`-Gate laufen immer. Das Gate prüft alle erwarteten Job-/Step-Ergebnisse und ausdrücklich übersprungene Prüfungen.
- Überholte PR-Läufe werden abgebrochen; `main`- und manuelle Läufe nicht pauschal.
- **Nur APKs mit grünem `build`-Gate sind vollständig CI-validiert.** Ein bereits hochgeladenes Artefakt allein beweist das nicht.
- `ladies-explorer-android-arm64-debug` wird ausschließlich bei `workflow_dispatch` nach regulärem Gradle-Build und APK-Architekturprüfung hochgeladen (7 Tage). Automatische PR-/Main-Läufe prüfen die APK bei `full`, speichern sie aber nicht als Artefakt. `docs` erzeugt kein APK. Ein Re-run eines automatischen Laufs ändert das ursprüngliche Ereignis nicht.
- `android-ci-reports` enthält verfügbare Gradle-Profile, JVM-/Lint-Berichte und ausgewählte Diagnose (7 Tage).

### Build-Caches

Gradle Enhanced Caching und getrennte Host-/Android-Rust-Caches werden verwendet. Der Host-Job nutzt `Swatinem/rust-cache`; Android nutzt `actions/cache/restore` und `actions/cache/save`, getrennt nach effektivem Rust-/Host-Compiler, NDK/API und Build-/Cargo-Konfiguration. Der Fallback bleibt auf dieselbe native Toolchain beschränkt. Nur nach erfolgreichem Build und mit vorhandenen Android-Release- und Host-Debug-Abhängigkeiten wird ein fehlender Cache gespeichert; Cargo entfernt zuvor die eigenen Workspace-Artefakte. Unbenutzte vorinstallierte Rust-Versionen sind kein Android-Cache-Input. Externe Fork-PRs schreiben nicht in die Caches. SDK/NDK werden nur bei fehlender oder ungültiger Installation nachinstalliert. Der Gradle Configuration Cache bleibt im regulären Build deaktiviert.

`cargoBuild<Variant>` und `uniffiBindgen<Variant>` nutzen bei `-Pladies.nativeCache=true` auf kontrollierten GitHub-hosted Linux-X64-Runnern den Gradle-Task-Output-Cache. Eingaben umfassen Rust-Workspace/Ressourcen, Lockfile, Toolchain, definierte Umgebung, Compiler-/Linker-/libc-Versionen, Rust-Targets, relevante NDK/API/ABI-/Generatoridentitäten und für Bindings die tatsächlichen Bibliotheksbytes. Die Runner-Image-Version ist nur Aktivierungsvoraussetzung, kein Cache-Key; unveränderte Tool-Versionsstrings können Änderungen an Host-Headern/Systembibliotheken nicht vollständig abbilden. Bei Verdacht `-Pladies.nativeCache=false` verwenden. Lokal bzw. in unkontrollierter Umgebung bleibt normale Cargo-Wiederverwendung ohne Gradle-Output-Cache/UP-TO-DATE für diese Tasks erhalten. Maßgeblich ist [app/build.gradle.kts](app/build.gradle.kts).

### Optionale CI-Diagnose

Optionale Diagnosewerkzeuge, ausschließlich explizit manuell (beide Inputs standardmäßig false):

- `verify_native_cache=true`: [Native Cache-Probe](tools/verify-native-cache.sh) mit separatem Checkout, Wiederherstellung, Bytevergleich und Input-Invalidierungen; gemeinsamer Probe-Cache schreibgeschützt.
- `measure_android_build=true`: [Build-Messung](tools/measure-android-build.sh) mit Gradle-Cache-Key- und Cargo-Fingerprint-Diagnostik, Kotlin-/Gradle-Berichten und isoliertem Configuration-Cache-Experiment.

### Lokale Prüfungen

**CI-Scope und Architektur:**

```sh
python3 -m unittest discover -s tools -p test_ci_scope.py
python3 -m unittest discover -s tools -p test_check_architecture.py
python3 tools/check_architecture.py
```

**Rust-Formatierung, Tests und Lint:**

```sh
(cd rust && cargo fmt --all -- --check && cargo test --workspace --locked && cargo clippy --workspace --all-targets --locked)
```

**Android-Build, Test-APKs, JVM-Tests und Lint:**

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug -Pladies.abis=arm64-v8a --stacktrace --continue --profile
```

Die CI prüft die in [ARCHITECTURE.md](docs/ARCHITECTURE.md#automatisierte-architekturverträge) beschriebenen Architekturverträge im bestehenden Core-Job und am tatsächlich erzeugten ARM64-Debug-APK vor dem Upload. Lokal nach dem Build: `python3 tools/check_architecture.py --apk app/build/outputs/apk/debug/app-debug.apk --apkanalyzer "$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" --aapt2 /pfad/zum/sdk/build-tools/version/aapt2`. Prüfgrenzen und konkrete Regeln stehen im verlinkten Architekturvertrag.

Eine grüne CI beweist keine funktionierende reale Website-Suche oder native Geräte-UX. Geräteprüfaufgaben werden nach der [Merge-Regel](docs/ROADMAP.md#pflege) abgeschlossen; nach Installation unter Einstellungen Datenbankstatus (Schema v2) kontrollieren.

## Lokale Diagnose

- Unter Einstellungen → Diagnose INFO/DEBUG-Log oder HTTP-Aufzeichnung bewusst aktivieren; TXT/HAR speichern oder teilen. Firefox DevTools → Netzwerk → HAR importieren.
- Headerzeit umfasst Verbindung und Redirects, nicht isolierte Serverwartezeit/TTFB. HAR stellt sie aggregiert als `wait` dar; DNS-/TCP-/TLS-Einzelphasen nicht erfinden.
- Pagination-Pause ist keine Parserzeit; Cachetreffer erzeugen keine Requests.
- Messungen mit Ausgangscommit, Operation und Bedingungen belegen. Keine privaten Profile, Antworten, HARs oder Logs in Git.

## Synthetische Testdaten

Die CI erzeugt eine isolierte native Android-v2-Testdatenbank ohne Nutzerimport oder Migration:

```sh
python3 tools/m05-reference.py /tmp/m05-reference --clock 2026-11-18T12:00:00+00:00
(cd rust && M05_REFERENCE_DB=/tmp/m05-reference/ladies.sqlite cargo test --workspace --locked m05_native_reference_fixture -- --ignored)
```

Acht künstliche Personen, neun Inserate, 18 Snapshots, Präferenz-/Favorit-Kombinationen, geometrische Medien, example.invalid; `scenarios.json` enthält stabile IDs und Szenarien. Aktuelle Watchlist: Uhrzeit weglassen. Der opt-in-Rust-Test öffnet diese native Datenbank und prüft Schema, Medienpfad, Präferenz, Notiz, Snapshot-Anzahl und Fremdschlüssel, keine Geräte-UI. Zielverzeichnis neu, keine echten Daten überschreiben; ausschließlich Testdaten. Spezifische Regressionsverträge: [ARCHITECTURE](docs/ARCHITECTURE.md#automatisierte-architekturverträge), visuelle Prüfung: [DESIGN](docs/DESIGN.md#prüfung-und-gestaltungsspielraum).

## Dokumentation

| Dokument | Zuständigkeit |
| --- | --- |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Native Architektur, SQLite, Identität, Historie, Medien, UI-Datenflüsse |
| [ROADMAP.md](docs/ROADMAP.md) | Einziges Arbeits- und Fortschrittsregister |
| [DESIGN.md](docs/DESIGN.md) | Übergreifender UI-/UX- und Designvertrag |
| [PRODUCT.md](docs/PRODUCT.md) | Produkt, Features, fachliches Soll und Grenzen |
| [AGENTS.md](AGENTS.md) | Ausführungs-Harness für Agenten |

Bei fachlichen Änderungen die zuständige Dokumentation im selben PR aktualisieren. Historische Meilensteinberichte sind über Git verfügbar. Keine zusätzlichen parallelen Spezifikationen.

## Lizenz

Apache-2.0; [LICENSE](LICENSE), [NOTICE](NOTICE). Attribution und noch zu klärende native Weiterverteilungspflichten stehen in NOTICE und [ROADMAP](docs/ROADMAP.md#unabhängiger-klärungspunkt).
