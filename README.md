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

M05 ist zur vollständigen Frontend-Parität wieder geöffnet. Nächsten ausführbaren Schritt dem [Aufgabenregister](docs/M05-TASKS.md) entnehmen; M06/M07 nicht vorziehen. Einstieg für Agenten: [AGENTS.md](AGENTS.md).

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

Der [Android-and-Rust-Workflow](.github/workflows/android.yml) läuft für PRs (einschließlich neuer Commits), Pushes auf main und manuelle Starts. Die beiden Prüflinien **Core and contracts** (HTTP-Grenze, Export-/Import-Fixtures, Rust fmt/test/clippy) und **Android integration** (ARM64-Rust/UniFFI, Debug- und Instrumentierungstest-APK, JVM-Tests und Lint) laufen unabhängig. Gradle `--continue` und getrennte Rust-Prüfschritte melden möglichst viele unabhängige Fehler pro Lauf. Rust-Abhängigkeiten werden über Cargo.lock reproduziert.

Der abschließende Check **build** behält den bisherigen Namen und ist nur erfolgreich, wenn alle erwarteten Prüfungen erfolgreich sind. Reine Inhaltsänderungen in den elf explizit zugelassenen Dokumenten (`README.md`, `docs/ROADMAP.md`, `docs/M05-PLAN.md`, `docs/M05-TASKS.md`, `docs/M05-PARITY.md`, `docs/ARCHITECTURE.md`, `docs/SOURCE.md`, `docs/DATA-IMPORT.md`, `docs/DIAGNOSTICS.md`, `docs/MAP.md`, `tools/M05-REFERENCE.md`; maßgeblich ist die Allowlist in [`tools/ci_scope.py`](tools/ci_scope.py)) dürfen die beiden Prüflinien nur auslassen, wenn der vollständige PR-Merge-Diff ausschließlich diese Änderungen enthält und der jüngste main-Push-CI-Lauf für exakt den Basiscommit erfolgreich ist. Fehlender Nachweis, gemischte PRs, neue/geänderte Pfade außerhalb der Allowlist, main und manuelle Starts führen zur Full-CI. Ein reiner Dokumentationslauf ist kein neuer APK- oder Meilenstein-Nachweis. `AGENTS.md`, `docs/PROVENANCE.md`, Lizenz-/Notice-Dateien, `.gitignore`, CI-/Build-/Testdateien sowie neue, entfernte, umbenannte oder typ-/modusgeänderte Dateien erzwingen Full-CI. Die Scope-Regressionen prüfen auch echte Git-Diffs und die gezielten Ignore-Regeln für lokale Exporte. `.gitignore` schützt nicht vor bereits versionierten oder ausdrücklich hinzugefügten privaten Daten.

Nur APKs aus Läufen mit grünem **build**-Gate als vollständig validiert verwenden; ab CI06.4 wird das reguläre APK nach erfolgreichem Gradle-Build und gepackter Architekturprüfung, vor den Sonderprüfungen hochgeladen. Ein APK kann deshalb auch bei fehlgeschlagenem Core-Job oder späterer fehlgeschlagener Qualifikation bereitstehen. Das bisherige APK-Artefakt bleibt unter `ladies-explorer-android-arm64-debug` verfügbar (7 Tage bei PRs, 30 Tage auf main). `android-ci-reports` enthält verfügbare Gradle-Profile, JVM-Test- und Lint-Berichte für 7 Tage, auch bei fehlgeschlagenem Gradle-Schritt.

Gradle Enhanced Caching wird für das öffentliche Repository erprobt (Rückfall: `cache-provider: basic`); Host- und Android-Rust-Cache haben getrennte Schlüssel, externe Fork-PRs schreiben nicht in die Caches. Vergleich von kalten/warmen Läufen anhand der CI-Schrittzeiten und Gradle-Profilberichte; keine angenommene Beschleunigung ohne Messung. CI02 verwendet zusätzlich Gradle-Task-Output-Caching für Rust/UniFFI im kontrollierten GitHub-Linux-Runner (`-Pladies.nativeCache=true`). CI03 (Geräteausführung) bleibt separat.

### Native Build-Caches (CI02)

