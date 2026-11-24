# Arbeits- und Fortschrittsregister

Einzige operative Quelle für Aufgaben, Reihenfolge, Status und Abnahmen.

**Nächster Schritt: M05.11 (G11), T11.4 READY.** M05.10 abgeschlossen; M06/M07 erst nach G11 und auf Nutzerauftrag. L01 unabhängig klären.

## Ziel und Grenzen

[PRODUCT](PRODUCT.md), [ARCHITECTURE](ARCHITECTURE.md) und [DESIGN](DESIGN.md) sind die maßgeblichen Verträge, keine parallelen Backlogs.

## Fortschritt

| Meilenstein | Stand | Ergebnis / Rest |
| --- | --- | --- |
| M00–M02 Basis, Domänenmodell, damaliger Import | DONE | [PR #1](https://github.com/Justify87/ladies-explorer-android/pull/1)–[#3](https://github.com/Justify87/ladies-explorer-android/pull/3); abgeschlossen. |
| M03 Suche/Parser | DONE | [#4](https://github.com/Justify87/ladies-explorer-android/pull/4), [#8](https://github.com/Justify87/ladies-explorer-android/pull/8); abgeschlossen. |
| M04 Identität, Historie, Medien | DONE | [#5](https://github.com/Justify87/ladies-explorer-android/pull/5); abgeschlossen. |
| M05.0–M05.9 Frontend-Parität | DONE (Nutzerabschluss 19.11.) | [#23](https://github.com/Justify87/ladies-explorer-android/pull/23), CI #393. Keine Wiedereröffnung. |
| M05.10 Unified Profile Experience | DONE | T10.0–T10.7 abgeschlossen; T10.8 [#54](https://github.com/Justify87/ladies-explorer-android/pull/54)–[#58](https://github.com/Justify87/ladies-explorer-android/pull/58), [#60](https://github.com/Justify87/ladies-explorer-android/pull/60)–[#75](https://github.com/Justify87/ladies-explorer-android/pull/75) integriert; T10.8/T10.9 Abschlussbilanz unten. |
| M05.11 Gezielte Qualitätskonsolidierung | DOING | T11.1–T11.4: belegte Inkonsistenzen und Redundanzen vor M06 gezielt beheben; keine großflächige Neuarchitektur. |
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

## Abgeschlossen – M05.10 (G10)

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

### T10.8 — DONE · Vertikale Integration (G10.3)

**Integrierter Umfang:** [#54](https://github.com/Justify87/ladies-explorer-android/pull/54)–[#58](https://github.com/Justify87/ladies-explorer-android/pull/58), [#60](https://github.com/Justify87/ladies-explorer-android/pull/60)–[#75](https://github.com/Justify87/ladies-explorer-android/pull/75). Geräteprüfungen dieses Umfangs gemäß Merge-Regel DONE. Abschlussbasis `3ec501d540866d096275675d2f32d9c211a50cf1` (Dateibaum `6deeb940f9c0fb3e1d2ebd3c1c8f040e572c67d8`, identisch mit PR #75 Head `925baa93654f1d42ad597ce981d23b69cd87482e`). Vorhandene Testpfade und produktive Aufrufer gegen PRODUCT/DESIGN/ARCHITECTURE geprüft; keine neue Implementierungsroute.

| Abnahmebereich | Integrierte Belege / vorhandene Regressionen |
| --- | --- |
| Swipe/Grid/Karte/Suche/Gespeichert, Rückkehr und Profilöffnung | [#55](https://github.com/Justify87/ladies-explorer-android/pull/55), [#60](https://github.com/Justify87/ladies-explorer-android/pull/60)–[#74](https://github.com/Justify87/ladies-explorer-android/pull/74); `ShellRouteTest`, `ProfileEntryAdmissionTest`, `SearchRunStateTest`, `SavedPeopleStateTest`, `SavedSearchPaneTest`. |
| A/B, Informationsdichte, Accessibility, Medien und Altcode | Gemeinsame `ProfileShell`/`ProfileFlow` aus `DiscoverScreen` und `ProfileDetailScreen`; T10.3–T10.7, `ProfileHeroLayoutTest`, `ProfilePresentationTest`, `ProfilePhotoGestureTest`, `ProfilePanelsTest`, `ProfileShellDeviceTest`, `ProfileGestureDeviceTest`, `ProfileRestoreDeviceTest`, `GalleryVideoDeviceTest`. Instrumentierung gebaut, Geräteabnahme gemäß Merge-Regel; keine separate Geräteausführung behauptet. |
| Präferenz/Favorit/Undo/Skip/Reset/Replay | [#54](https://github.com/Justify87/ladies-explorer-android/pull/54); `SwipeDeckStateTest`, `ProfileProjectionTest.productionPreferenceCommitUsesCasThenTraversesOnlyForExplicitDeckDecision`, `committedNeutralResetReprojectsWithoutVisitEvenIfFeedReadFails`; Rust `lib.rs`: `listing_favorite_checks_owner_and_expected_value_atomically`, `swipe_writes_and_undo_check_owner_and_previous_preference_atomically`. |
| Merge/Dirty Notes/Late Write/Archivabbruch | [#54](https://github.com/Justify87/ladies-explorer-android/pull/54), [#72](https://github.com/Justify87/ladies-explorer-android/pull/72)–[#75](https://github.com/Justify87/ladies-explorer-android/pull/75); `StandaloneProfileOperationsTest`, `ProfileNoteStateTest`, `SwipeProfileWindowTest`; Rust `identity.rs`, `profile_detail.rs::note_cas_preserves_other_fields_and_rejects_wrong_owner`, `media.rs::archive_cancel_after_first_commit_defers_late_download_and_retry_reuses_asset`. |
| Unabhängige Projektionen | `ProfileProjectionTest.actualDiscoveryPublisherKeepsSuccessfulSiblingAndRejectsOutdatedEpoch`, `ProfilePanelsTest`; Rust `source/storage.rs::merge_reprojects_existing_run_saved_people_saved_window_and_map_without_source`. |
| SavedSearch-Ack/Revision | [#56](https://github.com/Justify87/ladies-explorer-android/pull/56), [#58](https://github.com/Justify87/ladies-explorer-android/pull/58), [#60](https://github.com/Justify87/ladies-explorer-android/pull/60); `SavedSearchPaneTest`; Rust `saved_search.rs::changed_baseline_rejects_cursor_and_stale_acknowledgement`, `person_writes_advance_ack_revision_without_changing_saved_membership`. |
| Fehler/Offline/Status/Radius/Pagination | [#57](https://github.com/Justify87/ladies-explorer-android/pull/57), [#61](https://github.com/Justify87/ladies-explorer-android/pull/61)–[#71](https://github.com/Justify87/ladies-explorer-android/pull/71), [#73](https://github.com/Justify87/ladies-explorer-android/pull/73); `SwipeProfileReadTest`, `SearchRunStateTest`, `SavedPeopleStateTest`, `ProfileEntryAdmissionTest`; Rust `discovery.rs`, `map.rs`, `source/storage.rs::saved_status_records_observations_and_rejects_stale_updates`, `saved_search.rs::only_complete_radius_verified_search_replaces_saved_baseline`. |

- [x] Oberflächen- und vertikale Regressionsmatrix abgeglichen; kein weiterer abnahmerelevanter Defekt im geprüften Umfang belegt. Keine allgemeine Fehlerfreiheit behauptet.
- [x] Technische Abnahme: keine zweite Implementierungsroute, kein belegter Funktionsverlust oder Scope-Drift. [CI #45412134217](https://github.com/Justify87/ladies-explorer-android/actions/runs/45412134217) (PR #74) erfolgreich; [CI #45412651895](https://github.com/Justify87/ladies-explorer-android/actions/runs/45412651895) (PR #75, identischer integrierter Dateibaum) vollständig erfolgreich.

### T10.9 — DONE · Abschluss M05.10 (G10)

**Abhängigkeit:** T10.8 abgeschlossen.

- [x] Abschlussreview der betroffenen Aufrufer, gemeinsamen Profilprojektion, Testmatrix, Architekturgrenzen und Dokumentationskonsistenz durchgeführt; README, PRODUCT, DESIGN und ARCHITECTURE weiterhin maßgeblich. Keine weiteren In-Scope-Befunde hoher Schwere im geprüften Umfang.
- [x] Technischer Abschlussbeleg: [CI #45412651895](https://github.com/Justify87/ladies-explorer-android/actions/runs/45412651895) mit erfolgreichem ARM64-Debug-APK-/Instrumentierungs-APK-Build, JVM-Tests, Lint, Rust-Tests, synthetischer nativer DB-Regression, Clippy, Quell-/APK-Architekturprüfungen und abschließendem build-Gate. Reguläre automatische CI speichert gemäß README kein Debug-APK-Artefakt; keine separate manuelle Veröffentlichung.
- Abschluss mit Merge von [PR #76](https://github.com/Justify87/ladies-explorer-android/pull/76); keine Code-, CI- oder Fachvertragsänderung. M06/M07 bleiben gesperrt bis Nutzerauftrag.

## Jetzt – M05.11 (G11) · Gezielte Qualitätskonsolidierung

**Abhängigkeit:** M05.10 DONE. **Reihenfolge:** vor M06; M06/M07 bleiben ohne gesonderten Nutzerauftrag ungestartet. **Ziel:** Nachgewiesene fachliche Widersprüche, unnötige Zustands-/Aufrufdoppelungen und sichtbare Redundanzen ursächlich beheben, ohne die bewährte Kotlin-/Rust-Architektur oder den Hobbyprojektumfang aufzublähen.

**Arbeitsweise:** Vor jeder Änderung betroffene Aufrufer, aktuellen Ist-Stand und [PRODUCT](PRODUCT.md), [ARCHITECTURE](ARCHITECTURE.md) bzw. [DESIGN](DESIGN.md) bedarfsorientiert abgleichen. Vermutete Probleme erst belegen; zulässiges Ergebnis ist eine begründete Nichtänderung. Kleine, thematisch abgeschlossene PRs auf integriertem `main`, gezielte positive/negative Regressionen und vorhandene CI. Kein repoweiter neuer Aufrufgraph, neues Framework, Event-Bus, allgemeiner Operationsmanager, zusätzliche Testinfrastruktur oder pauschale Legacy-/Datenbankmigration. Bestehende Owner-/CAS-, UI-Admission-, Drain-/Cancel-, Undo- und Reprojektionsverträge erhalten; keine stillen Fachänderungen.

### T11.1 — DONE · Fachliche Inkonsistenzen

**Integrierter Beleg:** [PR #78](https://github.com/Justify87/ladies-explorer-android/pull/78), Merge `430b53ccd8c0910f7e081ff68983db4037469772`, [CI #45417037316](https://github.com/Justify87/ladies-explorer-android/actions/runs/45417037316) erfolgreich.

- Internationale 00-Vorwahlen korrigiert; historische Telefonschlüssel und Identitätsevidenz geschützt, keine Schemaänderung oder automatische Zuordnung.
- ALL/AI/NATURAL behalten gemeinsame Sichtbarkeitsregeln und Definitionsbytes; Beschriftungen beziehen sich ausschließlich auf Quellenmarker.
- Gemeinsame Statusdarstellung in Profil und gespeicherten Personen; nicht belegte Statuswerte bleiben unbekannt.
- Technische Abnahme: vollständiges CI-Gate erfolgreich; Rust-/JVM-Regressionen, native DB-Regression, Clippy, Android-Build, Lint und Architekturprüfungen. Geräteprüfungen gemäß Merge-Regel DONE.

### T11.2 — DONE · Zustands- und Aufrufredundanzen

**Integrierter Beleg:** [PR #80](https://github.com/Justify87/ladies-explorer-android/pull/80), Merge `ac9a652288deb68987f6c7964d5a9cbf74388fb2`, [CI #45420041849](https://github.com/Justify87/ladies-explorer-android/actions/runs/45420041849) erfolgreich. Abschluss auf ausdrücklichen Nutzerauftrag vom 24.11.2026.

- Sichtbarkeitspublikation auf relevante Lifecycle-Übergänge begrenzt; identische SavedSearch-Sichtbarkeit ohne erneute Dirty-Prüfung. Sofortige Revocation bei Navigation erhalten.
- Statischen Filterkatalog nach erfolgreicher Publikation wiederverwenden, dynamische Nutzungshäufigkeiten weiterhin lesen. Zweiter aktiver Karten-Read und Swipe-Profilinvalidierung bleiben mangels belegter sicherer Entbehrlichkeit erhalten (mögliche zwischenzeitliche native Commits bzw. Statusdaten im Profil).
- Statusprüfung invalidiert SavedSearch im gemeinsamen Mutationspfad, bei Coroutine-Cancel separat; Karten-Recovery auch bei Writefehler. Bestehende native Commit-/Cancel-Verträge unverändert.
- Regressionen in `SearchRunStateTest`, `SavedSearchPaneTest` und `SavedPeopleStateTest` ergänzt; vollständiger PR-CI-Lauf erfolgreich, Geräteprüfungen gemäß Merge-Regel DONE. Keine separate lokale Testausführung oder gemessene Beschleunigung behauptet.
- Weitergehender Caller-/Projektionsreview war vor Nutzerabschluss nicht vollständig belegt; verbleibende konkrete Befunde ausschließlich im regulären Scope von T11.4 behandeln, keine pauschale Wiedereröffnung.

### T11.3 — DONE · Nutzerseitige Doppelungen

**Abschlussbeleg:** [PR #82](https://github.com/Justify87/ladies-explorer-android/pull/82), Implementierungscommit `9022ffdf2577f4494b6763f8d5a1ad6ce11c16b4`, [CI #45422297688](https://github.com/Justify87/ladies-explorer-android/actions/runs/45422297688) vollständig erfolgreich. Merge ausdrücklich freigegeben am 24.11.2026.

- Sichtungsdatum nur einmal, Änderungsdatum bei gleicher Tagesangabe zusammengefasst; Quellenstatusprüfung vom lokalen Neuladen abgegrenzt. Anzeigenanzahl in der Profilübersicht konsolidiert, Analyse-Fallback bei fehlender Personenzusammenfassung.
- Suchliste wegen eigenständigem Zugriff auf nur im Grid ausgeblendete Likes erhalten; doppelter Filterwechsel und Trefferzahl im Warntext entfernt. Suchentwurf, Navigation und bestehende Aktionskontexte unverändert.
- Gezielte JVM-Regressionen in `SavedPeopleStateTest`, `ProfilePanelsTest` und `SearchRunStateTest`; ARM64- und Instrumentierungs-APK, JVM-Tests, Lint, Rust-Tests, native DB-Regression, Clippy, Quell-/APK-Architekturprüfungen und Build-Gate erfolgreich. Geräteprüfungen mit Merge gemäß Merge-Regel DONE; keine separate Geräteausführung oder lokale Build-Ausführung behauptet.

### T11.4 — READY · Enges Altcode- und Abschlussreview

**Abhängigkeit:** T11.1–T11.3 abgeschlossen. **Anker:** jeweils betroffene Kotlin-/Rust-/UniFFI-Aufrufer, Tests, `docs/` und bestehender [CI-Vertrag](../README.md#prüfung--ci).

**Umfang:**
- [ ] Ausschließlich im Zuge der vorigen Aufgaben eindeutig abgelöste Funktionen, State-Felder, Wrapper, FFI-Exporte, Kommentare und Tests vollständig bereinigen. Native Altdefinitionen, Serialisierung, Framework-Aufrufer und interne Schemaentwicklung nur nach belegter Entbehrlichkeit anfassen.
- [ ] Betroffene UI→CoreStore→Rust→SQLite-Pfade und umgekehrte Aufrufer auf verbleibende Widersprüche, funktionale Regressionen und unnötige neue Abstraktionen reviewen; vorhandene gezielte Tests und vollständigen CI-Gate für den integrierten Abschlussstand belegen.

**Akzeptanz:** Keine offenen hohen In-Scope-Befunde, keine zweite fachliche Implementierungsroute, kein belegter Funktionsverlust und keine zusätzlichen Frameworks/Jobs. ROADMAP und tatsächlich geänderte Verträge im jeweiligen PR aktualisieren; Abschlussbelege nennen, danach Status DONE. Kein eigenmächtiger Merge; keine separate Geräteabnahme nach [Merge-Regel](#pflege).

## Danach – M06

Erst nach M05.11 (G11) und gesondertem Nutzerauftrag. Schema `rust/ladies-core/src/schema.rs` (encounters, encounter_services, price_lists, price_items) vorhanden, keine daraus abgeleitete vollständige UI.
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
