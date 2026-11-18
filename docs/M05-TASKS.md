# M05 – Aufgabenregister und Review-Gates

Einziger operativer Status-Tracker. Ausgangsstand: Planungs-PR, keine Implementierungsaufgabe erledigt. Referenz [M05-PLAN](M05-PLAN.md), Anforderungen [M05-PARITY](M05-PARITY.md), Prozess [AGENTS](../AGENTS.md). IDs stabil; Abhängigkeiten sind Task-IDs ohne T-Präfix. Alle Tasks beginnen TODO. Status: TODO, IN_PROGRESS, REVIEW, DONE, BLOCKED; DEVICE_PENDING ist ein zusätzlicher Abnahmevermerk, kein bestandener Test. DONE = Akzeptanz belegt (ggf. nur Code-/CI-Teil; G9/Geräteabnahme separat offen). Blocker in Evidenzspalte eintragen. Initialer Einstieg: T0.1. Danach jeweils aus Status, Abhängigkeiten und Gates ableiten.

| Task | Abh. | Matrix | Umsetzung (eng begrenzt) | Akzeptanz / Test | Status | Evidenz / Rest |
| --- | --- | --- | --- | --- | --- | --- |
| T0.1 | — | — | Legacy-Screens, Router, State, CSS, Bootstrap-Nachbearbeitungen sowie native APIs/Tests je P-ID erneut inventarisieren; Zeilen-/Commit-Belege ergänzen. | Vollständige Matrix, Lücken/Abweichungen X-ID, kein unbelegter Sollumfang | REVIEW | P01–P41 statische commit-fixierte Belege in M05-PARITY; Laufzeitbeleg offen |
| T0.2 | 0.1 | — | Synthetische Referenzdaten und Szenarien anlegen; Legacy-Browser und native Ausgangs-UI für Hauptscreens erfassen; falls keine Laufzeit verfügbar, Blocker konkret dokumentieren. | Reproduzierbare Referenz/Viewport/Fixture, keine privaten Daten | BLOCKED | tools/m05-reference.py + tools/M05-REFERENCE.md; Generator-CI ausstehend, Legacy-Adapter/Browser und Android-Gerät fehlen. DEVICE_PENDING |
| T0.3 | 0.1,0.2 | — | Verträge SOURCE, ARCHITECTURE, MAP, Import und Diagnose gegen Plan prüfen; offene Klassifikationen, Video, Kartenbezug, Notiz-Semantik entscheiden/nutzerseitig bestätigen falls Scope-Konflikt. | G0 Review mit expliziten offenen Gerätefällen | BLOCKED | Statischer Vertragsabgleich unten; T0.2 Runtime-Gate noch offen |
| T1.1 | 0.3 | P01 | Theme-Farben/Shapes/Typografie, Dark Mode, Kontrast und wiederverwendbare Tokens; dynamische Defaults ersetzen. | Farb-/Layoutvergleich hell/dunkel, Font Scale/Touch | TODO | — |
| T1.2 | 1.1 | P02 | Kontextheader, Bottom-Navigation, Insets; vier Root-Ziele, Ansichtswechsel vorbereiten. | Alle vorhandenen Funktionen erreichbar, kein doppelter Header | TODO | — |
| T1.3 | 1.2 | P03 | Stabile Navigation und Back-Stack; Scroll-/Tab-/Medienposition gezielt erhalten; keine stale Profile. | Back aus jedem Einstieg, Rotation und schneller Wechsel | TODO | — |
| T1.4 | 1.3 | P04 | Bestehendes MapScreen in Discover-Shell einbetten, Lifecycle erhalten, Datenumfang kenntlich. | Karte öffnen/verlassen/Back; kein Geocoding-Refactor | TODO | — |
| T2.1 | 1.4 | P05 | Filterentwurf vs. ausgeführter Suchlauf; stabile IDs/Filter-Snapshot/Teilstatus. Aktionsprojektion für aktive Ergebnisse vorbereiten. | State-Tests: alte Antwort, Abbruch, Filteränderung, neue Suche | TODO | — |
| T2.2 | 2.1 | P06,P07 | Gemeinsamer Filtereditor: Standort, Radius, Alter, Größe, Kategorien, Treffpunkt, Gruppierung, Chips, Filtertext, Reset, Footer. | Katalog-Aliase korrekt; Suche in Optionen, kein unbeabsichtigter Reset | TODO | — |
| T2.3 | 2.2 | P08 | Top-5 häufige Filter aus gesicherten tatsächlichen Suchnutzungen ableiten; keine unnötige Datenbank neben Rust. | Tests: Sortierung, Gleichstand, nicht verwendete Filter, Neustart | TODO | — |
| T2.4 | 2.2 | P09 | Verfügbarkeitsmodus kanonisieren, andere Zeiten erhalten; Editor vs Header unterschiedliche Ausführung. | Tests: Alle/Jetzt/Heute/Aliase, kein doppelter Run | TODO | — |
| T2.5 | 2.1,2.2,2.4 | P10,P11 | Leichtgewichtige Rust-Vorschau prüfen/ergänzen, Debounce, Cancellation/Epoch, Quellzahl und lokal gefilterte Zahl unterscheiden; Radiusvertrag strikt. | Mock-Fixtures: 0/Fehler/Radiusabweichung/veraltete Antwort; keine Suchpersistenz | TODO | — |
| T2.6 | 2.1,2.2 | P12,P13 | Lokale Filter über existierende Rust-Regeln verbinden; Präferenz/Media/AI; STUDIO/FACEAPP/Business nur nach belegter Datenlage, andernfalls UI-Grenze/Abweichung. | Fixtures für Filterkombinationen und unsupported Import; keine falsche Klassifikation | TODO | — |
| T2.7 | 2.3,2.4,2.5,2.6 | P14 | Suche erfolgreich → Entdecken; Teilresultat sichtbar; Abbruch/Fehler ohne alten Zustand zu verfälschen. | Quellen-Pagination/Radius/Delta-Regression, Back und Fehler | TODO | — |
| T3.1 | 2.7 | P15 | Gemeinsame responsive Medienhero-UI, Zähler, Story-Balken, Tippzonen, Back/Mehr. | 0/1/viele Medien, kleine Displays, Gesten | TODO | — |
| T3.2 | 3.1 | P16 | Coil/Archiv/Remote, Medienreihenfolge und Video extern, Loading/Fehler/Offline. | Synthetische lokale/remote/fehlende Medien; kein neuer Downloader | TODO | — |
| T3.3 | 3.1 | P17 | Gemeinsame Profilidentität und typisierte Projektion minimal ergänzen; nur belegte Statusfelder. | Neu/bekannt/mehrere Anzeigen/unknown; keine falsche Aktivität | TODO | — |
| T3.4 | 3.3 | P18 | Action Rail, unabhängige Präferenz/Favorit, Kontakt/WhatsApp/Originalprofil; bestehende sichere Intents. | State-Sync, Fehler, unavailable contact, keine automatische Anfrage | TODO | — |
| T3.5 | 3.2,3.3,3.4 | P19 | Gemeinsamen Profilinhalt in Standalone integrieren; Altcode entfernen, Einbettung für Swipe vorbereiten. | Ein Komponentensatz, Offline-Fallback und Navigation unverändert | TODO | — |
| T4.1 | 3.5,2.7 | P20 | Aktive Suchresultat-Queue mit stabiler ID, lokaler Filterung, Replay-/Hide-Likes, ggf. expliziter Offline-Feed. | Keine globale Vermischung; Reihenfolge/Mehrfachanzeigen getestet | TODO | — |
| T4.2 | 4.1 | P21 | Medienhero/Profilinhalt ins Swipe-Deck einbetten; horizontal vs vertikal vs Tap, zugängliche Buttons. | Gestenfälle, Scrollen zu Details, kein versehentliches Like | TODO | — |
| T4.3 | 4.1,4.2 | P22 | Undo vorheriger DB-Präferenz + Position, Reset ohne Persistenz-Reset, sessionlokales Später; Fehler/Reentranz/Merge. | Undo/Reset/Skip Tests mit Race/Fehler/ID-Neuzuordnung | TODO | — |
| T4.4 | 4.2,4.3 | P23 | Begrenzte Vorausladung für nächste Profile/Medien; Cancel bei Suche/Navigation, kein unkontrolliertes Archivieren. | Keine stale Updates, begrenzte Requests, Diagnosebeleg | TODO | — |
| T5.1 | 4.4 | P24 | Gemeinsame LazyVerticalGrid-Karte: zwei Spalten, Bild-Cover, Gradient, Name/Alter, Placeholder. | Synthetische Screenshot-/Scrollprüfung und stabile IDs | TODO | — |
| T5.2 | 5.1 | P25 | Grid-Like unabhängig von Profilöffnung; gemeinsame Zustandsänderung/Filter-Aktualisierung. | Konsistenz Grid/Deck/Gespeichert, Fehler/Rollback | TODO | — |
| T5.3 | 5.2,1.4 | P26 | Swipe/Grid/Karte wechseln, Position und aktive Suche behalten; Karte nur bestehender Vertrag. | Kein unbeabsichtigter Netzwerk-Run, Kartenlifecycle | TODO | — |
| T6.1 | 5.3 | P27 | Profil-Tabs auf gemeinsamen Inhalt umstellen; aktive Tab pro Person/Route. | Swipe/Standalone zeigen gleiche Daten, keine Phantom-Tabs | TODO | — |
| T6.2 | 6.1 | P28 | Info-Raster, Merkmale, Öffnungszeiten, einklappbare Beschreibung; responsive. | Leere/lange Texte, Font Scale, gleiche Daten wie Rust | TODO | — |
| T6.3 | 6.2 | P29 | Dienste und Filterbeleg Ja/Nein/Unbekannt, keine Ableitung aus fehlendem Feld. | Fixture: positiv/negativ/fehlend/Mehrfachwerte | TODO | — |
| T6.4 | 6.1 | P30 | Analyse aus vorhandenen Konsistenz-/Medien-/Identitätsdaten; Merge-Bestätigung beibehalten. | Tests: Mehrfachanzeigen, Konflikt, keine erfundene Analyse | TODO | — |
| T6.5 | 6.1 | P31 | Typisierte Personennotiz-API in Rust/UniFFI/CoreStore, Editor mit Laden/Speichern/Fehler/Draft. | Import, Neustart, Leerwert, fremde Felder unverändert, Merge-Erhalt | TODO | — |
| T6.6 | 6.1 | P32 | Timeline, alle verknüpften Anzeigen und Snapshot-Vergleich; minimale Read-API. | Historie mit fehlenden Snapshots/Offline, Sortierung, Original-Link | TODO | — |
| T7.1 | 6.2,6.6 | P33 | Paginiertes gespeichertes Personenmodell FAVORITE/LIKE/DISLIKE mit echten Counts, stabiler Datum-/Namenssortierung. | Rust-Fixtures: Kombinationen, Cursor, Gleichstände, keine Doppelung | TODO | — |
| T7.2 | 7.1 | P34 | Tabs, Thumbnail, Metadaten, Menü und favorisieren; List-State je Tab. | Direkte Aktionen, Back, kleine Displays | TODO | — |
| T7.3 | 7.1,7.2 | P35 | Watchlist 36h, Top 3 + expand, übrige Favoriten dedupliziert; Changed-/Relisting-Badges. | Grenzzeit, Datumssortierung, mehrere Anzeigen/Änderungen | TODO | — |
| T7.4 | 7.2,7.3 | P36 | Einzel-/Batchstatus mit sicherer Quellen-API, Abbruch, begrenzte Parallelität, Progress und Fehler. | Offline/404/Timeout/Prüfseite unterscheiden; keine falsche Inaktivität | TODO | — |
| T8.1 | 7.4 | P37 | Gespeicherte-Suchen-Screen und Header-Neuindikator, bestehende CRUD/Run/Delta/Acknowledge. | Neu-/Gelesen, Umbenennen versus Definitionsänderung | TODO | — |
| T8.2 | 8.1,2.2,2.6 | P38 | Gemeinsamen Editor für gespeicherte Vorlagen, getrennte Drafts und Unsupported-Regeln. | Import/Save/Rename/Edit/Cancel und Delta-Regression | TODO | — |
| T8.3 | 8.1,5.1 | P39 | Gespeicherte Ergebnisse über gemeinsame Grid-Karten, Cursor und Offline-Profilopen. | Stabile Paging-Keys, sichtbare Deltas, keine 1000-Grenze | TODO | — |
| T8.4 | 8.2,8.3 | P40 | Konsistenzreview Loading/Empty/Error, Back/Scroll, TalkBack, Font Scale, Rotation/Neustart; nur M05-Defekte beheben. | Synthetische UI-Matrix und Fehlerzustände | TODO | — |
| T9.1 | 8.4 | P41 | Gesamtdiff gegen alle P-IDs/X-IDs, Architektur/Scope/Altcode, Full-CI incl. APK und visuelles Testprotokoll. | Alle Gates belegt; offene Abweichungen/Blocker, konkrete APK/CI-Referenz | TODO | — |
| T9.2 | 9.1 | — | Geräteabnahme der M05-Szenarien und Dokumentationsabgleich; erst bei tatsächlicher Evidenz Roadmap schließen. | G9: kein offener kritischer Befund, Status explizit für nicht ausgeführte Tests | TODO | — |