- `cargoBuild<Variant>` berücksichtigt den gesamten Rust-Workspace inklusive eingebetteter JSON-Ressourcen, Cargo.lock, Toolchain, NDK-Identität, API und ABIs; `uniffiBindgen<Variant>` zusätzlich das Byte-Inhaltssignal der tatsächlichen nativen Bibliothek. Beide deklarieren ihre erzeugten Verzeichnisse und ersetzen veraltete Outputs vollständig.
- Nur bei expliziter Aktivierung auf dem bekannten GitHub-hosted Linux-X64-Image ohne zusätzliche Cargo-Konfigurationsdateien: definierte Prozessumgebung, Cargo-/Rust-/Host-Compiler-/Linker-/libc-Versionen, installierte Rust-Targets und Runner-Image im Gradle-Cache-Schlüssel; cargo-ndk und NDK-Metadaten/API zusätzlich für den Android-Rust-Task. Cargo erhält einen separaten stabilen Fingerprint aus tatsächlichen Compiler-/Linker-Versionen und der jeweiligen NDK/API-Identität, nicht dem Runner-Image-Label oder der installierten Targetmenge. Checkout-Pfade werden nicht als Gradle-Input verwendet; Rust-Quellpfade werden remapped. Host-Toolpfade und NDK-Pfad partitionieren die Gradle-Caches bewusst. Cargo.lock bleibt maßgeblich; installierte SDK-/Rust-Pakete werden als unveränderliche, versionierte Toolchain behandelt. Android-Mindest-API-Level und cargo-ndk verwenden denselben Wert `ladies.minApi` in `gradle.properties`; der API-Probe ändert ausschließlich diesen Wert.
- Lokal, bei unkontrolliertem Host oder zusätzlicher Cargo-Konfiguration: kein Gradle-Output-Cache und kein Gradle-UP-TO-DATE für diese beiden Tasks, Cargo behält seinen normalen inkrementellen Build. Rückfall in CI: `-Pladies.nativeCache=false`. Keine Änderung an Rust-Tests, CI04-Prüfungen oder APK-Abnahme.
- `tools/verify-native-cache.sh` prüft nach dem regulären ARM64-Build reale Cache-Hits in einem separaten Checkout mit Kotlin-only-Änderung, Generator-Konfiguration, eingebettete JSON- und Rust-Inhalte im tatsächlichen ELF, Cargo.lock-Eingabe, API- und ABI-Wechsel sowie identische wiederhergestellte Outputs. Der gemeinsame Task-Cache bleibt dabei lesbar, aber schreibgeschützt; der Probe-Lauf kontrolliert auch, dass keine neuen Cache-Einträge entstehen. Ab CI06.3 bestimmt die zentrale CI-Scope-Policy die Ausführung: PRs und main-Pushes mit betroffenen Build-Verträgen bzw. unklarem Änderungsumfang oder manuell via Workflow-Input `verify_native_cache`. Echte NDK- und Rust-Toolchain-Versionen werden bei einem tatsächlichen kompatiblen Versionswechsel separat abgenommen (kein künstlicher Wechsel auf eine nicht unterstützte Toolchain); der Lockfile-Test belegt keine Abhängigkeits-Upgrades. Zur runnerübergreifenden Abnahme denselben unveränderten PR-Stand auf einem neuen Runner ausführen und beide nativen Tasks im regulären APK-Build auf `FROM-CACHE` prüfen. Geschwindigkeitsgewinn erst anhand tatsächlicher Warm-/Cold-Läufe bewerten.

### Android Build Performance (CI05)

