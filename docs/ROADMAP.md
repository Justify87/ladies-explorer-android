# Arbeits- und Fortschrittsregister

Einzige operative Quelle für Aufgaben, Reihenfolge, Status und Abnahme. Maßgebliche Verträge: [PRODUCT](PRODUCT.md), [ARCHITECTURE](ARCHITECTURE.md), [DESIGN](DESIGN.md). Arbeitsweise: [AGENTS](../AGENTS.md); Prüfungen: [README](../README.md#prüfung--ci).

## Entwicklungsstand

M07 / G07 (initialer lokaler Produktumfang) und M08 / G08 (native Designkonsolidierung) sind abgeschlossen. M09 / G09 erfasst die am 24.11.2026 gemeldeten Befunde aus praktischer App-Nutzung. M09.1–M09.4 sind abgeschlossen. M09.5 ist nach gesonderter Nutzerfreigabe umgesetzt und zur technischen Prüfung vorgesehen; M09.6 folgt erst nach Integration und eigener Freigabe. L01 bleibt unabhängig vor weiterer öffentlicher Distribution zu klären.

## Geplante Meilensteine

### M09 — Praxiskonsolidierung nach G08

**Freigabe:** Nutzerfeedback vom 24.11.2026 nach praktischer App-Nutzung; Aufnahme aller Anforderungen in das Register beauftragt. Umsetzung und Merge der einzelnen Aufgaben sind gesondert freizugeben. M00–M08/G08 bleiben DONE; L01 bleibt unabhängig.

**Ziel:** Entdecken, Profile, Suche, Einstellungen und Karte auf den tatsächlichen Nutzungsablauf ausrichten: Medienfläche und Informationsdichte erhöhen, relevante Informationen unmittelbar zeigen, sekundäre Informationen progressiv offenlegen, technische Bedienlast entfernen und die drei Entdecken-Modi auf einem gemeinsamen Ergebnis- und Navigationsvertrag konsolidieren.

**Umfang und Vertragsänderungen:** Die folgenden Aufgaben präzisieren ausdrücklich neue Nutzerentscheidungen gegenüber G08. Bei ihrer Umsetzung die jeweils betroffenen Aussagen in [PRODUCT](PRODUCT.md), [ARCHITECTURE](ARCHITECTURE.md) und [DESIGN](DESIGN.md) im selben PR konsistent mit dem resultierenden Code ersetzen; insbesondere DB-weite Karte im Entdecken-Tab, manuelle Standortauflösung, Seitenlimit, Grid-Herz-Aktion, Hero-Overlay, Profilhierarchie und Filterstatus-UI. Unbetroffene Fach-, Datenintegritäts-, Owner-/CAS-, Lifecycle-, Sicherheits- und Diagnoseverträge bleiben gültig. Keine Änderung des Referenzprototyps ohne ausdrücklichen Auftrag. Dark und Light, zugängliche nicht überlappende 48-dp-Interaktionsflächen (sichtbare Elemente dürfen kleiner sein), adaptive Layouts und vorhandene gemeinsame Komponenten erhalten. KISS/YAGNI; keine neuen Parallelpfade, keine Wiedereröffnung abgeschlossener Meilensteine. Abhängigkeiten unten bezeichnen integrierten Stand; Aufgaben seriell nach [AGENTS](../AGENTS.md#arbeitszyklus) ausführen.

#### M09.5 — Gemeinsame progressive Abschnitte und Profilinhalte

**Status:** REVIEW — Branch `feature/m09-5-progressive-sections`; Nutzerfreigabe liegt vor. CI-Gate und Merge ausstehend.

**Abhängigkeit:** M09.4; gesonderter Nutzerauftrag.

- Einheitliches natives Ein-/Ausklappmuster für Profile und Suche schaffen bzw. vorhandenes konsolidieren: abgerundeter Hintergrund, kompakte Überschrift und Indikator, konsistente Abstände; alle Inhalte, Texte, Metadaten, Buttons und Editoren visuell innerhalb ihres zugehörigen Containers. Keine frei zwischen Abschnitten hängenden Inhalte, keine redundanten Controls. Scrollanker beim Öffnen/Schließen erhalten, keine Mutation durch Öffnen.
- „Über das Profil“ als abgerundeten Abschnitt standardmäßig vollständig eingeklappt ohne Textvorschau; „Mehr anzeigen“ öffnet Originaltext vollständig, anschließend einklappbar. Öffnungszeiten, Merkmale und vollständige Dienste ebenfalls ein-/ausklappbar und zunächst geschlossen. Bestehende Analyse-, Notiz-, Begegnungs-, Preislisten-, Verlaufs-/Historienabschnitte einschließlich „Anzeige entdeckt“, verknüpfter Anzeigen und ihrer Aktionen konsistent gestalten; Filterübereinstimmung als bewusst sichtbare Ausnahme in M09.6.
- Dienste fachlich anhand der tatsächlichen Quellstruktur, Parser-/Projektion und Gruppenzuordnung analysieren; Ursache mehrfacher „Service“-Überschriften beheben, zusammengehörige Werte verständlich gruppieren, Typografie und Hierarchie für schnelle Erfassung verbessern. Relevante belegte Angaben und Unsicherheiten nicht verlieren, keine erfundenen Gruppen.
- Redundante leere Zustände und Hinweistexte insbesondere bei manuellen Angebotspreisen entfernen: „Keine Aussage über aktuelle Gültigkeit“, „Zuletzt bekannter bezahlter Gesamtbetrag unbekannt“, „Keine Preislisten gespeichert“ nicht pauschal anzeigen. Fehlende optionale Werte/Abschnitte ohne Erklärtext weglassen; vorhandene historische Beträge, Listen, Editiermöglichkeiten und bei konkreten Daten notwendige Aktualitäts-/Herkunftshinweise erhalten. Tatsächliche Fehler und Konflikte nicht verstecken.
- **Prüfung:** gemeinsame native Abschnittsprobes, geschlossen/geöffnet, Text- und Aktionszuordnung, Anker, Quellgruppen-/Leerzustandsregressionen; bestehende lokale CRUD-, Owner-/CAS-, Entwurfs-, Read-/Merge-/Lifecycle-Verträge.

#### M09.6 — Profil: relevante Filterübereinstimmung

**Status:** PLANNED.

**Abhängigkeit:** M09.5; gesonderter Nutzerauftrag.

- Bisherige „Filterbelege“ fachlich und visuell revidieren; verständliche Bezeichnung (z. B. „Übereinstimmung mit deiner Suche“). Oben im Profil nach Galerie/Kontakten, vor sekundären Detailabschnitten, unmittelbar sichtbar und standardmäßig offen; keine redundante zweite Liste weiter unten. Vollständige Dienste und Merkmale bleiben darunter geschlossen zugänglich.
- Nur tatsächlich wirksame Filter des zum Profil gehörenden ausgeführten Runs bzw. gültigen Suchkontexts (nicht ungespeicherter Entwurf) auswerten. Relevante Eigenschaften, Dienste, Alters-/Größenbereiche, Entfernung und sonstige gewählte Kriterien zusammenführen und konkrete belegte passende Werte hervorheben. Bestehende Filterlogik und Quellbelege verwenden; Ja/Nein/Unbekannt unterscheidbar, Unbekannt nicht als Treffer behandeln.
- Grad der Übereinstimmung schnell erfassbar machen, nur anhand fachlich korrekter Filtersemantik (einschließlich Alternativen innerhalb einer Gruppe); keine künstliche gewichtete Bewertung oder Scheingenauigkeit. Bei fehlendem gültigen Filterkontext keinen erfundenen Match anzeigen.
- **Prüfung:** Rust-/JVM-Regressionen für kombinierte Filter, Alternativen, fehlende/negative Evidenz, Run-/Ownerwechsel und lokale Sichtbarkeit; native Darstellung des kompakten Match-Abschnitts.

#### M09.7 — Suche: kompakter Editor, Filtertext und Abschnittszustand

**Status:** PLANNED.

**Abhängigkeit:** M09.5; gesonderter Nutzerauftrag.

- Gesamten Filtereditor vertikal verdichten: sichtbare Höhe und Innenabstände von Umkreis- und Verfügbarkeitsauswahl, Rubriken, Treffpunkten, Eigenschaften/Figurtyp/Brustgröße und allen weiteren Filterelementen reduzieren, kleinere lesbare Typografie, kompaktes Ortseingabefeld; Touchziele, adaptive Breiten und IME erhalten.
- Oberen sichtbaren Statusblock „Filterentwurf“, „Letzter Suchlauf“ usw. entfernen. Fachliche Trennung Draft/Preview/Run, Zugriff auf letzten gültigen Lauf, Top 5, Fehler/Teilresultat, gespeicherte Suchen und Zustandsverwaltung erhalten.
- Treffpunkte, Rubriken, Eigenschaften und weitere geeignete Gruppen mit demselben Abschnittsmuster wie im Profil (M09.5) darstellen. „Alle Rubriken“, „Weniger anzeigen“ und redundante Gruppenschalter durch einheitliche Ausklappmechanik ersetzen. Auf-/Zuklappzustände beim Verlassen und Wiederöffnen des Suche-Tabs erhalten; keine unnötige globale Persistenzpflicht.
- Filtertext dynamisch auf sämtliche passenden Filterelemente und ihre übergeordneten Gruppen einschränken; nicht passende Gruppen und Einzeloptionen vollständig ausblenden, passende Gruppen temporär öffnen. Fachliche Auswahl auch bei ausgeblendeter Option unverändert halten. Beim Löschen des Texts vorherigen normalen Abschnittszustand wiederherstellen.
- **Prüfung:** JVM-Suche nach Gruppen-/Wertbezeichnungen, Teiltreffer, keine Treffer, Auswahlerhalt und Wiederherstellung; native Dichte-/Touch-/Font-/IME-Probes; gemeinsamer Editor und gespeicherte Definitionen ohne unbeauftragte Änderung.

#### M09.8 — Suche: Aktionsleiste und automatische Pagination

**Status:** PLANNED.

**Abhängigkeit:** M09.7; gesonderter Nutzerauftrag.

- Schwebende Suchaktionsleiste kompakter neu gestalten; Trefferanzahl in Suchbutton integrieren und Quellvorschau, lokalen/letzten Ergebniscount und unbekannten Wert fachlich korrekt unterscheiden. „Lokale Filter werden nach der Suche angewendet“ und „Der letzte Suchlauf bleibt bis zur neuen Suche erhalten“ entfernen. Reset erhalten; „Aktuelle Suche als Standard speichern“ ergänzen, explizit aktuellen gültigen Entwurf einschließlich Filter dauerhaft als Ausgangskonfiguration speichern. Keine implizite Suche, SavedSearch-Änderung oder Quittierung; Reset-/Startverhalten und Speichern prüfen.
- Manuelles Seitenlimit aus der normalen Suche und aus nutzerseitig gespeicherten Suchdefinitionen entfernen (auch nicht in erweiterte Einstellungen verschieben). Historischer Befund: in M03 als vorsorgliche Obergrenze eingeführt ([PR #4](https://github.com/Justify87/ladies-explorer-android/pull/4)); kein belegter Endlos-Paginationsfehler in der untersuchten Android-Historie, vorherige Web-App orientierte sich an Quellseitenzahl.
- Reguläre Suche lädt automatisch alle nachweislich verfügbaren Ergebnisse innerhalb des gewählten Radius. Quell-Pagination, Radiusübergang/Radiusbestätigung, Deduplizierung, Request-Pausen, Cancel/Drain, HTTP-/Parserfehler und Partial-/Vollständigkeitssemantik erhalten. Fehlende/fehlerhafte Seitenzahlen, wiederholte Seiten und unkontrollierte Anfragen absichern; erforderliche interne Sicherheitsobergrenze evidenzbasiert statt willkürlicher Nutzergrenze. Einseitige nicht persistierende Vorschau bewusst erhalten.
- Such-/FFI-/Persistenz-/SavedSearch-Verträge und Altdefinitionen kompatibel bereinigen, vorhandene Android-Daten erhalten; veraltete Limits dürfen die neue reguläre Suche nicht unbemerkt abschneiden. Unvollständige Resultate nie als vollständig ausgeben.
- **Prüfung:** Rust-Transportfixtures für mehrere/mehr als zehn Seiten, Radiusgrenze, unplausible/fehlende Metadaten, wiederholte Seiten, Teilresultat/Cancel, Vorschau; JVM-/Persistenzregressionen für Standard, Reset, Altdefinition, Suchbutton und korrekte Counts.

#### M09.9 — Einstellungen: Hierarchie und globale Ergebnissichtbarkeit

**Status:** PLANNED.

**Abhängigkeit:** M09.3, M09.8; gesonderter Nutzerauftrag.

- Einstellungs-Tab aus Nutzersicht neu strukturieren: kompakte Hauptseite mit wenigen tatsächlichen fachlichen Gruppen, echte navigierbare Untermenüs mit konsistentem Back; keine erfundenen Einstellungsoptionen. Logging, HTTP-Aufzeichnung, Diagnose, technische Status-/Exportfunktionen in eigenes Untermenü „Diagnose und Fehlerbehebung“ auslagern. Gewöhnliche Einstellungen leicht erreichbar; DB-/Core-Status, Replay-Likes, Diagnose-/Capture-/Exportverträge unverändert verfügbar. Keine automatische Aufzeichnung oder Weitergabe.
- Unter Suche zwei unabhängige dauerhaft gespeicherte Optionen „Likes ausblenden“ und „Dislikes ausblenden“ ergänzen. Global wirksam auf vorhandene und künftige Suchergebnisse in Swipe/Grid/Map, ohne neuen Quelllauf; beide aktiv = nur unbewertete Ergebnisse. Keine Löschung oder Präferenzänderung; gespeicherte Personen-/Favoritenansichten nicht ausblenden.
- Bestehende lokale Sichtbarkeitsregeln, gespeicherte Suchdefinitionen, Replay-Likes und globale Optionen ohne redundante Implementierung konsolidieren. Globale Ausblendung als zusätzliche wirksame Sichtbarkeitsbedingung behandeln; keine konkurrierenden Ausblendungspfade. Standardwerte und kompatible Übernahme vorhandener Einstellungen begründet festlegen, nicht still Nutzerdaten umdeuten.
- **Prüfung:** alle vier Toggle-Kombinationen, unmittelbare Reprojektion über alle Modi, Neustart, Undo, Replay/Reset, SavedPeople-/SavedSearch-Verträge, Fehler/Lifecycle; native Untermenü-/Back-/Layoutprobes und Diagnose-Schutzregressionen.

#### M09.10 — Karte: Standortdaten und automatische Aufbereitung

**Status:** PLANNED.

**Abhängigkeit:** M09.1; gesonderter Nutzerauftrag.

- Lang laufenden, fehlschlagenden bisherigen „Standort auflösen“-Vorgang ursächlich untersuchen (Quelle, Cache, Provider, Fehler/Timeout, Lease und Persistenz); Befund belegen, nicht lediglich UI entfernen.
- Standortinformationen bei regulärer Datenerfassung vorgelagert und einheitlich der jeweiligen Anzeige/ihrem belegten Standort in SQLite zuordnen. Vorhandene Koordinaten, Herkunft und Genauigkeit wiederverwenden; bestehende Datensätze bedarfsgesteuert automatisch nachpflegen, migrationssicher ohne Datenverlust. Kein erneutes Auflösen bereits gültiger Standorte, keine Kartenoberfläche als Beschaffungspflicht.
- Abstufung nach bestverfügbarer belegter Genauigkeit: verlässlich bekannte konkrete Position/zulässig vorhandene Adresse, ansonsten PLZ-/Stadtteilbereich, ansonsten Stadt. Annäherungen und Genauigkeit persistieren und visuell von konkreter Position unterscheiden; keine erfundenen Adressen/Ersatzkoordinaten oder Präzision bei unbekanntem Ort. Ohne verlässlichen Ort kein Marker.
- Manuelle Auflösung durch automatische, begrenzte, abbrechbare und nicht blockierende Aufbereitung ersetzen. Rust-Core als zuständige Schicht, bestehende Cache-/Negativcache-, Provider-Pausen-, Lease-/Cancel-, Fehler-/Offline-Verträge bewahren bzw. zielgerichtet anpassen. Providerbedingungen und Eignung für automatische Auflösung prüfen; kein unkontrolliertes Bulk-Geocoding. Keine privaten oder versteckten Adressen bzw. Profilinformationen an externe Geocoder, kein GPS, Routing oder versteckter Standort. Konkrete Position nur bei zulässiger, tatsächlich belegter Datengrundlage; falls dafür eine Änderung einer verbindlichen Datenschutz-/Providergrenze erforderlich wäre, diese nicht eigenmächtig vornehmen, stattdessen zulässige grobe Stufe verwenden und Blocker benennen.
- **Prüfung:** synthetische Rust-/SQLite-Fixtures für Genauigkeit, Cache-Hit, Backfill, Quellen-/Owneränderung, Migration, Provider-/Transportfehler, Cancel/Lease/Rate-Limit und Offline; keine realen Profil-/Adressdaten in Tests, keine neuen Providerzugriffe ohne belegte Eignung.

#### M09.11 — Karte: reaktive Darstellung, Cluster und maximale Fläche

**Status:** PLANNED.

**Abhängigkeit:** M09.1, M09.9, M09.10; gesonderter Nutzerauftrag.

- Ursache der leeren Karte über SQLite → Rust → UniFFI/Kotlin → GeoJSON/MapLibre-Style/Source/Layer untersuchen und beheben. Karte beim Eintritt automatisch mit geografisch zuordenbaren Profilen des gemeinsamen Suchergebnisses befüllen; kein manueller Reload, kein erneuter Quelllauf. Neue Runs, Standortauflösung, Daten-/Bewertungsänderungen und globale Filter reaktiv übernehmen; lokale Daten und Fehler-/Offline-Fallback bleiben zugänglich.
- Cluster in niedrigen Zoomstufen sichtbar, beim Hineinzoomen schrittweise in kleinere Cluster und einzelne Marker auflösen; gleiche Koordinaten mit mehreren Profilen zugänglich auswählen. Genauigkeitsstufen sichtbar unterscheidbar ohne fiktive Präzision. Auswahl öffnet reguläres Swipe-Profil gemäß M09.1; Back erhält Kamera, Zoom, Ausrichtung und Auswahl.
- „Standort auflösen“, „Aktualisieren“, Postleitzahlzentren-/Adress-Erläuterung, Statistik (z. B. 214 von 218, vier ohne Position) und „Ungefähre Ortslagen unabhängig von der Suche“ entfernen. Überflüssige Container/Abstände beseitigen; Karte maximal zwischen gemeinsamem Modusumschalter und unterer Root-Navigation ausdehnen. MapLibre-Logo unten links entfernen, soweit technisch und lizenzrechtlich zulässig; erforderliche Attribution/Quellenangaben erhalten.
- **Prüfung:** JVM-/native synthetische Cluster-/Marker-/Gleichort-/Style-/Lifecycle-Probes, aktualisierte Mitgliedschaft und Standortgenauigkeit, Back/Kamera, Filter/Undo, Offline-/Fehlerfall, sichtbare Fläche und Attribution; tatsächliche Provider-/Geräteausführung nur bei belegtem Test behaupten.

#### M09.12 / G09 — Gesamtintegration und Abschluss

**Status:** PLANNED.

**Abhängigkeit:** M09.1–M09.11 integriert; gesonderter Nutzerauftrag.

- Alle Kriterien M09.1–M09.11 auf integriertem Stand abgleichen; insbesondere gemeinsamer Run → Grid/Map → Swipe → Back, persistente globale Sichtbarkeit, Standard/Reset/Preview/Pagination, Filterübereinstimmung, Ortsdaten → automatische Karte → Cluster, gemeinsame progressive Abschnitte, Kontakte und Diagnose-Untermenü.
- Betroffene Aufrufer, States, FFI, Persistenz, Tests und Dokumentation auf Abweichungen, Altpfade, unnötige Abstraktionen und widersprüchliche Verträge prüfen. Belegte In-Scope-Befunde ursächlich beheben; keine unabhängigen Erweiterungen. Dark/Light, kleine/kurze Fenster, Font Scale, Touch-/Fokus, Fehler/Cancel/Lifecycle und Datenintegrität risikogerecht prüfen.
- Vollständiges vorgesehenes CI-Gate und konkrete PR-/CI-/Commit-Belege; Geräteprüfungen nach [Merge-Regel](#pflege). Kein tatsächlicher Gerätetest, reale Quellen-/Providerverfügbarkeit oder gemessene Verbesserung ohne Beleg behaupten. Abschluss und Entwicklungsstand anhand der integrierten Kriterien aktualisieren.

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
| M06.1a | Begegnungsbasis: native personengebundene CRUD mit Owner-/Editor-CAS, gemeinsamer bedarfsgesteuerter Profilabschnitt und Editor für Swipe/Standalone, Entwurfs- und Commit-/Readfehler-Schutz; Rust-/JVM-Regressionen und Instrumentierungs-APK validiert | [#87](https://github.com/Justify87/ladies-explorer-android/pull/87), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45443347192), [geprüfter Commit](https://github.com/Justify87/ladies-explorer-android/commit/88f53965226683079a8eb57ddb59b9d4180b429d) |
| M06.1b | Optionaler Grundpreis und persönliche Leistungszeilen im gemeinsamen Begegnungseditor; atomare CRUD, vollständiger Editor-CAS, Rollback, Cascade und Merge-Erhalt; unbekannte Beträge getrennt vom Gesamtpreis; Rust-/JVM-Regressionen und Instrumentierungs-APK validiert | [#88](https://github.com/Justify87/ladies-explorer-android/pull/88), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45450763583), [geprüfter Commit](https://github.com/Justify87/ladies-explorer-android/commit/f574ea0cbe0a8d91561059a45fc1b4fbf630407f) |
| M06.2 | Manuelle personenbezogene EUR-Preislisten mit Datum und Positionen, historische Anzeige, atomare native CRUD mit Owner-/Editor-CAS und Merge-Erhalt, gemeinsamer Editor für beide Profilkontexte, separat abgeleiteter letzter bekannter bezahlter Gesamtbetrag; Rust-/JVM-Regressionen und Instrumentierungs-APK validiert | [#89](https://github.com/Justify87/ladies-explorer-android/pull/89), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45454318406), [Merge](https://github.com/Justify87/ladies-explorer-android/commit/10f850e263e6a64e5995b56aa5a1b191b29bc447) |
| M06.4 / G06 | Integrations- und Aufruferreview der gemeinsamen lokalen Begegnungs-/Preisfunktionen; gleichdatierte Preislisten nach Erstellungsreihenfolge statt zufälliger ID sortiert, Korrektur und bezahlter Gesamtbetrag regressionsgesichert. Historisch identische Erstellungszeitstempel nur deterministisch per ID auflösbar; M06.3 gemäß Nutzerentscheidung 24.11.2026 ersatzlos entfallen (globale Statistik/Verlauf, Medienbrowser, zusätzliche Karten-/Geocoding-Funktionen; keine Verschiebung nach M07). Vollständiges CI-Gate grün, Geräteprüfung gemäß Merge-Regel. | [#90](https://github.com/Justify87/ladies-explorer-android/pull/90), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45455748491), [geprüfter Commit](https://github.com/Justify87/ladies-explorer-android/commit/96dc1e7ab09323895e2afd928e66664f04ab58b4), [Architektur](ARCHITECTURE.md#gemeinsame-profilarchitektur) |
| M07.1 | Vier funktionsübergreifende Integrationsketten auf G06 geprüft; Preisübersicht nach Begegnungs-Commit explizit invalidiert, bestätigten Identitäts-Merge über Lifecycle-Stop hinweg konsistent gehalten; gezielte JVM-/native Merge-Regressionen, vollständiges CI-Gate einschließlich Instrumentierungs-APK erfolgreich. Geräteprüfung gemäß Merge-Regel; keine tatsächliche Geräteausführung behauptet. | [#91](https://github.com/Justify87/ladies-explorer-android/pull/91), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45459671704), [geprüfter Commit](https://github.com/Justify87/ladies-explorer-android/commit/d0274466a5836893cef338d7c8d3b857fc474c7c) |
| M07.3 / G07 | Lokale Nutzungsreife und technischer Projektabschluss auf integriertem Stand; vorhandene G10/G11/G06/M07.1-Funktionsbelege, gezieltes Start-/DB- und Vertragsreview ohne weiteren konkreten abnahmeverhindernden Befund. Vollständiges CI-Gate für unveränderten Code-/Buildstand einschließlich ARM64-Debug-/Instrumentierungs-APK, Rust/JVM/Lint, Architekturverträge und synthetischer DB-Integritäts-/Fremdschlüsselprüfung erfolgreich. Geräteprüfung gemäß Merge-Regel; kein tatsächlicher Gerätetest, APK-Download, reale Quellenverfügbarkeit oder Release-Qualifikation behauptet. M07.2 ersatzlos entfallen; TXT/HAR-Speichern und -Teilen vorhanden, kein zusätzlicher Bedarf. | [#92](https://github.com/Justify87/ladies-explorer-android/pull/92), [vollständige CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45459671704), [geprüfter Commit](https://github.com/Justify87/ladies-explorer-android/commit/d0274466a5836893cef338d7c8d3b857fc474c7c), [Vergleich zum integrierten Stand](https://github.com/Justify87/ladies-explorer-android/compare/d0274466a5836893cef338d7c8d3b857fc474c7c...6fbc7cd27b256fbeed94499ce7a36283002b428a), [Dokumentations-CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45461239094) |
| M08.0 | Orange-Dark-Designvertrag einschließlich Light-Mode-Pflicht, offline nutzbare synthetische Designreferenz v3, vorhandene Fach-/UX-Verträge und native Hero-Messgrenzen erhalten. | [#93](https://github.com/Justify87/ladies-explorer-android/pull/93), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45474871021) |
| M08.1 | Gemeinsame Orange-/Light-Material-3-Rollen, Shell, Navigation, Insets, 48-dp-Ziele und Fokus; statische Kontrastprüfung in beiden Modi und native Test-APK. | [#95](https://github.com/Justify87/ladies-explorer-android/pull/95), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45477736142) |
| M08.2 | Swipe/Grid, gemeinsame Kontext-/Moduswahl, adaptiver Hero und ikonische Rail, gemeinsame Grid-Karten; Owner-, Gesten-, Undo- und Medienverträge erhalten. | [#96](https://github.com/Justify87/ladies-explorer-android/pull/96), [CI #740](https://github.com/Justify87/ladies-explorer-android/actions/runs/45480963962), [CI #741](https://github.com/Justify87/ladies-explorer-android/actions/runs/45481647144) |
| M08.3 | MapLibre mit DB-weitem Bestand, semantischen Markerfarben, adaptiver Detailfläche und lokaler zugänglicher Profilauswahl auch bei Stilfehler. | [#97](https://github.com/Justify87/ladies-explorer-android/pull/97), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45483613275) |
| M08.4 | Gemeinsame Profilsektionen und lokale Begegnungs-/Preislisteneditoren mit responsiven Fakten, Fehleranzeige und umbrechenden Aktionen; Owner-/CAS-/Read-/Merge-/Lifecycle-Verträge erhalten. | [#98](https://github.com/Justify87/ladies-explorer-android/pull/98), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45485255405) |
| M08.5 | Gemeinsame Filter-/Settings-Gruppen, adaptive Suchfooter und gespeicherte Ansichten; Draft/Preview/Run, revisionsgebundenes Ack, Watchlist, Diagnose und Replay-Likes-Admission erhalten. | [#99](https://github.com/Justify87/ladies-explorer-android/pull/99), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45488135597) |
| M08.6 / G08 | Gesamtintegration und Abschluss der nativen Designkonsolidierung: Grid-Kontext/Modus bei kurzen Viewports scrollbar, vollständiger Suchstatus, adaptive Karten mit getrenntem Like-Ziel, gleich hohe Modusziele und Rail-Farben aus aktivem Theme. 540 Budgetkombinationen statisch ohne Abweichung und JVM-Regression; 24 synthetische native Theme-/Breiten-/Schriftprobes sowie Grid-/Status-/Like-Bounds in der Instrumentierungs-APK. Diff-/Aufruferreview, bestehende Fach-/Architekturverträge unverändert; vollständiges CI-Gate #765 erfolgreich. Geräteprüfaufgaben gemäß Merge-Regel; keine tatsächliche Geräteausführung, TalkBack-/DPAD-, IME-/Rotationsprüfung oder native Hero-Messung behauptet. | [#100](https://github.com/Justify87/ladies-explorer-android/pull/100), [CI #765](https://github.com/Justify87/ladies-explorer-android/actions/runs/45490335157), [geprüfter Commit](https://github.com/Justify87/ladies-explorer-android/commit/bc907ca8d60900e3c62ce88ed54ab19bf2b7e67c), [detaillierte M08-Belege vor Abschluss](https://github.com/Justify87/ladies-explorer-android/blob/bc907ca8d60900e3c62ce88ed54ab19bf2b7e67c/docs/ROADMAP.md) |
| M09.1 | DONE — Gemeinsame ausgeführte bzw. lokale Entdecken-Mitgliedschaft für Swipe, Grid und Karte; native Kartenprojektion nach Listing und Run statt DB-weitem Bestand, regulärer Swipe-Einstieg mit Rückkehr zum Ursprung und gültigem Grid-/Kartenanker. Owner-/Run-Admission, Undo und Standalone-Kontexte erhalten; Produkt-, Architektur- und Designverträge angepasst. JVM-/Rust-Regressionen, APK- und Instrumentierungs-APK-Build, Lint und vollständiges CI-Gate erfolgreich; Geräteprüfaufgaben gemäß Merge-Regel, keine tatsächliche Geräteausführung behauptet. | [#102](https://github.com/Justify87/ladies-explorer-android/pull/102), [CI](https://github.com/Justify87/ladies-explorer-android/actions/runs/45507864274), [geprüfter Commit](https://github.com/Justify87/ladies-explorer-android/commit/b0684712ac61e4745bfe85c1a07b0a978b8dd672) |
| M09.2 | DONE — Kompakter Entdecken-Header mit Schnellwahl in allen Modi, Statistikzeile und Swipe-Sammelmenü entfernt; kompakter Modusumschalter, größerer proportionserhaltender Hero ohne unteres Textoverlay, unabhängiges Favoritenziel, weiterhin zugängliche Profil-/Deckaktionen. Run-/Owner-/Cancel-Verträge erhalten, Produkt-, Architektur- und Designverträge angepasst. JVM-/Rust-Regressionen, Architekturprüfungen, Lint und ARM64-Debug-/Instrumentierungs-APK erfolgreich; vollständiges CI-Gate #783 nach Korrektur des Restore-Testaufrufers grün. Geräteprüfaufgaben gemäß Merge-Regel; keine tatsächliche Geräteausführung behauptet. | [#103](https://github.com/Justify87/ladies-explorer-android/pull/103), [CI #783](https://github.com/Justify87/ladies-explorer-android/actions/runs/45510527932), [geprüfter Commit](https://github.com/Justify87/ladies-explorer-android/commit/07db222118b12e97970344a2b27c065b7b2016f2) |
| M09.3 | DONE — Bilddominierte Entdecken-Kacheln mit belegtem Alter und dreistufigem, nicht interaktivem Herzstatus; horizontale Like-/Dislike-Gesten, zugängliche Präferenzaktionen und kontextgebundenes Undo über native Owner-/Präferenz-CAS ohne Deck-Visit. Exklusives Grid-Hide entfernt; gemeinsame Mitgliedschaft und Projektionsverträge erhalten, Produkt-, Architektur- und Designvertrag angepasst. Gezielte JVM-Regressionen, Rust-/Architekturprüfungen, Lint und ARM64-Debug-/Instrumentierungs-APK erfolgreich; vollständiges CI-Gate #786 grün. Geräteprüfaufgaben gemäß Merge-Regel; keine tatsächliche Geräteausführung behauptet. | [#104](https://github.com/Justify87/ladies-explorer-android/pull/104), [CI #786](https://github.com/Justify87/ladies-explorer-android/actions/runs/45511663777), [geprüfter Commit](https://github.com/Justify87/ladies-explorer-android/commit/1fe266e715e70f58cdbb97ea9b6c4ffa91d89069) |
| M09.4 | DONE — Gemeinsamer Profilfluss mit akzentuiertem Anzeigentitel, belegtem Alter/Ort/Status und validierten Telefon-/WhatsApp-Aktionen direkt nach der Galerie; redundante Metadaten-Pills und Overlay entfernt. Unbeschränkten `data-ort`-Fallback im Rust-Parser korrigiert, aktive Listing-/Owner-/Snapshot-Zuordnung geprüft, historische Rohwerte erhalten. Konkreter Offenbach-Fall ohne Originaldaten nicht reproduziert; kein Orts-Hardcoding oder Datenmigration. Produkt-, Architektur- und Designverträge aktualisiert. Rust-/JVM-Regressionen, Architekturprüfungen, Lint, ARM64-Debug-/Instrumentierungs-APK und vollständiges CI-Gate #790 nach Korrektur des Galerie-Testaufrufers erfolgreich. Geräteprüfaufgaben gemäß Merge-Regel; keine tatsächliche Geräteausführung behauptet. | [#105](https://github.com/Justify87/ladies-explorer-android/pull/105), [CI #790](https://github.com/Justify87/ladies-explorer-android/actions/runs/45513823907), [geprüfter Commit](https://github.com/Justify87/ladies-explorer-android/commit/708164357bf4be741be266fb8d56f4b05abf7663) |
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
