# Arbeits- und Fortschrittsregister

Einzige operative Quelle für Aufgaben, Reihenfolge, Status und Abnahmen. Stand: 20.11.2026, [main 4a085ed7](https://github.com/Justify87/ladies-explorer-android/commit/4a085ed7c76299ad0d7f4b732110f59a58c4ecd0).

**Nächster Schritt: T10.7 – Lifecycle-/Performance-Restreview.** M05.10 abschließen, M06/M07 erst auf Nutzerauftrag. L01 unabhängig klären.

## Ziel und Grenzen

Eigenständige lokale Android-App auf einem Gerät: Kotlin/Compose für UI/Lifecycle, Rust/SQLite via UniFFI für Fachlogik und Persistenz. Relevante Web-App-Daten und Regeln erhalten; kein Server/Flask/Python-Runtime, Messenger, Synchronisierungsdienst, automatische KI-/OCR-Preisextraktion oder fachfremde Artefact-Funktionen. [Architektur](ARCHITECTURE.md), [Quelle](SOURCE.md), [UI-Vertrag](M05-PLAN.md), [Fach-/UX-Anforderungsinventar](M05-PARITY.md) sind Spezifikationen, keine parallelen Backlogs.

## Fortschritt

| Meilenstein | Stand | Ergebnis / Rest |
| --- | --- | --- |
| M00–M02 Basis, Domänenmodell, Import | DONE (technisch) | [PR #1](https://github.com/Justify87/ladies-explorer-android/pull/1)–[#3](https://github.com/Justify87/ladies-explorer-android/pull/3); reale Datenübernahme M07.1. |
| M03 Suche/Parser | DONE (technisch) | [#4](https://github.com/Justify87/ladies-explorer-android/pull/4), [#8](https://github.com/Justify87/ladies-explorer-android/pull/8); Live-Gesamtprüfung M07.1. |
| M04 Identität, Historie, Medien | DONE (technisch) | [#5](https://github.com/Justify87/ladies-explorer-android/pull/5); Gesamtprüfung M07.1. |
| M05.0–M05.9 Frontend-Parität | DONE (Nutzerabschluss 19.11.) | [#23](https://github.com/Justify87/ladies-explorer-android/pull/23), CI #393. Nicht ausgeführte Alt-Geräte-/Abweichungsabnahmen bleiben nicht ausgeführt; keine Wiedereröffnung. |
| M05.10 Unified Profile Experience | ACTIVE | T10.0–T10.6 technisch umgesetzt, T10.7 teilweise integriert, T10.8/9 offen. |
| M06 Weitere Fachfunktionen | PLANNED / teilintegriert | Karte [#10](https://github.com/Justify87/ladies-explorer-android/pull/10), gespeicherte Suchen, Einstellungen, WhatsApp vorhanden; Rest unten. |
| M07 Gesamtintegration | PLANNED | App-Geräteabnahme, Stabilität, Export-/Backup- und Release-Umfang. |

Diagnose (Logging, Messungen, HTTP/HAR, TXT-Export) ist integriert [#7](https://github.com/Justify87/ladies-explorer-android/pull/7); reale Diagnoseabnahme M07.1. Ursprüngliche M00–M02-Nummerierung wich von PR-Bezeichnungen ab; maßgeblich sind obige Zuordnung und Git. Kein Prozentwert aus ungleich großen Aufgaben.

## Pflege

- **READY** ausführbar; **DOING** tatsächlich in Arbeit (Branch/PR nennen); **REVIEW** zur Prüfung; **DONE** belegter technischer oder ausdrücklicher Nutzerabschluss; **BLOCKED** Grund und nächster Schritt; **PLANNED** abhängig/vorläufig, nicht freigegeben. Gerätebelege gesondert: **DEVICE_PENDING** oder tatsächlich ausgeführt mit Ergebnis. Grüner Build ≠ Gerätetest.
- Je Arbeitskontext eine Aufgabe. Main, offene PRs, Abhängigkeiten, relevante Spezifikation/Code/Tests prüfen; konkrete beobachtbare Kriterien vor Umsetzung festlegen. Kein konkurrierender Umbau am selben State.
- Bei Unterbrechung Stand/nächsten Schritt/Blocker hier aktualisieren. Bei Abschluss Kriterien abgleichen, Beleg (PR/Commit/Test/Lauf) verlinken, Status und geänderte Fachverträge im selben PR pflegen. Erledigte Detailchecklisten zu einem Beleg verdichten; Review-Historie in PR/Git, nicht anhängen. Keine eigenmächtige Merge-/Veröffentlichungsfreigabe.

## Jetzt – M05.10 (G10)

### T10.0–T10.6 — DONE (technisch)

| ID | Ergebnis | Integrierter Beleg |
| --- | --- | --- |
| T10.0 | Baseline, Mess-/Fixture-Grundlage | [#24](https://github.com/Justify87/ladies-explorer-android/pull/24), [#25](https://github.com/Justify87/ladies-explorer-android/pull/25) |
| T10.1 / G10.0 | Charakterisierung, Owner-/Operationsverträge | [#26](https://github.com/Justify87/ladies-explorer-android/pull/26), CI #413 |
| T10.2 | Gemeinsame Projektion und Identitätsoperation | [#27](https://github.com/Justify87/ladies-explorer-android/pull/27) |
| T10.3 / G10.1 | Gemeinsamer Lazy-Profilfluss | [#28](https://github.com/Justify87/ladies-explorer-android/pull/28), CI #451 |
| T10.4 | Swipe-first Shell, adaptiver Hero | [#29](https://github.com/Justify87/ladies-explorer-android/pull/29), CI #457/#458 |
| T10.5 | Native Gesten-Arbitration | [#30](https://github.com/Justify87/ladies-explorer-android/pull/30), CI #462/#463 |
| T10.6 / G10.2 (technisch) | Rail/Undo, Swipe-Übergang und neue Galerie | [#31](https://github.com/Justify87/ladies-explorer-android/pull/31), [CI #478](https://github.com/Justify87/ladies-explorer-android/actions/runs/44710394072), [Merge #479](https://github.com/Justify87/ladies-explorer-android/actions/runs/44712016704) |

Gerätefeedback 20.11. (hakender Swipe, Layoutsprünge, Galerie-Chrome, Videos) in PR #31 bearbeitet. Neue visuelle Wiederabnahme DEVICE_PENDING in T10.9; keine behauptete Gerätefreigabe.

### T10.7 — READY · Lifecycle, Performance und Altcode

Vorgänger T10.6; P03/P20–P23/P39–P44/P49–P51. PR #31 enthält bereits drei Listing-/Owner-gebundene Slots, serialisierte Reads, Promotion ohne Cancel, begrenztes Coil-Warming in Hero-Größe, Retry und lokalen libmpv-Lifecycle/Rust-only Videotransport. Nicht neu implementieren. Code: `DiscoveryScreen.kt`, `SwipeProfile.kt`, `SwipeFlight.kt`, `ProfilePanels.kt`, `ProfilePresentation.kt`, `GalleryVideo.kt`, `MainViewModel.kt`; Tests: `SwipeProfileWindowTest`, `SwipeProfileReadTest`, `ProfilePresentationTest`, `ProfilePanelsTest`, `ProfileProjectionCharacterizationTest`, Rust-Medientests.

- [ ] Restreview Owner-/Run-/Snapshot-/Revision- und Lifecycle-Wechsel: Prefetch-Promotion/Pruning, Request/Retry/Cancel, Native Drain, Cache-/Note-/Facts-Invalidierung, SaveableState, unabhängige Reprojektion, Video-Surface-/Tempfile-Cleanup. Betroffene Aufrufer bis Rust/SQLite prüfen.
- [ ] Kein stale UI, Double Write, Source-Redo nach Commit, zusätzliche Archivierung, mehr als current+2 oder zweite Queue/HTTP-/Profilimplementierung. Nur belegte Defekte ursächlich beheben und Regression sichern.
- [ ] Messvergleich bestehender Diagnosemittel (200+ Cards, 0/1/12/80 Medien, kalt/warm, Requestzahl, Timing/HAR). Ohne Gerät technische Prüfung abschließen und Messrest in T10.9 führen. Keine behauptete Beschleunigung ohne Messung; keine allgemeine M07-Optimierung.

### T10.8 — PLANNED · Vertikale Integration (G10.3)

Vorgänger T10.7 technisch abgeschlossen. P01–P51/X01–X11, [UI-Vertrag](M05-PLAN.md).
- [ ] Alle betroffenen Einstiegspunkte (Swipe/Grid/Karte/Suche/Gespeichert inkl. gespeicherter Ergebnisse), Rückkehr, A/B, Informationsdichte, Accessibility und Altcode überprüfen.
- [ ] Vertikale Regression UI bis SQLite soweit betroffen: Präferenz/Favorit/Undo/Skip/Reset/Replay, Merge/Dirty Notes/Late Write/Archive Cancel, unabhängige Projektionen, SavedSearch-Ack/Revision, Fehler/Offline/Status/Radius/Pagination. Kein zweiter Pfad, Funktionsverlust oder Scope Drift. Konkrete Testbelege und nicht ausgeführte Fälle.

### T10.9 — PLANNED · Abschluss M05.10 (G10)

Vorgänger T10.8. Technische Integration und physische Abnahme getrennt.
- [ ] T10.9-T: Abschlussreview, erfolgreiche ausgewählte technische Prüfungen, APK und integrierten Stand belegen, Ist-Doku aktualisieren; keine ungelösten hohen in-scope Befunde.
- [ ] T10.9-D — DEVICE_PENDING: neue UX auf Gerät prüfen: native Gesten/Tap/Cancel/Undo, Frame-Timing/stabiler Viewport, Galerie/MP4/WebM/Decoder, Lifecycle/Rotation/Back/Offline, A/B-Flächenbudget, Schriftgrößen/Insets/IME/TalkBack, Cache-/HAR-Vergleich aus T10.7. Vorhandene GalleryVideoDeviceTest, ProfileGestureDeviceTest, ProfileRestoreDeviceTest, ProfileShellDeviceTest oder manuelle Fälle aus [UI-Vertrag](M05-PLAN.md) und [Fixture](../tools/M05-REFERENCE.md). Gerät/Commit/Fixture/Ergebnis protokollieren. G10 technisch getrennt abschließbar; physische Freigabe nur mit Beleg oder Nutzerentscheidung, nie alte M05-Gates öffnen.

## Danach – M06

Erst nach G10 und Nutzerauftrag. Schema `rust/ladies-core/src/schema.rs` (encounters, encounter_services, price_lists, price_items) und Legacy-Import vorhanden, keine daraus abgeleitete vollständige UI.
- [ ] **M06.1 — PLANNED:** Begegnungen/Besuche, Service-Details, Gesamt-/Sympathiebewertung, private Begegnungsnotiz/Preisangaben: tatsächliche Daten/API und Legacy-Soll abgleichen, fehlenden nativen Bearbeitungsumfang festlegen und umsetzen. Personennotiz getrennt; Persistenz-/Import-/Identitätsintegrität prüfen.
- [ ] **M06.2 — PLANNED / Scope bestätigen:** manuelle Preislisten/-historie/letzter Preis gegen Legacy/Schema/Import abgleichen; nur bestätigten UI-Umfang umsetzen, keine automatische Extraktion.
- [ ] **M06.3 — PLANNED / Scope bestätigen:** globale Statistik, globaler Verlauf, eigenständiges Medienarchiv, Karten-/Geocoding-Erweiterungen gegen Hauptplan abgleichen; keine Implementierung ohne Nutzerentscheidung. Vorhandene Karte/SavedSearch/WhatsApp nicht erneut entwickeln.
- [ ] **M06.4 — PLANNED:** bestätigte neue Funktionen integriert prüfen; technische und reale Gerätebelege getrennt.

## Später – M07

Nach M06 bzw. ausdrücklich freigegebenem Umfang.
- [ ] **M07.1 — PLANNED:** reale App-Gesamtintegration mit lokalem Testbestand: Import, Suche/Radius/Pagination, gespeicherte Such-Deltas, Profil-/Medien-/Identitäts-/Offline-Pfade, Karte/Lifecycle/Geocoding, Neustart, Stabilität/Performance. Diagnose an/aus und Firefox-HAR-Import. Prüfanleitungen: [Import](DATA-IMPORT.md), [Quelle](SOURCE.md), [Karte](MAP.md), [Diagnose](DIAGNOSTICS.md). Keine alte M05-Abnahme künstlich wieder öffnen; neue Gesamtintegration prüfen.
- [ ] **M07.2 — PLANNED / Scope bestätigen:** tatsächlichen Export-/Backup-/Restore-Bedarf Android-Datenbank/Medien abgleichen (Legacy-Export vorhanden, kein Android-Backup-Nachweis); nur freigegebenen Umfang mit Integritäts-/Wiederherstellungstest umsetzen.
- [ ] **M07.3 — PLANNED:** abschließende Produkt-/Release-Reife, bekannte Restfehler und Distribution gegen bestätigten Scope prüfen; keine Veröffentlichung ohne Auftrag.

## Unabhängiger Klärungspunkt

- [ ] **L01 — READY:** libmpv/FFmpeg-AAR-Lizenz, konkret enthaltene Bibliotheken/ABIs, Corresponding Source und Weiterverteilungspflichten auch für bereits öffentlich verfügbare Debug-Artefakte klären. [PROVENANCE](PROVENANCE.md), [NOTICE](../NOTICE). Keine Lizenzfreigabe oder Behauptung eines Rechtsverstoßes alleine aus Wrapper-Lizenz; vor weiterer öffentlicher Distribution klären.

Frühere operative Register/Belege bleiben im [Git-Stand vor der Konsolidierung](https://github.com/Justify87/ladies-explorer-android/tree/c6114a6f51b2c2ce99e986efd939ab9115708b70/docs) nachvollziehbar. Neue Aufgaben ausschließlich hier.