## Meilenstein-Gates

| Gate | Tasks | Vorgänger | Review-Schwerpunkt | Status | Beleg/PR |
| --- | --- | --- | --- | --- | --- |
| G0 | T0.1–T0.3 | — | Referenz, vollständige Zuordnung, Grenzen, reproduzierbare Fixtures | BLOCKED | Quellbelege + Fixture vorhanden, CI und echte Referenzscreens offen; kein G1-Start |
| G1 | T1.1–T1.4 | G0 | Design, Back/Insets, keine verlorenen Root-Funktionen | TODO | — |
| G2 | T2.1–T2.7 | G1 | Aktive Suche, lokale Filter, Radius/Pagination/Delta | TODO | — |
| G3 | T3.1–T3.5 | G2 | Gemeinsames Profil/Medien, Archiv/Offline/Intents | TODO | — |
| G4 | T4.1–T4.4 | G3 | Queue, Undo/Skip/Reset, Gesten, Prefetch | TODO | — |
| G5 | T5.1–T5.3 | G4 | Grid, Ansichtswechsel, ein Ergebniszustand | TODO | — |
| G6 | T6.1–T6.6 | G5 | Tabs, Notizen, Historie, Identitätsintegrität | TODO | — |
| G7 | T7.1–T7.4 | G6 | Listen, Status, Sortierung und Watchlist | TODO | — |
| G8 | T8.1–T8.4 | G7 | Gemeinsamer Editor/Grid, Delta, UX-Konsistenz | TODO | — |
| G9 | T9.1–T9.2 | G8 | Komplette Matrix, CI, APK, visuelle/physische Abnahme | TODO | — |