CI05 beginnt nach Integration von CI02. Der unabhängige Wiederholungslauf auf CI02-Head `72788aa` war vollständig grün, aber die nativen Tasks im normalen APK-Build wurden erneut ausgeführt: Runner-Image 20261111.370.1 vs. 20261111.127.1, trotz wiederhergestelltem Gradle- und Rust-Cache. Lint hingegen vollständig FROM-CACHE. Die Image-/Toolchain-Grenzen nicht ohne Nachweis der Gleichwertigkeit entfernen. [CI02 Versuch 2](https://github.com/Justify87/ladies-explorer-android/actions/runs/44723811678/attempts/2).

- Die vorhandene Android-CI verwendet den exakt versionsgeprüften cargo-ndk-Bestand oder das offizielle Linux-x86_64-GNU-Release 4.1.2 mit fest gepinnter SHA-256-Prüfsumme (`tools/install-cargo-ndk.sh`). Fehler beim Download oder der Prüfung brechen ab; kein unvalidiertes Binary. Lokale Installation unverändert.
- Ab CI06.3 wählt die zentrale Scope-Policy `tools/measure-android-build.sh` bei erkannten Änderungen an den beiden CI05-Skripten in PRs und main-Pushes oder beim expliziten Workflow-Input `measure_android_build` aus. Der reguläre Full-CI-Task-Satz bleibt unverändert; nur in ausgewählten Diagnoseläufen werden auch für den tatsächlichen APK-Build Cache-Fingerprints und Kotlin Build Reports erfasst. Die reguläre CI ohne Diagnose erhält keine zusätzlichen Gradle-Flags. Im temporären Checkout werden derselbe vollständige Gradle-Task-Satz, echte Cache-Key-Diagnostik, Kotlin Build Reports, Lint-Cache-Treffer, Konfigurationscache mit Problems=fail und vergleichbare Warm-Cache-Builds erfasst. Synthetische Outputs werden nicht in den Task-Cache geschrieben. Die vorhandene CI02-Probe sichert zusätzliche API-/ABI-/ELF-Invalidierungen ab.
- Reports liegen im Artefakt `android-ci-reports` unter `build/reports/ci05`. Keine externen Build Scans, keine neuen Secrets, keine pauschale Aktivierung des Configuration Cache. Fehlgeschlagene optionale Configuration-Cache-Qualifikation ist ein dokumentiertes Negativergebnis; der unveränderte Full-CI-Build muss erfolgreich sein. Einstellungen, Lint-Invalidierungen und native Cache-Eingaben erst anhand der Messergebnisse ändern. Keine weitere Modul-/Compiler-/JVM-Umstrukturierung ohne Befund.

### CI06 – zentrale Auslösepolicy (Soll, CI06.1)

Ausgangsstand: integriertes CI05 (`b5e08b73`, [Main-CI #494](https://github.com/Justify87/ladies-explorer-android/actions/runs/44734332264) und [PR-CI #493](https://github.com/Justify87/ladies-explorer-android/actions/runs/44727982739) grün). In #493 benötigten die regulären Gradle-Tasks 183 s, die CI02-Qualifikation 377 s und die CI05-Messung 626 s. Der Configuration Cache war im isolierten CI05-Experiment wiederverwendbar, sparte im Median der drei Wiederholungen aber nur 1,87 s (34,353 → 32,481 s); er bleibt in der regulären CI deaktiviert. Runnerübergreifende native Cache-Hits und tatsächliche kompatible Toolchain-Versionswechsel bleiben getrennte Nachweise.

**CI06.1 definiert den verbindlichen Zielvertrag.** CI06.2 implementiert die zentrale Klassifizierung; CI06.3 bindet deren Ausgaben an die tatsächlichen Auslöser und Gradle-Diagnoseflags an. CI06.4 vergleicht im finalen Gate die tatsächlichen Step-Outcomes mit der Auswahl (einschließlich ausdrücklich übersprungener Schritte) und stellt das regulär geprüfte APK vor den Sonderprüfungen bereit; vollständige Regressionen und Abnahme folgen in CI06.5–6. Kein App-/Rust-Fachumbau, zusätzliche Runner, neue Abhängigkeiten, Geräte-/Release-Infrastruktur oder Änderungen an abgeschlossenen M05-Aufgaben.

#### Änderungsmatrix

Die zentrale Scope-Policy entscheidet einmal über `mode=docs|full`, `native_qualification=true|false` (CI02) und `performance_measurement=true|false` (CI05). Scope-Regressionen und das abschließende `build`-Gate laufen stets. Full-CI umfasst unverändert **Core and contracts** und **Android integration**, einschließlich CI04-Quell-/APK-Verträgen, Export/Roundtrip, synthetischer Referenz und separatem Importtest, Rust fmt/test/clippy, ARM64-Debug- und Instrumentierungstest-APK, JVM-Tests, Lint und Debug-APK-Upload. Die Gradle-Aufgaben bleiben in einem Aufruf. Ein Cache-Treffer ist gültige Task-Wiederverwendung, keine selektive Abwahl von Prüfungen.

| Geprüfter Änderungsumfang | Full-CI im PR | CI02 | CI05 automatisch |
| --- | --- | --- | --- |
| Nur gewöhnliche Inhaltsänderungen in der bestehenden exakten Docs-Allowlist, mit gültigem Basisnachweis | Nein | Nein | Nein |
| Dieselben Docs-Änderungen ohne gültigen Basisnachweis; strukturelle Änderungen an bekannten Docs (z. B. Modus/Entfernen) | Ja | Nein | Nein |
| Android-Kotlin/Java, Ressourcen, Manifest, JVM-/Instrumentierungstestquellen; `app/proguard-rules.pro` | Ja | Nein | Nein |
| Rust-Fachcode, Tests, eingebettete Ressourcen unter `rust/ladies-core/src/` (außer Build-Vertrag) | Ja | Nein | Nein |
| `tools/check_architecture.py`, `tools/test_check_architecture.py`, `tools/export-legacy.sh`, `tools/m05-reference.py` | Ja | Nein | Nein |
| `AGENTS.md`, `docs/PROVENANCE.md`, `LICENSE`, `NOTICE`, `.gitignore` | Ja | Nein | Nein |
| Gradle-Buildskripte/Settings, `gradle.properties`, `gradle/`, Wrapper `gradlew`/`gradlew.bat`; native Packaging (z. B. `jniLibs`) | Ja | Ja | Nein |
| Cargo-Manifeste/Lockfiles, `build.rs`, UniFFI-Konfiguration, `rust/bindgen/`, Rust-Toolchain-Dateien, Cargo-Konfiguration (auch neue `.cargo/`-Pfade) | Ja | Ja | Nein |
| `tools/verify-native-cache.sh` | Ja | Ja | Nein |
| `tools/measure-android-build.sh` | Ja | Nein | Ja |
| `tools/install-cargo-ndk.sh` | Ja | Ja | Ja |
| Workflow-/CI-Policy-Dateien, einschließlich `.github/workflows/`, `tools/ci_scope.py`, `tools/test_ci_scope.py` | Ja | Ja | Nein |
| Unbekannte Pfade oder nicht sicher klassifizierbarer Diff | Ja | Ja | Nein (außer positiv erkannter CI05-Skriptänderung) |

Build-Vertrag hat Vorrang vor gewöhnlichen Quellpfaden; beide Sonderprüfungen können gleichzeitig erforderlich sein. Eine neue Datei innerhalb eines sicher bekannten Quellbereichs ist nicht automatisch unbekannt; unklare Dateitypen, Infrastrukturpfade oder Änderungen, die nicht sicher zugeordnet werden können, fallen konservativ zurück. Bei gemischten PRs werden alle Anforderungen vereinigt. Bei Umbenennungen beide Pfade berücksichtigen (Git-Diff ohne Rename-Erkennung); keine Docs-Ausnahme bei Addition, Löschung, Typ-/Moduswechsel oder leerem Diff. Native Build-Vertragsänderungen bleiben auch dann qualifikationspflichtig, wenn nur ein Teil des Workflows betroffen ist; keine semantische YAML-Diff-Engine. CI05-Messungen bei sonstigen konkreten Cache-/Performance-Optimierungen sind eine ausdrückliche manuelle Abnahme und kein automatischer Benchmark für jede Gradle-Änderung.

#### Ereignisse, Evidenz und Gate

- **PR:** Vollständiger NUL-separierter Raw-Diff zwischen verifiziertem Basiscommit (erster Parent des synthetischen Merge-Commits) und tatsächlich geprüftem Merge-HEAD, nicht nur letzter Commit oder begrenzte API-Dateiliste. Docs-only ausschließlich bei PRs gegen main, regulärer Allowlist-Inhaltsänderung und jüngstem erfolgreichen abgeschlossenen main-Push-Lauf von android.yml für exakt diese Basis. Kann nur der Basis-CI-Nachweis nicht erbracht werden, bleibt die sonst verifizierte Pfadklassifizierung für CI02/05 gültig; Full-CI ist zwingend. Bei unprüfbarem Änderungsumfang, leerem Diff oder unbekanntem Pfad Full-CI plus CI02; CI05 nur bei positiv erkannter Skriptänderung oder explizitem manuellen Auftrag.
- **Main-Push:** Immer Full-CI und neues APK für den integrierten Stand (auch bei Docs). Den vollständigen Push-Diff zwischen Event-before und tatsächlichem HEAD prüfen, einschließlich Mehrcommit-Push; nur bei verifiziertem passenden HEAD, vorhandener erreichbarer Basis und vollständigem Diff darf eine Sonderprüfung entfallen. Fehlende/Null-/nicht verwendbare Basis, Force-Push ohne sicher belegbaren Bereich oder Diff-Fehler: Full-CI plus CI02. Relevante native/CI05-Skriptänderungen qualifizieren auch nach Merge oder Direktpush. Keine Anrechnung eines anderen PR-/Commit-Laufs.
- **Manuell:** Immer Full-CI, CI02 und CI05 ausschließlich durch die unabhängigen booleschen Inputs `verify_native_cache` bzw. `measure_android_build`. Für andere Ereignisse keine neue automatische Ausführung einführen; unbekannte Policy-Ausgabe ablehnen.
- **Concurrency/Rerun:** Überholte PR-Läufe abbrechen, Main-/manuelle Läufe nicht pauschal abbrechen. GitHub-Rerun desselben Runs verwenden; kein eigener Cross-Commit-Ergebnis-Cache. Bestehende kontrollierte Gradle-/Rust-Cache-Identitäten, Fork-Schreibschutz und synthetisch schreibgeschützte Probe-Cache-Ausführung beibehalten.
- **Gate:** Die erwarteten Ausgaben müssen gültig sein. In `docs` sind Core/Android absichtlich übersprungen und beide Sonderprüfungen deaktiviert. In `full` müssen beide Jobs erfolgreich sein; jede ausgewählte Sonderprüfung muss tatsächlich erfolgreich abgeschlossen sein (Step-Outcome, nicht nur Job-Erfolg). Nicht ausgewählte Sonderprüfungen als übersprungen/nicht erforderlich ausweisen, nicht als erfolgreich geprüften Vertrag. Fehlende, unbekannte, fehlgeschlagene oder abgebrochene erforderliche Prüfungen sowie widersprüchliche Ergebnisse dürfen kein grünes Gate erzeugen. Ein dokumentiertes negatives Configuration-Cache-Experiment ist ein Messergebnis, keine Freigabe zur Aktivierung; Fehler der erforderlichen Messung/Cache-Verifikationen selbst bleiben Fehler.
- **Artefakte:** Debug-APK nach erfolgreichem regulärem Gradle-Build und gepackter CI04-Architekturprüfung bereitstellen, auch wenn eine spätere erforderliche Qualifikation oder der Core-Job scheitert. Nur grünes finales `build` belegt vollständige ausgewählte CI; Name/Retention und Berichtsupload bleiben erhalten. Keine Geräte- oder Release-Abnahme behaupten.

CI06-Abnahme: positive und negative Policy-/Gate-Regressionen mit tatsächlichen Git-Diffs (auch gemischte Historie, Mehrcommit-Push, unbekannte Pfade, fehlerhafte Evidenz und erforderliche übersprungene Schritte), vollständige CI und reale Docs-only-Ausführung auf exakt grünem Main-Basiscommit. Native Qualifikation und CI05-Messung anhand der tatsächlichen Ergebnisse prüfen, keine pauschale Laufzeitersparnis annehmen. Der historische Check `build` bleibt stabil; derzeit meldet GitHub main als nicht geschützt und keine Repository-Rulesets, die detaillierte Protection-Abfrage liefert 403. Erzwingung als erforderlicher Check ist nicht belegt; Repository-Einstellungen bleiben unverändert.

Wesentliche lokale Prüfungen:

```sh
bash -n tools/export-legacy.sh
(cd rust && cargo fmt --all -- --check && cargo test --workspace --locked && cargo clippy --workspace --all-targets --locked)
python3 -m unittest discover -s tools -p test_ci_scope.py
python3 -m unittest discover -s tools -p test_check_architecture.py
python3 tools/check_architecture.py
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug -Pladies.abis=arm64-v8a --stacktrace --continue --profile
```

CI04 prüft die in [ARCHITECTURE.md](docs/ARCHITECTURE.md#automatisierte-architekturverträge-ci04) beschriebenen Architekturverträge im bestehenden Core-Job und am tatsächlich erzeugten ARM64-Debug-APK vor dem Upload. Lokal nach dem Build: `python3 tools/check_architecture.py --apk app/build/outputs/apk/debug/app-debug.apk --apkanalyzer "$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" --aapt2 /pfad/zum/sdk/build-tools/version/aapt2`. Keine zusätzlichen Runner oder Frameworks; die statischen Prüfungen sind keine vollständige semantische Analyse oder Sicherheits-Sandbox. Die kompilierte CoreStore-API wird zusätzlich im bestehenden JVM-Test geprüft; APK-Ressourcenreferenzen werden über die AAPT2-Tabelle aufgelöst und alle zugehörigen XML-Dateien validiert.

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
