# Ladies Explorer Android

Native, lokal genutzte Android-App mit Kotlin, Jetpack Compose / Material 3 Expressive, Rust, UniFFI und SQLite. Die App übernimmt relevante Daten und fachliche Regeln der früheren Web-App in einen eigenständigen Android-Stack. Kein separater Server, kein Python-/Flask-/Node.js-Runtime, keine Cloud-Datenbank.

## Aktueller Funktionsumfang

- Entdecken (Swipe, LIKE/DISLIKE, später), native Suche und Filter, gespeicherte Suchen mit Ergebnis-Historie, Favoriten, Karte und Einstellungen.
- Gemeinsamer vertikaler Profilfluss für Swipe und Einzelprofil mit Mediengalerie, lokalem Bildarchiv, Offline-Fallback auf gespeicherte Profile, privaten Personennotizen (lesen, bearbeiten, speichern), Änderungshistorie und explizit bestätigbaren Identitätskandidaten.
- Lokaler Import vorhandener Web-App-Daten, native MapLibre-Karte mit groben Ortszentren und ausdrücklich gestarteter Geocodierung.
- Lokales Debug-/Performance-Logging und optionaler HTTP-Mitschnitt mit TXT-/HAR-Export.
- Galerie ohne obere Bedienleiste, Medienwechsel über seitliche Tippzonen; eingebettete stumme Videos mit Autoplay/Loop und mittigem Play/Pause. Externe WhatsApp-/Originalaktionen; kein integrierter Messenger oder automatische KI-/OCR-Preisextraktion.

Der Code ist keine vollständig abgenommene Web-App-Ablösung. Fortschritt, offene Aufgaben und Abnahme stehen im [Arbeits- und Fortschrittsregister](docs/ROADMAP.md).

## Aktive Weiterentwicklung

Den nächsten ausführbaren Schritt dem [Arbeitsregister](docs/ROADMAP.md) entnehmen. Einstieg für Agenten: [AGENTS.md](AGENTS.md).

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

Der [Android-and-Rust-Workflow](.github/workflows/android.yml) läuft bei PRs, Pushes auf main und manuellen Starts. **Core and contracts** und **Android integration** prüfen unabhängig Rust fmt/test/clippy, Quellen- und APK-Architekturverträge, Export-/Import-Fixtures, ARM64-Rust/UniFFI, Debug- und Instrumentierungstest-APK, JVM-Tests und Lint. Gradle führt die Aufgaben gemeinsam mit `--continue` aus. Cargo.lock ist maßgeblich. Instrumentierungstests werden gebaut, nicht automatisch auf einem Gerät ausgeführt.

### Ausführung und Artefakte

- `full`: Standard für Änderungen mit möglichen Build-/Testauswirkungen, manuelle Starts und nicht zuverlässig nachweisbare Änderungsumfänge.
- `docs`: Vollständig verifizierte Änderungen ausschließlich an regulären `.md`-Dateien im Repository-Wurzelverzeichnis, unter `docs/` oder direkt unter `tools/` (auch AGENTS und Provenance, neue/gelöschte/umbenannte Dateien). PRs gegen main und verifizierte main-Pushes überspringen Core und Android. Keine Abhängigkeit von vorheriger CI oder APK-Verfügbarkeit. Markdown in Build-Input-Verzeichnissen, gemischte Diffs und Typ-/Moduswechsel bleiben Full-CI.
- `reuse`: Bei nachgewiesenem Zwei-Parent-Merge desselben Repositories mit identischem geprüftem Git-Tree, exakter Basis und erfolgreichem jüngstem PR-CI-Lauf übernimmt main das geprüfte APK. Bei Docs-Merges kann das APK des verifizierten Basislaufs optional erneut veröffentlicht werden. [ci_reuse.py](tools/ci_reuse.py) prüft Run/Versuch, Repository, Ereignis, Tree/Parents, Gates/Jobs/Steps und APK-SHA-256. Fehlende oder ungültige Evidenz bzw. Transferfehler lassen den ursprünglichen Scope bestehen: bei Codeänderungen Full-CI, bei reinen Docs kein Build.

Der Scope-Job und abschließende Check **build** laufen immer. Das Gate verlangt die tatsächlich erwarteten Job-/Step-Ergebnisse einschließlich ausdrücklich übersprungener Prüfungen und erfolgreicher APK-Wiederveröffentlichung. Überholte PR-Läufe werden abgebrochen; main und manuelle Läufe nicht pauschal.

Nur APKs mit grünem **build**-Gate als vollständig CI-validiert verwenden. Das Artefakt `ladies-explorer-android-arm64-debug` wird nach regulärem Gradle-Build und APK-Architekturprüfung bereitgestellt (PR: 7 Tage, main: 30 Tage); es kann bereits vor dem finalen Gate oder trotz späterer Fehler verfügbar sein. Bei Wiederverwendung wird das APK erneut hochgeladen, Berichte bleiben im Quelllauf. `android-ci-reports` enthält verfügbare Gradle-Profile, JVM-/Lint-Berichte und ausgewählte Diagnosedaten (7 Tage). Reine Docs-Läufe ohne wiederverwendbares Artefakt erzeugen kein APK; das zuletzt erfolgreich gebaute APK bleibt im ursprünglichen Lauf verfügbar, solange dessen Aufbewahrungsfrist nicht abgelaufen ist.

