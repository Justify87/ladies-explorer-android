# Arbeits- und Fortschrittsregister

Einzige operative Quelle für Aufgaben, Reihenfolge, Status und Abnahme. Maßgebliche Verträge: [PRODUCT](PRODUCT.md), [ARCHITECTURE](ARCHITECTURE.md), [DESIGN](DESIGN.md). Arbeitsweise: [AGENTS](../AGENTS.md); Prüfungen: [README](../README.md#prüfung--ci).

## Entwicklungsstand

Nächster Schritt: [M06.1a](#m061a--begegnungsbasis). M06 und M07 jeweils erst nach gesondertem Implementierungsauftrag beginnen; L01 unabhängig davon klären. Nach G07 bedarfsorientierte Wartung statt vorsorglicher weiterer Meilensteine.

## Geplante Meilensteine

### M06 — Persönliche Begegnungen und Preise

**Ziel und Umfang:** Den bestätigten lokalen Mindestumfang gemäß [PRODUCT](PRODUCT.md#persönliche-begegnungen-und-preise) im bestehenden gemeinsamen Profil nutzbar machen. M06.1 umfasst Begegnungsbasis und Leistungsdetails; danach Angebotspreise und G06. Kein neuer Root-Bereich und keine Wiederaufnahme der Web-App-Parität.

**Integrationsrahmen:** Die Tabellen `encounters`, `encounter_services`, `price_lists`, `price_items` in `schema.rs` und ihre bereits vorhandene Übertragung im Identitäts-Merge (`identity.rs`) nutzen. Betroffene Verträge und Aufrufer in `lib.rs`, `CoreStore.kt`, `ProfilePanels.kt::ProfileFlow`, `MainViewModel` und `StandaloneProfileOperations` vor der jeweiligen vertikalen Aufgabe prüfen. Bestehende Tabellen und Operationspfade bevorzugen; Schemaänderung nur bei belegter Integritätsnotwendigkeit mit datenerhaltender interner Schemaentwicklung. Keine zusätzlichen Netzwerkzugriffe, Frameworks, Stores oder spekulativen Abstraktionen.

**Gemeinsame Akzeptanz für neue Schreibpfade** ([Operationsmodell](ARCHITECTURE.md#operations--und-konsistenzmodell), [Identität](ARCHITECTURE.md#historisierung-und-identitätsoperation), [UI-Hierarchie](DESIGN.md#informationshierarchie--progressive-offenlegung)):
- Rust validiert Personen-/optionalen Inseratsowner, betroffenen Datensatz und erwarteten Bearbeitungsstand in derselben Transaktion.
- UI-Aktionen bleiben an den sichtbaren Profil-/Editor-Kontext gebunden. Veraltete Callbacks, Mehrfachklicks und Merge-Konflikte dürfen keine falsche oder doppelte Mutation auslösen; ungespeicherte betroffene Entwürfe vor Merge schützen.
- Bestätigten Commit und nachfolgenden Readfehler trennen, keine automatische Write-Wiederholung. Nur betroffene lokale Projektionen invalidieren; keine neuen Quellabrufe oder Begegnungs-/Preislisten-Prefetches.
- Offline, Back, Cancel, Lifecycle und Fehlerzustände gezielt prüfen.

**Abgrenzung (Nutzerentscheidung 24.11.2026):** M06.3 entfällt ersatzlos (globale Statistik, globaler Verlauf, eigenständiges Medienarchiv, pauschale Karten-/Geocoding-Erweiterungen). Nicht nach M07 verschieben; nur bei neuem konkretem Bedarf und ausdrücklichem Auftrag neu bewerten. Bestehende Karte, SavedSearch, WhatsApp, Medienarchivierung und Quellhistorie erhalten.

#### M06.1a — Begegnungsbasis

**Status:** PLANNED · **Abhängigkeit:** G11 abgeschlossen.

**Umfang:** Typisierte native Read-/Create-/Update-/Delete-API über UniFFI/CoreStore, kompakte aufklappbare Begegnungssektion und ein gemeinsamer Editor für Swipe und Standalone. Zeitpunkt, optionale Dauer, tatsächlich bezahlter Gesamtbetrag, Gesamt-/Sympathiebewertung und private Begegnungsnotiz; keine separaten Editorpfade.

**Akzeptanz:**
- Begegnungen einer Person über verschiedene Inserate hinweg chronologisch lesen, anlegen, ändern und ausdrücklich löschen; optionalen Inseratsbezug sicher prüfen.
- Unbekannte Werte von null unterscheiden; Bewertungen 1–5, Beträge nichtnegativ, angegebene Dauer positiv.
- Back/Abbrechen verliert Entwürfe nicht stillschweigend; Fehler sichtbar, gespeicherte Daten bei fehlgeschlagenem Write unverändert.
- Personennotiz, Präferenz, Favorit, Quellenhistorie und Swipe-Undo unverändert.

**Prüfung:** Rust-CRUD-/Owner-/Merge-Regression einschließlich veraltetem Owner/Editorstand und Rollback, JVM-Editor-/Admission-/Commit-Readfehler-Regression und gezielte Android-Integration beider Profilkontexte. Vorhandene Testframeworks verwenden.

#### M06.1b — Leistungsdetails

**Status:** PLANNED · **Abhängigkeit:** M06.1a abgeschlossen.

**Umfang:** Optionalen Grundpreis und einfache manuell erfasste Leistungszeilen (Bezeichnung, optionaler Betrag) im selben Begegnungseditor ergänzen. Keine separate Serviceverwaltung und kein zusätzlicher Kriterienkatalog.

**Akzeptanz:**
- Begegnung und Leistungszeilen gemeinsam transaktional speichern, ändern und löschen; keine leeren Leistungsbezeichnungen oder negativen Beträge.
- Unbekannt bleibt unbekannt; Gesamtpreis nicht aus unvollständigen Einzelangaben erfinden.
- Leistungszeilen bei Merge vollständig erhalten und niemals mit Quellprofil-Diensten gleichsetzen.

**Prüfung:** Gezielte Rust-/JVM-Regression betroffener Pfade; vorhandene Begegnungs- und Profiltests bleiben gültig.

#### M06.2 — Minimale manuelle Angebotspreise

**Status:** PLANNED · **Abhängigkeit:** M06.1a/b abgeschlossen.

**Umfang:** Personenbezogene manuelle Preislisten mit Datum, Preispositionen und optionaler Dauer/Bezeichnung. Native Read-/Create-/Update-/Delete-API und Profilintegration in einem vertikalen Schritt: ein gemeinsamer kleiner Editor sowie bedarfsorientierte aktuelle/historische Anzeige. Bestehende Tabellen nutzen; kein Preis-Dashboard, kein automatisches Extrahieren oder Kopieren aus Begegnungen.

**Akzeptanz:**
- Neue Liste als eigener historischer Eintrag; Bearbeiten ist ausdrückliche Korrektur, Löschen erfordert Bestätigung.
- Neueste erfasste Liste und frühere Listen nach Datum/ID eindeutig unterscheiden; keine Aussage über tatsächlich aktuell gültige Angebote.
- Beträge in EUR-Cent und nichtnegativ, angegebene Dauer positiv. Person/Inserat und Kindzeilen transaktional validieren; Merge erhält Listen und Positionen.
- Letzten bekannten bezahlten Gesamtbetrag aus Begegnungen ableiten und als solchen kennzeichnen, nicht als Angebotspreis übernehmen. Kein zweiter fachlicher Store oder doppelte Preisprojektion.

**Prüfung:** Rust-CRUD-/Historien-/Owner-/Merge-Regression, JVM-Editor-/Kontext-/Fehlerregression; beide Profilkontexte gezielt prüfen.

#### M06.4 — Gezielte Integration und Abschluss (G06)

**Status:** PLANNED · **Abhängigkeit:** M06.1a/b und M06.2 abgeschlossen.

**Umfang:** Betroffene Rust-/UniFFI-/Kotlin-/Compose-Aufrufer, Datenprojektionen, Tests und Verträge auf Korrektheit, Redundanz und abgelöste Pfade prüfen. Keine Wiedereröffnung von M05 oder Vorwegnahme der M07-Gesamtintegration.

**Akzeptanz:** Neue Funktionen in beiden Profilkontexten offline konsistent. Gezielte Regressionen belegen Identitäts-Merge einschließlich Kinddatensätzen, Entwurfs-/Stale-Owner-Schutz, atomare Writes, Cancel/Back/Lifecycle, unbekannte Werte, Readfehler nach Commit und unveränderte Swipe-/Galerie-/Notizverträge.

**Prüfung und Abschluss:** Vollständiges bestehendes CI-Gate einschließlich Rust-/JVM-Regressionen, nativer DB-Regression, Clippy, Android-Build/Lint, Architekturprüfungen und Instrumentierungs-APK. Geräteprüfungen gemäß [Merge-Regel](#pflege). PR/Commit/CI, verbleibende konkrete Befunde und geänderte Fach-/Design-/Architekturverträge im Abschlussbeleg verlinken; G06 mit Merge abgeschlossen.

### M07 — Technischer Projektabschluss

**Ziel und Umfang:** Nach G06 den bestätigten initialen Produktumfang nachvollziehbar geprüft und lokal nutzbar abschließen. M05.10/M05.11 und G06 als vorhandene Integrationsbelege nutzen. Nur konkrete abnahmerelevante Fehler ursächlich beheben; vorhandene Implementierungen und Tests bevorzugen. Keine abgeschlossenen Aufgaben wiedereröffnen.

**Abgrenzung (Nutzerentscheidung 24.11.2026):** Keine pauschale Code-/Architektur-/UI-Überarbeitung, neue Features, zusätzliche Diagnoseexporte, Nutzer-Backup/Restore/Migration, Wiederaufnahme gestrichener M06-Funktionen oder Web-App-Parität. M07.2 entfällt ersatzlos: TXT-/HAR-Speichern und -Teilen bereits vorhanden, zusätzlicher Bedarf nicht belegt. Nicht in andere Meilensteine verschieben. Keine neuen Frameworks, permanenten CI-Jobs, allgemeinen Aufrufgraphen oder Benchmarkprogramme ohne belegten Bedarf und gesonderten Auftrag.

#### M07.1 — Gezielte Gesamtintegration

**Status:** PLANNED · **Abhängigkeit:** G06 abgeschlossen.

**Umfang:** Auf dem dann integrierten Code- und Teststand vier funktionsübergreifende Ketten anhand produktiver Aufrufer, vorhandener Regressionen und konkreter Befunde prüfen; G06 nicht duplizieren:
- Suche → Swipe/Grid → gemeinsames Profil → bestätigte Entscheidung: aktive/lokale Ergebnismengen, Undo und Rückkehr konsistent.
- Swipe-/Standalone-Profil → Begegnung/Preis → Rust/SQLite → erneutes lokales Lesen: persönliche Daten, Quellenhistorie, Notiz, Favorit und Präferenz getrennt.
- Expliziter Identitäts-Merge → Erhalt aller zugehörigen Daten einschließlich Begegnungs-/Preispositionen → konsistente betroffene Profil-, Such-, SavedPeople-, SavedSearch- und Kartenprojektionen.
- Mutation → Navigation/Back/Lifecycle → Offline/Wiederöffnung: Stale-Owner-/Entwurfs-/Cancel-/Drain-Verträge und Commit/Readfehler-Trennung erhalten. Medien-, Karten- und Diagnoseverträge nur an betroffenen Übergängen prüfen.

**Akzeptanz:** Geprüfte Ketten erfüllen PRODUCT, ARCHITECTURE und DESIGN. Nachgewiesene abnahmerelevante Fehler im bestätigten Scope sind behoben und gezielt regressionsgesichert; keine parallele Implementierungsroute oder unnötige Testdopplung. Begründete Nichtänderung zulässig.

**Prüfung und Abschluss:** Tatsächliche Aufrufer und Tests nach G06 sind maßgeblich; bestehende Rust-, JVM-, Android-Integrations- und Architekturregressionen nutzen. Orientierung: SearchRun, SwipeDeck/SwipeProfileWindow, ProfileProjection, StandaloneProfileOperations, SavedPeople/SavedSearch und native Merge-Regressionen. Vollständiges CI-Gate (Rust-/JVM-Tests, native DB-Regression, Clippy, Android-Build/Lint, Quell-/APK-Architekturprüfungen und Instrumentierungs-APK) für identischen relevanten Code-/Buildstand belegen oder regulär ausführen; reiner Docs-Gate reicht nicht. Geräteprüfungen gemäß [Merge-Regel](#pflege); PR/Commit/CI und Befunde verlinken. Abschluss mit Merge.

#### M07.3 — Nutzungsreife und Abschluss (G07)

**Status:** PLANNED · **Abhängigkeit:** M07.1 abgeschlossen.

**Umfang und Akzeptanz:**
- Bestätigten Produktumfang am integrierten Stand prüfen; konkrete Fehler nach Auswirkung bewerten, abnahmeverhindernde Befunde beheben, andere nachvollziehbar dokumentieren. Keine neue Featurearbeit.
- Installierbares ARM64-Debug-APK und native Datenintegrität am Abschlussstand anhand vollständiger CI-/Buildbelege bestätigen; bei Bedarf bestehenden manuellen APK-Workflow nutzen. App-Start, DB-Status und Geräteintegration nach bestehendem Prüfvertrag beurteilen.
- PRODUCT, ARCHITECTURE, DESIGN, README und Arbeitsregister auf belegte Abweichungen prüfen und nur nötige Korrekturen vornehmen.
- Bestätigter lokaler Produktumfang vollständig, keine bekannten abnahmeverhindernden Befunde; keine allgemeine Fehlerfreiheit, nicht ausgeführte Geräteprüfungen oder externe Quellenverfügbarkeit aus CI ableiten.

**Prüfung und Abschluss:** Vollständiges bestehendes CI-Gate für den relevanten Code-/Buildstand; Geräteprüfungen gemäß [Merge-Regel](#pflege). Integrierten Stand, PR/Commit/CI und konkrete Restrisiken verlinken. G07 mit Merge des Abschluss-PR abgeschlossen; keine automatische Fortsetzung ohne neuen konkreten Auftrag.

**Distribution (nur bei ausdrücklichem Auftrag):** Lokale Nutzungsreife ist keine öffentliche Distributionsfreigabe. Ein ausdrücklich gewünschtes Release-APK durch einmalige gezielte Qualifizierung des vorhandenen minifizierten Release-Builds (insbesondere native UniFFI/JNA/JNI-/libmpv-Schnittstellen und Installierbarkeit) belegen; Debug-CI ersetzt diesen Nachweis nicht. Signierung/Versionierung nur nach bestätigtem Bedarf. L01 vor weiterer öffentlicher Distribution klären; ohne Klärung keine öffentliche Distributionsfreigabe. Keine automatische Veröffentlichung, Store-Anbindung oder neue Release-Infrastruktur. Nicht beauftragte öffentliche Distribution blockiert den lokalen G07-Abschluss nicht.

## Unabhängige Aufgaben

### L01 — Lizenzklärung

**Status:** READY.

libmpv/FFmpeg-AAR-Lizenz, enthaltene Bibliotheken und ABIs, Corresponding Source und Weiterverteilungspflichten einschließlich bereits öffentlich verfügbarer Debug-Artefakte klären ([NOTICE](../NOTICE)). Keine Lizenzfreigabe oder Behauptung eines Rechtsverstoßes allein aus der Wrapper-Lizenz. Vor weiterer öffentlicher Distribution klären, unabhängig vom lokalen Projektabschluss.

## Abgeschlossene Arbeiten

Alle Einträge dieses Abschnitts sind DONE; Geräteprüfungen gemäß Merge-Regel abgeschlossen. Keine separate Geräteausführung, lokale Build-Ausführung, gemessene Beschleunigung oder allgemeine Fehlerfreiheit aus den Abschlussbelegen ableiten. Vollständige frühere Aufgabenbeschreibungen, Testmatrizen, Befunde und Commit-Bezüge stehen im [Register vor der Neufassung](https://github.com/Justify87/ladies-explorer-android/blob/be37ca997891322acc7ace6ad63ce2828a0bcb18/docs/ROADMAP.md) und in den verlinkten PRs. Frühere operative Register: [Git-Stand vor der Konsolidierung](https://github.com/Justify87/ladies-explorer-android/tree/c6114a6f51b2c2ce99e986efd939ab9115708b70/docs).

| ID | Ergebnis | Abschlussbeleg |
| --- | --- | --- |
| M00–M02 | Basis, Domänenmodell, damaliger Import; Zuordnung maßgeblich trotz abweichender früherer PR-Bezeichnungen | [#1](https://github.com/Justify87/ladies-explorer-android/pull/1)–[#3](https://github.com/Justify87/ladies-explorer-android/pull/3) |
| M03 | Suche/Parser | [#4](https://github.com/Justify87/ladies-explorer-android/pull/4), [#8](https://github.com/Justify87/ladies-explorer-android/pull/8) |
| M04 | Identität, Historie, Medien | [#5](https://github.com/Justify87/ladies-explorer-android/pull/5) |
| Diagnose | Logging, Messungen, HTTP/HAR, TXT-Export | [#7](https://github.com/Justify87/ladies-explorer-android/pull/7) |
| M05.0–M05.9 | Frontend-Parität; Nutzerabschluss 19.11.2026, keine Wiedereröffnung | [#23](https://github.com/Justify87/ladies-explorer-android/pull/23), CI #393 |
| M05.10 / G10 | Unified Profile Experience; T10.0–T10.9 | [#76](https://github.com/Justify87/ladies-explorer-android/pull/76), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45412651895) |
| M05.11 / G11 | Qualitätskonsolidierung nach G10; T11.1–T11.4 | [#83](https://github.com/Justify87/ladies-explorer-android/pull/83), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45425545751) |
| DC01 | Obsolete Produktionspfade/UniFFI-Exporte entfernt, Testgrenzen korrigiert | [#49](https://github.com/Justify87/ladies-explorer-android/pull/49) |
| DC02 | Altimport vor weiterer Featurearbeit entfernt, native synthetische v2-Fixtures; Nutzerentscheidung 20.11.2026: Web-App obsolet, keine Nutzerdatenmigration, native Schemaentwicklung und Android-Nutzerdaten erhalten | [#52](https://github.com/Justify87/ladies-explorer-android/pull/52), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/44828090537), [Produktsoll](PRODUCT.md#produktziel-und-grenzen) |

### Aufgabenbelege zu M05.10 und M05.11

| ID | Ergebnis | Abschlussbeleg |
| --- | --- | --- |
| T10.0 | Baseline, Mess-/Fixture-Grundlage | [#24](https://github.com/Justify87/ladies-explorer-android/pull/24), [#25](https://github.com/Justify87/ladies-explorer-android/pull/25) |
| T10.1 / G10.0 | Charakterisierung, Owner-/Operationsverträge | [#26](https://github.com/Justify87/ladies-explorer-android/pull/26), CI #413 |
| T10.2 | Gemeinsame Projektion und Identitätsoperation | [#27](https://github.com/Justify87/ladies-explorer-android/pull/27) |
| T10.3 / G10.1 | Gemeinsamer Lazy-Profilfluss | [#28](https://github.com/Justify87/ladies-explorer-android/pull/28), CI #451 |
| T10.4 | Swipe-first Shell, adaptiver Hero | [#29](https://github.com/Justify87/ladies-explorer-android/pull/29), CI #457/#458 |
| T10.5 | Native Gesten-Arbitration | [#30](https://github.com/Justify87/ladies-explorer-android/pull/30), CI #462/#463 |
| T10.6 / G10.2 | Rail/Undo, Swipe-Übergang, Galerie; Gerätefeedback 20.11. bearbeitet | [#31](https://github.com/Justify87/ladies-explorer-android/pull/31), [CI #478](https://github.com/Justify87/ladies-explorer-android/actions/runs/44710394072), [Merge #479](https://github.com/Justify87/ladies-explorer-android/actions/runs/44712016704) |
| T10.7 | Lifecycle, Performance und Altcode; Cache-Promotion korrigiert | [#53](https://github.com/Justify87/ladies-explorer-android/pull/53), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45013729939) |
| T10.8 / G10.3 | Vertikale Integration und Regressionsmatrix | [#54](https://github.com/Justify87/ladies-explorer-android/pull/54)–[#58](https://github.com/Justify87/ladies-explorer-android/pull/58), [#60](https://github.com/Justify87/ladies-explorer-android/pull/60)–[#75](https://github.com/Justify87/ladies-explorer-android/pull/75), [CI #74](https://github.com/Justify87/ladies-explorer-android/actions/runs/45412134217), [CI #75](https://github.com/Justify87/ladies-explorer-android/actions/runs/45412651895) |
| T10.9 / G10 | Abschlussreview nach T10.8; keine weiteren hohen In-Scope-Befunde belegt | [#76](https://github.com/Justify87/ladies-explorer-android/pull/76), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45412651895) |
| T11.1 | Fachliche Inkonsistenzen bei Telefonnummern, Filtersemantik und Status behoben | [#78](https://github.com/Justify87/ladies-explorer-android/pull/78), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45417037316) |
| T11.2 | Zustands-/Aufrufredundanzen reduziert; Nutzerabschluss 24.11.2026, verbleibender Callerreview in T11.4 | [#80](https://github.com/Justify87/ladies-explorer-android/pull/80), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45420041849) |
| T11.3 | Nutzerseitige Doppelungen bereinigt; eigenständiger Suchlisten-Zugriff erhalten | [#82](https://github.com/Justify87/ladies-explorer-android/pull/82), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45422297688) |
| T11.4 / G11 | Enges Altcode-/Aufrufer- und Abschlussreview; keine weiteren hohen In-Scope-Befunde belegt | [#83](https://github.com/Justify87/ladies-explorer-android/pull/83), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45425545751) |

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

- Offene Arbeiten vor abgeschlossenen Arbeiten führen. Eigenständig ausführbare offene Aufgaben mit stabiler ID, eigener Überschrift und einem maßgeblichen Status sowie risikogerechten Angaben zu Abhängigkeiten, Umfang, Akzeptanz und Prüfung beschreiben.
- Neue Aufgaben ausschließlich hier; keine parallelen Aufgabenregister, redundanten Statusangaben oder mehrfach gepflegten Aufgabenbeschreibungen. Meilensteinabschluss aus zugehörigem Abschluss-/Gate-Eintrag ableiten. Kein Prozentwert aus ungleich großen Aufgaben.
- Aufgabenwahl und Ausführung nach [AGENTS](../AGENTS.md#arbeitszyklus), jeweils auf integriertem Stand; erforderliche Nutzerfreigaben bleiben verbindlich. Bei Statusänderungen Entwicklungsstand, nächste Aufgabe und betroffene Abhängigkeiten konsistent halten; bei Unterbrechung Stand, nächsten Schritt oder Blocker am Eintrag festhalten.
- Beim Abschluss Kriterien abgleichen und Status samt Beleg im selben PR pflegen. Abgeschlossene Aufgaben zu Ergebnis und nachvollziehbarem PR-/CI-/Commit-Beleg verdichten; weiterhin gültige Erkenntnisse vor ihrer Entfernung in den zuständigen Vertragsdokumenten absichern. Historische Implementierungs- und Reviewdetails verbleiben in Git/PR.
- Die Informationsstruktur bei Bedarf anpassen, ohne ihre semantische Funktion, gültige Anforderungen, verbindliche Scope-Grenzen oder Nutzerentscheidungen zu verändern.