Vor Beginn eines Meilensteins muss das Vorgänger-Gate erfüllt bzw. eine ausdrücklich dokumentierte Freigabe für offene Gerätebelege vorhanden sein; Task-Abhängigkeiten allein ersetzen dieses Gate nicht. Für jedes Gate: alle Tasks/Matrix-IDs prüfen; gezielte Tests und Full-CI pro PR; Diff nach redundanter Implementierung, Folgefehlern, Daten-/State-Konsistenz und Scope prüfen. Kritische/hohe Befunde beheben und erneut testen. Offene Geräteprüfungen bezeichnen; echte Geräteabnahme ist G9-Voraussetzung. Bei externem Blocker sachlichen Teil weiterführen soweit Abhängigkeiten zulassen, nicht auf DONE setzen. Kein automatischer Merge.

## Reviewprotokoll (im PR; hier nur Beleg/Status verlinken)

- Gate/Tasks/Matrix, geprüfter Commit, Zielerreichung (erfüllt/teilweise/fehlt).
- Befunde nach Schwere; konkrete Datei/Test, Ursache und Folgefehler; Korrektur + Re-Test.
- Tatsächlich ausgeführte Tests (Kommando, Lauf/Ergebnis), nicht ausgeführt (Grund).
- Vergleich mit synthetischer Legacy-/Native-Referenz, Gerätefälle/DEVICE_PENDING.
- Scope-Prüfung gegen X01–X11, verbleibende Blocker, nächster ausführbarer Task.
- Nur real geänderte Fachverträge in ARCHITECTURE/SOURCE/MAP etc. aktualisieren. Keine neuen historischen Parallelberichte.