### Build-Caches und optionale Diagnose

Gradle Enhanced Caching und getrennte Host-/Android-Rust-Caches werden verwendet; externe Fork-PRs schreiben nicht in die Caches. SDK/NDK werden nur bei fehlender oder ungültiger Installation nachinstalliert. Der Gradle Configuration Cache bleibt im regulären Build deaktiviert.

`cargoBuild<Variant>` und `uniffiBindgen<Variant>` nutzen bei `-Pladies.nativeCache=true` auf kontrollierten GitHub-hosted Linux-X64-Runnern den Gradle-Task-Output-Cache. Eingaben umfassen Rust-Workspace/Ressourcen, Lockfile, Toolchain, definierte Umgebung, Compiler-/Linker-/libc-Versionen, Rust-Targets, relevante NDK/API/ABI-/Generatoridentitäten und für Bindings die tatsächlichen Bibliotheksbytes. Die Runner-Image-Version ist nur Aktivierungsvoraussetzung, kein Cache-Key; unveränderte Tool-Versionsstrings können Änderungen an Host-Headern/Systembibliotheken nicht vollständig abbilden. Bei Verdacht `-Pladies.nativeCache=false` verwenden. Lokal bzw. in unkontrollierter Umgebung bleibt normale Cargo-Wiederverwendung ohne Gradle-Output-Cache/UP-TO-DATE für diese Tasks erhalten. Maßgeblich ist [app/build.gradle.kts](app/build.gradle.kts).

Optionale Diagnosewerkzeuge, ausschließlich explizit manuell (beide Inputs standardmäßig false):

- `verify_native_cache=true`: [Native Cache-Probe](tools/verify-native-cache.sh) mit separatem Checkout, Wiederherstellung, Bytevergleich und Input-Invalidierungen; gemeinsamer Probe-Cache schreibgeschützt.
- `measure_android_build=true`: [Build-Messung](tools/measure-android-build.sh) mit Cache-Key-Diagnostik, Kotlin-/Gradle-Berichten und isoliertem Configuration-Cache-Experiment.

Wesentliche lokale Prüfungen:

```sh
bash -n tools/export-legacy.sh
(cd rust && cargo fmt --all -- --check && cargo test --workspace --locked && cargo clippy --workspace --all-targets --locked)
python3 -m unittest discover -s tools -p test_ci_scope.py
python3 -m unittest discover -s tools -p test_check_architecture.py
python3 tools/check_architecture.py
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug -Pladies.abis=arm64-v8a --stacktrace --continue --profile
```

Die CI prüft die in [ARCHITECTURE.md](docs/ARCHITECTURE.md#automatisierte-architekturverträge) beschriebenen Architekturverträge im bestehenden Core-Job und am tatsächlich erzeugten ARM64-Debug-APK vor dem Upload. Lokal nach dem Build: `python3 tools/check_architecture.py --apk app/build/outputs/apk/debug/app-debug.apk --apkanalyzer "$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" --aapt2 /pfad/zum/sdk/build-tools/version/aapt2`. Keine zusätzlichen Runner oder Frameworks; die statischen Prüfungen sind keine vollständige semantische Analyse oder Sicherheits-Sandbox. Die kompilierte CoreStore-API wird zusätzlich im bestehenden JVM-Test geprüft; APK-Ressourcenreferenzen werden über die AAPT2-Tabelle aufgelöst und alle zugehörigen XML-Dateien validiert.

Eine grüne CI beweist keinen erfolgreichen Import realer Daten, keine funktionierende Website-Suche oder native Geräte-UX. Geräteprüfaufgaben werden nach der [Merge-Regel](docs/ROADMAP.md#pflege) abgeschlossen; nach Installation unter Einstellungen Datenbankstatus (Schema v2) kontrollieren.

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
| [ROADMAP.md](docs/ROADMAP.md) | Einziges Arbeits- und Fortschrittsregister |
| [M05-PLAN.md](docs/M05-PLAN.md) | Gültiger UI-/Profilvertrag und Regressionsanforderungen |
| [M05-PARITY.md](docs/M05-PARITY.md) | Fach-/UX-Anforderungsinventar mit stabilen IDs |
| [AGENTS.md](AGENTS.md) | Ausführungs-Harness für Agenten |
| [PROVENANCE.md](docs/PROVENANCE.md) | Artefact-Referenz und Codeherkunft |

Bei fachlichen Änderungen die zuständige Dokumentation im selben PR aktualisieren. Historische Meilensteinberichte sind über Git verfügbar. Keine zusätzlichen parallelen Spezifikationen.

## Lizenz

Apache-2.0; [LICENSE](LICENSE), [NOTICE](NOTICE). Die technische Build-Basis wurde selektiv aus Artefact adaptiert, nicht dessen Features; siehe [PROVENANCE.md](docs/PROVENANCE.md).
