# Arbeits- und Fortschrittsregister

Einzige operative Quelle für Aufgaben, Reihenfolge, Status und Abnahmen.

**Nächster Schritt: DC02 – Altimport entfernen; danach T10.7 – Lifecycle-/Performance-Restreview.** M05.10 abschließen, M06/M07 erst auf Nutzerauftrag. L01 unabhängig klären.

## Ziel und Grenzen

[PRODUCT](PRODUCT.md), [ARCHITECTURE](ARCHITECTURE.md) und [DESIGN](DESIGN.md) sind die maßgeblichen Verträge, keine parallelen Backlogs.

## Fortschritt

| Meilenstein | Stand | Ergebnis / Rest |
| --- | --- | --- |
| M00–M02 Basis, Domänenmodell, damaliger Import | DONE | [PR #1](https://github.com/Justify87/ladies-explorer-android/pull/1)–[#3](https://github.com/Justify87/ladies-explorer-android/pull/3); abgeschlossen. |
| M03 Suche/Parser | DONE | [#4](https://github.com/Justify87/ladies-explorer-android/pull/4), [#8](https://github.com/Justify87/ladies-explorer-android/pull/8); abgeschlossen. |
| M04 Identität, Historie, Medien | DONE | [#5](https://github.com/Justify87/ladies-explorer-android/pull/5); abgeschlossen. |
| M05.0–M05.9 Frontend-Parität | DONE (Nutzerabschluss 19.11.) | [#23](https://github.com/Justify87/ladies-explorer-android/pull/23), CI #393. Keine Wiedereröffnung. |
| M05.10 Unified Profile Experience | ACTIVE | T10.0–T10.6 abgeschlossen, T10.7 teilweise integriert, T10.8/9 offen. |
| M06 Weitere Fachfunktionen | PLANNED / teilintegriert | Karte [#10](https://github.com/Justify87/ladies-explorer-android/pull/10), gespeicherte Suchen, Einstellungen, WhatsApp vorhanden; Rest unten. |
| M07 Gesamtintegration | PLANNED | Gesamtintegration, Stabilität, Export-/Backup- und Release-Umfang. |

Diagnose (Logging, Messungen, HTTP/HAR, TXT-Export) ist integriert [#7](https://github.com/Justify87/ladies-explorer-android/pull/7); abgeschlossen. Ursprüngliche M00–M02-Nummerierung wich von PR-Bezeichnungen ab; maßgeblich sind obige Zuordnung und Git. Kein Prozentwert aus ungleich großen Aufgaben.

## Pflege

**Merge-Regel:** Mit dem Merge eines PR sind alle Geräteprüf- und Geräteabnahmeaufgaben seines integrierten Umfangs immer **DONE**. Dieselbe Regel gilt rückwirkend. Status der zugehörigen Tasks und Meilensteine im Arbeitsregister entsprechend aktualisieren; keine separaten Gerätestatus, Nachabnahmen oder Verschiebungen in spätere Meilensteine. Noch nicht implementierte Funktionen und konkrete offene Fehler bleiben reguläre Aufgaben. Die technische Prüfung bleibt Bestandteil der Umsetzung.

- **READY** ausführbar; **DOING** tatsächlich in Arbeit (Branch/PR nennen); **REVIEW** zur Prüfung; **DONE** abgeschlossen; **BLOCKED** Grund und nächster Schritt; **PLANNED** abhängig/vorläufig, nicht freigegeben.
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

### T10.7 — READY · Lifecycle, Performance und Altcode

Vorgänger T10.6; P03/P20–P23/P39–P44/P49–P51. PR #31 enthält bereits relevante Implementierungen; [Architektur](ARCHITECTURE.md#swipe-read-window-und-prefetch) und [Medienvertrag](ARCHITECTURE.md#persistentes-archiv-und-anzeige) prüfen, nicht neu implementieren. Code: `DiscoveryScreen.kt`, `SwipeProfile.kt`, `SwipeFlight.kt`, `ProfilePanels.kt`, `ProfilePresentation.kt`, `GalleryVideo.kt`, `MainViewModel.kt`; Tests: `SwipeProfileWindowTest`, `SwipeProfileReadTest`, `ProfilePresentationTest`, `ProfilePanelsTest`, `ProfileProjectionTest`, Rust-Medientests.

- [ ] Restreview Owner-/Run-/Snapshot-/Revision- und Lifecycle-Wechsel: Prefetch-Promotion/Pruning, Request/Retry/Cancel, Native Drain, Cache-/Note-/Facts-Invalidierung, SaveableState, unabhängige Reprojektion, Video-Surface-/Tempfile-Cleanup. Betroffene Aufrufer bis Rust/SQLite prüfen.
- [ ] Kein stale UI, Double Write, Source-Redo nach Commit, zusätzliche Archivierung, mehr als current+2 oder zweite Queue/HTTP-/Profilimplementierung. Nur belegte Defekte ursächlich beheben und Regression sichern.
- [ ] Technische Performanceprüfung anhand vorhandener Tests, Code und verfügbarer Diagnosebelege; keine behauptete Beschleunigung ohne Messung. Keine allgemeine M07-Optimierung.

### T10.8 — PLANNED · Vertikale Integration (G10.3)

Vorgänger T10.7 abgeschlossen. P01–P51/X01–X11, [UI-Vertrag](DESIGN.md).
- [ ] Alle betroffenen Einstiegspunkte (Swipe/Grid/Karte/Suche/Gespeichert inkl. gespeicherter Ergebnisse), Rückkehr, A/B, Informationsdichte, Accessibility und Altcode überprüfen.
- [ ] Vertikale Regression UI bis SQLite soweit betroffen: Präferenz/Favorit/Undo/Skip/Reset/Replay, Merge/Dirty Notes/Late Write/Archive Cancel, unabhängige Projektionen, SavedSearch-Ack/Revision, Fehler/Offline/Status/Radius/Pagination. Kein zweiter Pfad, Funktionsverlust oder Scope Drift. Konkrete technische Testbelege.

### T10.9 — PLANNED · Abschluss M05.10 (G10)

Vorgänger T10.8. Abschluss mit Merge.
- [ ] T10.9: Abschlussreview, erfolgreiche ausgewählte technische Prüfungen, APK und integrierten Stand belegen, Ist-Doku aktualisieren; keine ungelösten hohen in-scope Befunde.

## Danach – M06

Erst nach G10 und Nutzerauftrag. Schema `rust/ladies-core/src/schema.rs` (encounters, encounter_services, price_lists, price_items) vorhanden, keine daraus abgeleitete vollständige UI.
- [ ] **M06.1 — PLANNED:** Begegnungen/Besuche, Service-Details, Gesamt-/Sympathiebewertung, private Begegnungsnotiz/Preisangaben: tatsächliche Daten/API und bestätigten fachlichen Bedarf abgleichen, fehlenden nativen Bearbeitungsumfang festlegen und umsetzen. Personennotiz getrennt; Persistenz-/Identitätsintegrität prüfen.
- [ ] **M06.2 — PLANNED / Scope bestätigen:** manuelle Preislisten/-historie/letzter Preis gegen fachlichen Bedarf und Schema abgleichen; nur bestätigten UI-Umfang umsetzen, keine automatische Extraktion.
- [ ] **M06.3 — PLANNED / Scope bestätigen:** globale Statistik, globaler Verlauf, eigenständiges Medienarchiv, Karten-/Geocoding-Erweiterungen gegen Hauptplan abgleichen; keine Implementierung ohne Nutzerentscheidung. Vorhandene Karte/SavedSearch/WhatsApp nicht erneut entwickeln.
- [ ] **M06.4 — PLANNED:** bestätigte neue Funktionen integriert prüfen und mit Merge abschließen.

## Später – M07

Nach M06 bzw. ausdrücklich freigegebenem Umfang.
- [ ] **M07.1 — PLANNED:** technische Gesamtintegration und Stabilität der App anhand vorhandener automatisierter Regressionen, Code und konkreter Fehlerbefunde prüfen: Suche, gespeicherte Suchen, Profile/Medien/Identität, Karte, Lifecycle, Offline und Diagnose. Keine Wiedereröffnung bereits abgeschlossener Geräteprüfaufgaben.
- [ ] **M07.2 — PLANNED / Scope bestätigen:** etwaigen lokalen Backup-/Exportbedarf separat klären; keine nutzerseitige Datenübernahme, Import-/Restore-Migration oder Gerätewechselmigration gemäß PRODUCT. Keine Implementierung ohne ausdrückliche Freigabe.
- [ ] **M07.3 — PLANNED:** abschließende Produkt-/Release-Reife, bekannte Restfehler und Distribution gegen bestätigten Scope prüfen; keine Veröffentlichung ohne Auftrag.

## Unabhängige technische Bereinigung

- [ ] **DC02 — READY:** Nutzerentscheidung 20.11.2026: Web-App obsolet, keine Nutzerdatenmigration. Vor weiterer Featurearbeit bestehenden Web-App-Import einschließlich Nutzeroberfläche, ViewModel/CoreStore-/UniFFI-/Rust-Pfaden, Exportskript und ausschließlich dazu gehörenden Tests/CI-Schritten ursächlich entfernen. Weiter benötigte synthetische Testdaten direkt nativ/test-only bereitstellen, relevante Integritätsregressionen erhalten. Keine Ersatzmigration/Importoberfläche; native Android-Schemaentwicklung und bestehende Android-Nutzerdaten nicht beschädigen. Nachweis, dass keine nutzerseitige Migrationsroute bleibt. Technische Integrationsprüfungen im separaten Code-PR. [PRODUCT](PRODUCT.md#produktziel-und-grenzen).

- [x] **DC01 — DONE:** [PR #49](https://github.com/Justify87/ladies-explorer-android/pull/49): obsolete Produktionspfade und UniFFI-Exporte entfernt, Testgrenzen korrigiert, gültige Fachverträge erhalten.

## Unabhängiger Klärungspunkt

- [ ] **L01 — READY:** libmpv/FFmpeg-AAR-Lizenz, konkret enthaltene Bibliotheken/ABIs, Corresponding Source und Weiterverteilungspflichten auch für bereits öffentlich verfügbare Debug-Artefakte klären. [NOTICE](../NOTICE). Keine Lizenzfreigabe oder Behauptung eines Rechtsverstoßes alleine aus Wrapper-Lizenz; vor weiterer öffentlicher Distribution klären.

Frühere operative Register/Belege bleiben im [Git-Stand vor der Konsolidierung](https://github.com/Justify87/ladies-explorer-android/tree/c6114a6f51b2c2ce99e986efd939ab9115708b70/docs) nachvollziehbar. Neue Aufgaben ausschließlich hier.