## Agentenstart

Auftrag: „Setze den ersten ausführbaren M05-Task bzw. vereinbarten Teilmeilenstein gemäß AGENTS.md, M05-PLAN.md, M05-PARITY.md und diesem Aufgabenregister um. Prüfe main und Abhängigkeiten, arbeite in neuem Branch/PR, teste, reviewe und aktualisiere Status/Evidenz; kein Merge ohne Freigabe, keine M06/M07-Arbeiten.“ Bei Fortsetzung aus dem Tracker statt der Chat-Historie ableiten, was wirklich noch offen ist.

### G0 – statischer Vertragsreview / offene Abnahmen (18.11.2026)

- Ausgang main `3d210041a35e20acac97a790499a05e1bb39266c`, keine offenen PRs; Legacy `9c514af6ce3659089807fefd4ed5040599975a5a`. P01–P41 verankert. X01–X11 unverändert; keine produktive UI/API-/Datenbankänderung. Die native `FavoritesScreen` liegt in DiscoveryScreen.kt (Matrix korrigiert).
- P05/P10/P11: Legacy `search_runtime.js` hydratisiert nach ready-Preview transient; Legacy Preview kann aus Seite-1-Karten einen Count bilden. Native verbindlich: keine automatische persistierende Suche, aktiver Run getrennt, echte Gesamtzahl oder unbekannt, keine Radiusaufweitung; `SOURCE` 9–15 + `source/network.rs` 1119–1404. T2.1/T2.5 berücksichtigen, keine Rücknahme des Vertrags.
- P13: Legacy Business-Website wird über die explizite Klasse `einzelansicht-url` erkannt (`business_profiles.py`), STUDIO/FACEAPP über Marker in selectors. Native SearchCard bietet KI-Bool, nicht diese Klassifizierungen; `SOURCE` 26 und `saved_search.rs` 503–584. Bestehende unsupported Definition bleibt sichtbar/nicht ausführbar; T2.6 prüft Quell-Evidenz, bis dahin kein Klassifizierer.
- P25: Requested Fixes entfernen neue Likes auch ohne Hide-Likes bis SEARCH_RESULTS; Abweichung zu alleiniger Filterprojektion/Replay. T5.2 muss dieses sichtbare Verhalten bewusst gegen gemeinsame native Projektion prüfen; keine eigenmächtige Änderung der Präferenzregeln. Bei Konflikt Nutzerentscheidung einholen.
- P31: Legacy PersonUserState.private_note getrennt von Encounter-Notiz; `ARCHITECTURE` 8/51, `DATA-IMPORT` 34–46 und `schema.rs` 17–31. T6.5 nur Personennotiz transaktional ergänzen, Treffen nicht anfassen.
- P04/P26: `MAP` 22–25/41: DB-weite Personenauswahl, keine Netzabfrage bei overview, Geocoding ausdrücklich gestartet. In M05 nur Oberfläche/Umfang beschriften; Lifecycle erhalten. Video extern, lokale Medien zuerst (`ARCHITECTURE` 57–58); Diagnose TXT/HAR-Vertrag unverändert (`DIAGNOSTICS` 29–40). Kein Scope-Drift, kein Altcode entfernt, keine neuen App-Abhängigkeiten.
- Generator fügt einen Smoke-Schritt zum vorhandenen CI-Job hinzu; prüft SQLite-Fremdschlüssel, Anzahl und ZIP-Inhalt. Nach Review wird das Archiv im selben CI-Job zusätzlich über den vorhandenen Rust-Importer geprüft (`m05_reference_bundle_import`, explicit opt-in); kein Rendernachweis. Lokale Ausführung/volle CI noch nicht belegt. Browser-Quellcheckout in der Umgebung nicht verfügbar (GitHub DNS nicht auflösbar); kein Android SDK/ADB. Keine Screenshots oder Gerätebelege. Review-Fix: alle sechs Präferenz/Favorit-Kombinationen, vollständige alte/neue Snapshot-Werte sowie tatsächlichen Importtest ergänzt. T0.2: Generator ausführen, Legacy-Adapter und Screens erfassen, Android-APK mit Fixture prüfen. Danach T0.3/G0 erneut reviewen. M05.1 erst nach erfülltem Gate und Integration bzw. ausdrücklicher Freigabe fehlender Gerätebelege.
