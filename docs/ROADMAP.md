# Arbeits- und Fortschrittsregister

Einzige operative Quelle für Aufgaben, Reihenfolge, Status und Abnahmen.

**Nächster Schritt: T10.8 – Pagination gespeicherter Personen/Watchlist im Branch `fix/t10-8-saved-people-pagination-admission` technisch validieren und nach Nutzerfreigabe integrieren; danach verbleibende G10.3-Regressionsmatrix prüfen.** M05.10 abschließen, M06/M07 erst auf Nutzerauftrag. L01 unabhängig klären.

## Ziel und Grenzen

[PRODUCT](PRODUCT.md), [ARCHITECTURE](ARCHITECTURE.md) und [DESIGN](DESIGN.md) sind die maßgeblichen Verträge, keine parallelen Backlogs.

## Fortschritt

| Meilenstein | Stand | Ergebnis / Rest |
| --- | --- | --- |
| M00–M02 Basis, Domänenmodell, damaliger Import | DONE | [PR #1](https://github.com/Justify87/ladies-explorer-android/pull/1)–[#3](https://github.com/Justify87/ladies-explorer-android/pull/3); abgeschlossen. |
| M03 Suche/Parser | DONE | [#4](https://github.com/Justify87/ladies-explorer-android/pull/4), [#8](https://github.com/Justify87/ladies-explorer-android/pull/8); abgeschlossen. |
| M04 Identität, Historie, Medien | DONE | [#5](https://github.com/Justify87/ladies-explorer-android/pull/5); abgeschlossen. |
| M05.0–M05.9 Frontend-Parität | DONE (Nutzerabschluss 19.11.) | [#23](https://github.com/Justify87/ladies-explorer-android/pull/23), CI #393. Keine Wiedereröffnung. |
| M05.10 Unified Profile Experience | ACTIVE | T10.0–T10.6 abgeschlossen, T10.7 mit [PR #53](https://github.com/Justify87/ladies-explorer-android/pull/53) integriert; T10.8 mit [PR #54](https://github.com/Justify87/ladies-explorer-android/pull/54)–[#57](https://github.com/Justify87/ladies-explorer-android/pull/57) teilintegriert; SavedSearch-Zeilenaktionen in [PR #58](https://github.com/Justify87/ladies-explorer-android/pull/58) integriert; Ergebnisfenster-Pagination/Retry in [PR #60](https://github.com/Justify87/ladies-explorer-android/pull/60) integriert; SavedPeople-Pagination in Arbeit; T10.9 offen. |
| M06 Weitere Fachfunktionen | PLANNED / teilintegriert | Karte [#10](https://github.com/Justify87/ladies-explorer-android/pull/10), gespeicherte Suchen, Einstellungen, WhatsApp vorhanden; Rest unten. |
| M07 Gesamtintegration | PLANNED | Gesamtintegration, Stabilität und Release-Umfang; M07.2 siehe unten. |

Diagnose (Logging, Messungen, HTTP/HAR, TXT-Export) ist integriert [#7](https://github.com/Justify87/ladies-explorer-android/pull/7); abgeschlossen. Ursprüngliche M00–M02-Nummerierung wich von PR-Bezeichnungen ab; maßgeblich sind obige Zuordnung und Git. Kein Prozentwert aus ungleich großen Aufgaben.

## Pflege

**Merge-Regel:** Mit dem Merge eines PR sind alle Geräteprüf- und Geräteabnahmeaufgaben seines integrierten Umfangs immer **DONE**. Dieselbe Regel gilt rückwirkend. Status der zugehörigen Tasks und Meilensteine im Arbeitsregister entsprechend aktualisieren; keine separaten Gerätestatus, Nachabnahmen oder Verschiebungen in spätere Meilensteine. Noch nicht implementierte Funktionen und konkrete offene Fehler bleiben reguläre Aufgaben. Die technische Prüfung bleibt Bestandteil der Umsetzung.

| Status | Bedeutung |
| --- | --- |
| `READY` | Ausführbar |
| `DOING` | Tatsächlich in Arbeit; Branch/PR nennen |
| `REVIEW` | Zur Prüfung |
| `DONE` | Abgeschlossen |
| `BLOCKED` | Blocker und nächsten Schritt benennen |
| `PLANNED` | Abhängig/vorläufig, nicht freigegeben |
- Je Arbeitskontext eine Aufgabe. Main, offene PRs, Abhängigkeiten, relevante Spezifikation/Code/Tests prüfen; konkrete beobachtbare Kriterien vor Umsetzung festlegen. Kein konkurrierender Umbau am selben State.
- Bei Unterbrechung Stand/nächsten Schritt/Blocker hier aktualisieren. Bei Abschluss Kriterien abgleichen, Beleg (PR/Commit/Test/Lauf) verlinken, Status und geänderte Fachverträge im selben PR pflegen. Erledigte Detailchecklisten zu einem Beleg verdichten; Review-Historie in PR/Git, nicht anhängen. Keine eigenmächtige Merge-/Veröffentlichungsfreigabe.

## Jetzt – M05.10 (G10)

### T10.0–T10.6 — DONE

| ID | Ergebnis | Integrierter Beleg |
| --- | --- | --- |
| T10.0 | Baseline, Mess-/Fixture-Grundlage | [#24](https://github.com/Justify87/ladies-explorer-android/pull/24), [#25](https://github.com/Justify87/ladies-explorer-android/pull/25) |
| T10.1 / G10.0 | Charakterisierung, Owner-/Operationsverträge | [#26](https://github.com/Justify87/ladies-explorer-android/pull/26), CI #413 |
| T10.2 | Gemeinsame Projektion und Identitätsoperation | [#27](https://github.com/Justify87/ladies-explorer-android/pull/27) |
| T10.3 / G10.1 | Gemeinsamer Lazy-Profilfluss | [#28](https://github.com/Justify87/ladies-explorer-android/pull/28), CI #451 |
| T10.4 | Swipe-first Shell, adaptiver Hero | [#29](https://github.com/Justify87/ladies-explorer-android/pull/29), CI #457/#458 |
| T10.5 | Native Gesten-Arbitration | [#30](https://github.com/Justify87/ladies-explorer-android/pull/30), CI #462/#463 |
| T10.6 / G10.2 | Rail/Undo, Swipe-Übergang und neue Galerie | [#31](https://github.com/Justify87/ladies-explorer-android/pull/31), [CI #478](https://github.com/Justify87/ladies-explorer-android/actions/runs/44710394072), [Merge #479](https://github.com/Justify87/ladies-explorer-android/actions/runs/44712016704) |

Gerätefeedback 20.11. (hakender Swipe, Layoutsprünge, Galerie-Chrome, Videos) in PR #31 bearbeitet. Alle zugehörigen Geräteprüfaufgaben T10.0–T10.6: **DONE**.

### T10.7 — DONE · Lifecycle, Performance und Altcode ([PR #53](https://github.com/Justify87/ladies-explorer-android/pull/53))

**Abhängigkeit:** T10.6. **Referenzen:** P03/P20–P23/P39–P44/P49–P51; [Architektur](ARCHITECTURE.md#swipe-read-window-und-prefetch), [Medienvertrag](ARCHITECTURE.md#persistentes-archiv-und-anzeige).

**Ist-Stand:** PR #31 enthält bereits relevante Implementierungen; prüfen statt neu implementieren. Code: `DiscoveryScreen.kt`, `SwipeProfile.kt`, `SwipeFlight.kt`, `ProfilePanels.kt`, `ProfilePresentation.kt`, `GalleryVideo.kt`, `MainViewModel.kt`. Tests: `SwipeProfileWindowTest`, `SwipeProfileReadTest`, `ProfilePresentationTest`, `ProfilePanelsTest`, `ProfileProjectionTest`, Rust-Medientests.

**Ziel:** Verbleibende Lifecycle-, Performance- und Altcode-Risiken in M05.10 prüfen, ohne abgeschlossene Funktionen neu aufzubauen.

**Umfang:**
- [x] Owner-/Run-/Snapshot-/Revision- und Lifecycle-Wechsel im vorhandenen Kotlin-/Rust-Pfad und den vorhandenen Regressionen überprüft: Prefetch-Promotion/Pruning, Request/Retry/Cancel, Native Drain, Cache-/Note-/Facts-Invalidierung, SaveableState, unabhängige Reprojektion sowie Video-Surface-/Tempfile-Cleanup. Ein nachgewiesener Cache-Promotion-Befund wird in PR #53 behoben.
- [x] Technische Performance anhand des vorhandenen Fenster-/Mutex-/Read- und Testvertrags überprüft. Kein neues Benchmark-Ergebnis oder gemessener Geschwindigkeitsgewinn; keine allgemeine M07-Optimierung.

**Akzeptanz:**
- [x] Abschluss mit erfolgreicher [CI #45013729939](https://github.com/Justify87/ladies-explorer-android/actions/runs/45013729939) in [PR #53](https://github.com/Justify87/ladies-explorer-android/pull/53): Ein cache-only Prefetch übersprang beim Current-Aufrücken die aktive Freshness-Policy; einmalige Quellenaktualisierung mit unverändertem Slot/Mutex und Regression für No-Redo/Local-only. Weitere entsprechende Defekte im geprüften T10.7-Umfang nicht belegt. Keine Beschleunigung ohne Messung behauptet.

### T10.8 — DOING · Vertikale Integration (G10.3) ([PR #54](https://github.com/Justify87/ladies-explorer-android/pull/54)–[#57](https://github.com/Justify87/ladies-explorer-android/pull/57), [PR #58](https://github.com/Justify87/ladies-explorer-android/pull/58) integriert)

**Abhängigkeit:** T10.7 abgeschlossen. **Referenzen:** P01–P51/X01–X11, [UI-Vertrag](DESIGN.md).

**Ziel:** Betroffene Profileinstiege über alle Schichten konsistent integrieren.

**Integrierter Teilauftrag (F01–F05):** [PR #54](https://github.com/Justify87/ladies-explorer-android/pull/54) bindet gespeicherte Personen-/Grid-Schreibaktionen an gerenderte Tokens und native CAS; Standalone-Profile verweigern stille Ownerwechsel und erlauben Rebinding nach bestätigtem Merge.

**Integrierter Teilauftrag – Profilöffnung:** [PR #55](https://github.com/Justify87/ladies-explorer-android/pull/55) bindet reine Profilöffnungen aus Grid, SavedPeople, normaler Suche, SavedSearch-Ergebnissen und Karte an aktuelle Surface-/Run-/Fenster-/Owner-Kontexte. Verzögerte Klicks nach Tab-, Filter-, Reprojektions-, Navigations- oder Lifecycle-Wechsel werden vor der Quellenoperation abgewiesen; [CI #45020251926](https://github.com/Justify87/ladies-explorer-android/actions/runs/45020251926) erfolgreich.

**Integrierter Teilauftrag – SavedSearch-Quittierung:** [PR #56](https://github.com/Justify87/ladies-explorer-android/pull/56) bindet explizite „Gelesen“-Aktionen an gerenderte Listen-/Ergebnisansicht, Revision, Ergebnisfenster und Aktionsgeneration. Verzögerte Aktionen nach Navigation, Reprojektion, Dialog oder Lifecycle-Wechsel werden vor dem nativen Ack verworfen; Rust-CAS bleibt die Commit-Grenze. [PR-CI #45029048049](https://github.com/Justify87/ladies-explorer-android/actions/runs/45029048049) und [main-CI #45030774068](https://github.com/Justify87/ladies-explorer-android/actions/runs/45030774068) erfolgreich.

**Integrierter Teilauftrag – SavedPeople-Statusaktionen:** [PR #57](https://github.com/Justify87/ladies-explorer-android/pull/57) bindet einzelne Statuschecks an die vorhandene gerenderte SavedPerson-Aktion und Batchchecks an Auswahl, Listenfenster und Aktionsgeneration. Verzögerte Prüfungen nach Tab-, Sortier-, Reprojektions- oder Lifecycle-Wechsel werden vor Netzwerk-I/O abgewiesen; [PR-CI #45032730497](https://github.com/Justify87/ladies-explorer-android/actions/runs/45032730497) erfolgreich.

**Integrierter Teilauftrag – SavedSearch-Zeilenaktionen:** [PR #58](https://github.com/Justify87/ladies-explorer-android/pull/58) bindet Zeilen-, Pane-, Dialog- und Editoraktionen an gerenderte Kontexte und schützt späte Editor-Reads. [PR-CI #45038090081](https://github.com/Justify87/ladies-explorer-android/actions/runs/45038090081) und [Main-CI #45039471481](https://github.com/Justify87/ladies-explorer-android/actions/runs/45039471481) erfolgreich.

**Integrierter Teilauftrag – SavedSearch-Ergebnisfenster:** [PR #60](https://github.com/Justify87/ladies-explorer-android/pull/60) bindet manuelle/automatische Pagination und Retry/Reload an das gerenderte Fenster und seine Aktionsgeneration. JVM-Regressionen für Suchwechsel, Lifecycle, erneuten Read, Busy/gesperrte Operation und Fehler-Recovery ohne Ack; [PR-CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45396569605) erfolgreich.

**Laufender Teilauftrag – SavedPeople-Pagination:** Branch `fix/t10-8-saved-people-pagination-admission` bindet manuelle und automatische Regular-Pagination sowie Watchlist-Pagination an die gerenderte Fensterinstanz, Auswahl und Aktionsgeneration. JVM-Regression für Kontextwechsel, ersetzte Fenster, Busy und explizite Fehler-Recovery ergänzt. Technische Validierung und Merge ausstehend.

**Umfang:**
- [ ] Swipe/Grid/Karte/Suche/Gespeichert einschließlich gespeicherter Ergebnisse, Rückkehr, A/B, Informationsdichte, Accessibility und Altcode überprüfen.
- [ ] Vertikale Regression von UI bis SQLite soweit betroffen: Präferenz/Favorit/Undo/Skip/Reset/Replay, Merge/Dirty Notes/Late Write/Archive Cancel, unabhängige Projektionen, SavedSearch-Ack/Revision sowie Fehler/Offline/Status/Radius/Pagination.

**Akzeptanz:**
- [ ] Keine zweite Implementierungsroute, kein Funktionsverlust und kein Scope-Drift; konkrete technische Testbelege.

### T10.9 — PLANNED · Abschluss M05.10 (G10)

**Abhängigkeit:** T10.8.

**Ziel / Akzeptanz:**
- [ ] Abschlussreview, erfolgreiche ausgewählte technische Prüfungen, APK und integrierten Stand belegen; Ist-Dokumentation aktualisieren.
- [ ] Keine ungelösten hohen In-Scope-Befunde; Abschluss mit Merge.

## Danach – M06

Erst nach G10 und Nutzerauftrag. Schema `rust/ladies-core/src/schema.rs` (encounters, encounter_services, price_lists, price_items) vorhanden, keine daraus abgeleitete vollständige UI.
- [ ] **M06.1 — PLANNED:** Begegnungen/Besuche, Service-Details, Gesamt-/Sympathiebewertung, private Begegnungsnotiz/Preisangaben: tatsächliche Daten/API und bestätigten fachlichen Bedarf abgleichen, fehlenden nativen Bearbeitungsumfang festlegen und umsetzen. Personennotiz getrennt; Persistenz-/Identitätsintegrität prüfen.
- [ ] **M06.2 — PLANNED / Scope bestätigen:** manuelle Preislisten/-historie/letzter Preis gegen fachlichen Bedarf und Schema abgleichen; nur bestätigten UI-Umfang umsetzen, keine automatische Extraktion.
- [ ] **M06.3 — PLANNED / Scope bestätigen:** globale Statistik, globaler Verlauf, eigenständiges Medienarchiv, Karten-/Geocoding-Erweiterungen gegen Hauptplan abgleichen; keine Implementierung ohne Nutzerentscheidung. Vorhandene Karte/SavedSearch/WhatsApp nicht erneut entwickeln.
- [ ] **M06.4 — PLANNED:** bestätigte neue Funktionen integriert prüfen und mit Merge abschließen.

## Später – M07

Nach M06 bzw. ausdrücklich freigegebenem Umfang.
- [ ] **M07.1 — PLANNED:** technische Gesamtintegration und Stabilität der App anhand vorhandener automatisierter Regressionen, Code und konkreter Fehlerbefunde prüfen: Suche, gespeicherte Suchen, Profile/Medien/Identität, Karte, Lifecycle, Offline und Diagnose. Keine Wiedereröffnung bereits abgeschlossener Geräteprüfaufgaben.
- [ ] **M07.2 — PLANNED / Scope bestätigen:** ausschließlich etwaigen zusätzlichen Bedarf an bewussten lokalen Diagnoseexporten klären; keine Nutzerdatenmigration einschließlich Backup/Restore gemäß [PRODUCT](PRODUCT.md#produktziel-und-grenzen). Keine Implementierung ohne ausdrückliche Freigabe.
- [ ] **M07.3 — PLANNED:** abschließende Produkt-/Release-Reife, bekannte Restfehler und Distribution gegen bestätigten Scope prüfen; keine Veröffentlichung ohne Auftrag.

## Unabhängige technische Bereinigung

### DC02 — DONE · Altimport entfernt und gemergt ([PR #52](https://github.com/Justify87/ladies-explorer-android/pull/52))

**Nutzerentscheidung:** 20.11.2026; Web-App obsolet, keine Nutzerdatenmigration. **Reihenfolge:** Vor weiterer Featurearbeit.

**Umfang:**
- [x] Bestehenden Web-App-Import einschließlich UI, ViewModel/CoreStore-/UniFFI-/Rust-Pfaden, Exportskript und ausschließlich importgebundenen Tests/CI-Schritten entfernen.
- [x] Synthetische Testdaten als isolierte native v2-Datenbank bereitstellen; Integritätsregressionen erhalten.

**Akzeptanz:**
- [x] Keine nutzerseitige Migrationsroute oder Ersatz-Importoberfläche.
- [x] Native Android-Schemaentwicklung unverändert; keine Eingriffe in bestehende Android-Nutzerdaten.
- [x] Technische Integrationsprüfungen und grüner CI-Gate im separaten [PR #52](https://github.com/Justify87/ladies-explorer-android/pull/52), [CI #44828090537](https://github.com/Justify87/ladies-explorer-android/actions/runs/44828090537). [Produktsoll](PRODUCT.md#produktziel-und-grenzen).

- [x] **DC01 — DONE:** [PR #49](https://github.com/Justify87/ladies-explorer-android/pull/49) – obsolete Produktionspfade und UniFFI-Exporte entfernt, Testgrenzen korrigiert, gültige Fachverträge erhalten.

## Unabhängiger Klärungspunkt

- [ ] **L01 — READY:** libmpv/FFmpeg-AAR-Lizenz, konkret enthaltene Bibliotheken/ABIs, Corresponding Source und Weiterverteilungspflichten auch für bereits öffentlich verfügbare Debug-Artefakte klären. [NOTICE](../NOTICE). Keine Lizenzfreigabe oder Behauptung eines Rechtsverstoßes alleine aus Wrapper-Lizenz; vor weiterer öffentlicher Distribution klären.

Frühere operative Register/Belege bleiben im [Git-Stand vor der Konsolidierung](https://github.com/Justify87/ladies-explorer-android/tree/c6114a6f51b2c2ce99e986efd939ab9115708b70/docs) nachvollziehbar. Neue Aufgaben ausschließlich hier.
