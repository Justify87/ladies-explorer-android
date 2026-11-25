# Produkt und Funktionen

Maßgeblicher fachlicher Soll-Vertrag der eigenständigen lokalen Android-App. Technik: [ARCHITECTURE](ARCHITECTURE.md); UI/UX: [DESIGN](DESIGN.md); offene Umsetzung: [ROADMAP](ROADMAP.md). Abweichungen vom Soll sind keine stillschweigenden Produktentscheidungen.

## Produktziel und Grenzen

**Zweck:** Auf einem Gerät Profile, Suchergebnisse, Personen, Medien, persönliche Entscheidungen und Historie verwalten.

**Nicht Bestandteil:**
- Separates Backend oder Synchronisierungsdienst; integrierter Messenger oder automatische Nachrichten/Anrufe.
- KI-Bildanalyse, automatische OCR-/KI-Preiserfassung oder erfundene Daten.

**Verbindliche Nutzerentscheidung vom 20.11.2026:** Die frühere Web-App ist obsolet.

- Keine Datenübernahme aus der Web-App und keine nutzerseitige Datenmigration – unabhängig von Format oder Verfahren (SQL, ZIP, Dateien, Gerätewechsel, Backup/Restore).
- Keinen Nutzerimport, keine Ersatzmigration und kein Backup-/Restore-Verfahren zur Datenmigration vorhalten oder entwickeln.
- Daten entstehen nativ in der Android-App. Interne, integritätserhaltende Schemaentwicklung derselben Android-Datenbank ist **keine** Nutzerdatenmigration. Diagnoseexporte sind kein Datenübernahmeverfahren.

## Suche und Filter

### Parameter und Top 5

- Suchparameter: Ort, Rubrik, Radius 5/10/20/30/50/70/100 km, Alters-/Größenbereich, Treffpunkt, Kategorien, Merkmale, Services und Seitenlimit 1–50; Werte gemäß belegtem Quellkatalog.
- Alle/Jetzt/Heute werden kanonisch abgebildet. Nur die Schnellwahl im Entdecken-Header führt in allen drei Modi unmittelbar eine Suche aus. Bei aktivem Run verwendet sie dessen ausgeführte Query; ohne aktiven Run den aktuellen Entwurf.
- Top 5: tatsächlich abgeschlossene Suchläufe zählen, pro Lauf dedupliziert und stabil sortiert; Rubrik zählt mit. Vorschau und bloße Auswahl zählen nicht, definierte Standardwerte ausgenommen.

### Aktive Suche, Vorschau und Vollständigkeit

- Aktiven Suchlauf und Filterentwurf trennen. Der Filtertext schränkt ausschließlich die sichtbaren Filterelemente ein; Gruppenbezeichnungen öffnen passende Gruppen, Wertbezeichnungen zeigen nur passende Optionen. Ausgeblendete Auswahlen bleiben unverändert; der Filtertext führt weder eine Suche aus noch ändert er gespeicherte Definitionen.
- Vorschau ist abbrechbar und leichtgewichtig; sie speichert weder Suchläufe noch Inserate, Beobachtungen oder Medien.
- Quellzahl und lokal sichtbare Treffer unterscheiden. Vorschau-Count nur bei bestätigtem Radius und belastbarer Quellzahl (mindestens die Zahl erkannter Karten) zeigen; andernfalls **unbekannt**, nicht null – auch bei unbestätigtem 404.
- Erfolgreiche Suche öffnet Entdecken. Fehler oder Abbruch erhalten den letzten aktiven Lauf.
- Radius niemals automatisch aufweiten; unbekannte Quellstruktur ist kein Nulltreffer.
- Explizite Radiusabweichung auf Seite 1 ist ein Fehler. Tritt sie später auf, können bestätigte Karten als Teilresultat bleiben.
- Fehlende Radiusbestätigung oder unvollständige Pagination ist ein Teilresultat, keine bestätigte Entfernung oder vollständige Ergebnismenge.
- Erster HTTP-404-Sonderfall ist leer, aber nicht radiusbestätigt; kein bestätigter Nulltreffer für Deltas.
- Reguläre Teilresultate dürfen gespeichert werden; Abbruch und Fehler ohne gültiges Teilresultat sind **kein** Sucherfolg.

### Lokale Filter

- Unterstützt: nur Fotos/Video, Likes/Dislikes ausblenden sowie ALL/AI/NATURAL **nur** auf vorhandenen Markern. AI bedeutet „KI-markiert“, die bestehende Definition NATURAL bedeutet „Ohne KI-Markierung“, nicht nachgewiesene Natürlichkeit. Ohne Marker bleibt die tatsächliche Bildherkunft unbekannt; gespeicherte Definitionen und Sichtbarkeitsregeln bleiben unverändert.
- STUDIO/FACEAPP/Business sind ohne belastbare Daten nicht unterstützt; keine URL-Heuristik.
- Replay-Likes und Hide-Likes unterscheiden. Normale und gespeicherte Suche verwenden identische Sichtbarkeitsregeln.

## Entdecken und Entscheidungen

- Swipe, Grid und Karte sind drei Ansichten **derselben ausgeführten Ergebnismenge** mit gemeinsamen Filtern, Listing-/Personenidentitäten, Präferenzen und Favoriten. Den neutralen, sichtbar lokalen Startbestand nicht mit einem aktiven Lauf vermischen; auch er besitzt eine gemeinsame Mitgliedschaft unabhängig vom Swipe-Fortschritt.
- Eindeutige Inserate und stabile Reihenfolge; Moduswechsel startet keine neue Suche und erhält die gültige Position.
- Like/Dislike und Favorit sind unabhängig. Der Swipe-Hero bietet die Favoritenaktion oben rechts, die Präferenz über Rail oder Geste; erst die bestätigte Entscheidung erlaubt Weitergehen. Identität und Profilangaben stehen außerhalb des Bildes.
- Später bleibt sessionlokal.
- Undo stellt den vorherigen **vollständigen** Präferenzzustand und den gültigen Deckschritt wieder her; Präsentationswiederherstellung siehe [DESIGN](DESIGN.md#gesten-und-präsentation).
- Reset/Replay starten Traversierung neu, ohne persistierte Entscheidungen zu löschen.
- Standalone-Präferenz, Neutral-Rücksetzung und Favorit erzeugen keine Deck-Visits.
- Grid erlaubt Like/Dislike ohne Profilöffnung durch horizontale Geste und zugängliche nicht-gestische Aktionen einschließlich Neutral-Rücksetzung. Die bestätigte Präferenz wird gemeinsam projiziert; kein exklusives Grid-Ausblenden. Der letzte gültige Grid-Commit ist innerhalb des aktuellen Grid-Kontexts per erwartungsgeprüftem Undo rückgängig zu machen, ohne Deck-Visit. Bei Writefehler bleibt der fachliche Zustand unverändert; bei Readfehler gilt der bestehende Recovery-Vertrag.
- Technische Kontext- und Commit-Sicherheit: [ARCHITECTURE](ARCHITECTURE.md#operations--und-konsistenzmodell).

## Profile, Medien und Kontakte

- Swipe und Standalone zeigen den gemeinsamen Profilumfang: Identität, Name/Alter/Ort, belegte Verfügbarkeit/Status, Öffnungszeiten, Merkmale, Beschreibung, Dienste, belegte Filterübereinstimmung (Ja/Nein/Unbekannt), Präferenz/Favorit, Medien, Kontakte, Analyse, Notizen, Verlauf, Snapshotvergleich und verknüpfte Anzeigen.
- Alle tatsächlich vorhandenen Informationen bleiben erreichbar; fehlende optionale Angaben nicht erfinden oder als leere Erklärung wiederholen. Quellgruppen und ihre Werte bleiben zusammen.
- Der gemeinsame Profilabschnitt zur Filterübereinstimmung verwendet ausschließlich den beim Öffnen gültigen ausgeführten Run oder den zugehörigen gültigen gespeicherten Ergebnisbestand, niemals den Entwurf oder einen zufällig anderen Run. Ohne gültige Mitgliedschaft und Owner-/Snapshot-Zuordnung keine Match-Anzeige. Belegte passende, nicht passende und unbekannte Werte bleiben unterscheidbar; Alternativen derselben Filtergruppe bilden ein Kriterium, unbekannte Evidenz zählt nicht als Treffer. Eingeschränkte Alters-/Größenbereiche und wirksame lokale Sichtbarkeitsregeln zählen wie die ausgewählten Quellfilter. Der Suchumkreis ist keine belegte Einzelentfernung; ohne solche Evidenz bleibt die Entfernung unbekannt. Kein künstlicher Score.
- Galerie in Quellreihenfolge. Lokale archivierte Medien zuerst, dann Remote-Fallback; bei Fehler Platzhalter.
- Reine Anzeige verändert weder Archiv noch Datenbestand. Eingebettete Videos und Darstellungsmodi nach [DESIGN](DESIGN.md#profile-und-medien).
- Verknüpfte Anzeigen verwenden externe Original-URLs. Telefon und WhatsApp sind unmittelbar nach der Galerie nur bei gültiger, dem sichtbaren Profil zugeordneter Telefonnummer zugänglich. Telefon, WhatsApp und Originalprofil nur durch ausdrückliche Nutzerhandlung; keine automatischen Aktionen.
- Der Detailort stammt aus Stadt und gegebenenfalls PLZ des aktiven Listing-Snapshots. Versteckte Orts-Rohwerte bleiben technische Evidenz, keine zusätzliche sichtbare Ortsangabe. Fehlende Angaben nicht durch andere Personen, Inserate oder unbelegte Ortswerte ersetzen.
- Neu/bekannt, Video und Aktivität nur anhand von Evidenz. Vergleich ausdrücklich auslösen; keine künstlichen Scores oder Datenfelder für Mockups.

## Personen, Identität und Historie

- Eine Person kann mehrere Quellprofile und Inserate besitzen. Präferenz, Favorit und Notiz gehören der **Person**; Quellenbeobachtungen und Nutzerzustand bleiben getrennt.
- Historie zeigt reale Übergänge; identische Inhalte erzeugen keine künstlichen Änderungen.
- Identitätskandidaten basieren auf vorhandener Evidenz. Kein automatisches Zusammenführen, kein freier manueller Merge/Split.
- Explizite Merge-Bestätigung in **beiden** Profilkontexten zeigt die Konsequenzen.

**Merge blockieren:**
- Fehlende Evidenz oder manuell getrennte Inserate.
- Widersprüchliche explizite LIKE-/DISLIKE- oder Wiederholungsinteresse-Entscheidungen: vor einem neuen Merge-Versuch ausdrücklich auflösen.
- Ungespeicherte Notizen betroffener Personen.

**Nach bestätigtem Merge:**
- Daten und historische Zuordnungen erhalten. Aktuelles Listing möglichst halten, andernfalls in gültigen Kontext mit Hinweis wechseln; kein Undo-Step.
- Personennotiz bleibt getrennt von Begegnungsnotiz. Notizkonflikte ausdrücklich lösen; keine stillen Überschreibungen oder Umdeutung von Entwürfen.

## Gespeicherte Personen

- Favoriten/Likes/Dislikes mit korrekter Personenzuordnung, Counts und Datum-/Namenssortierung.
- Watchlist „Neu & geändert“: 36 h, zuerst drei Einträge, ausklappbar und ohne Duplikate.
- Explizite Einzel-/Batchstatusprüfung zeigt Fortschritt, Abbruch und Teilfehler; sie ist nur bei **weiterhin gültiger gespeicherter LADIES-Mitgliedschaft** zulässig.

| Quellenbeobachtung | Fachlicher Status / Wirkung |
| --- | --- |
| Gültige Suchkarte | Bestätigt `ACTIVE` |
| Erkannte 200-Detailseite mit erwarteter ID | Bestätigt `ACTIVE` |
| Direkte 404/410-Antwort auf demselben Profilpfad | Bestätigt `UNAVAILABLE` |
| Unterstützte Detailseite ohne ID | Kann Inhalte liefern, Status bleibt `UNKNOWN`; explizite Prüfung zählt als Fehler |
| Falsche ID/Pfad, Prüfseite, Timeout oder Transportfehler | **Kein** Beweis für Inaktivität und kein bestätigter `UNAVAILABLE`-Wechsel |

- Nur bestätigte Quellenbeobachtungen ändern Status; Statuszeit ist die letzte bestätigte Quellenbeobachtung. Profil und gespeicherte Personen zeigen nicht belegte Statuswerte (auch historische INACTIVE/REMOVED) als unbekannt statt als bestätigte Nichtverfügbarkeit.
- Status-only ändert weder Inhalts-/Personenreihenfolge noch archivierte Medien.
- Abbruch erhält bereits bestätigte Teilresultate.

## Gespeicherte Suchen

### Definition und Bearbeitung

- Definitionen erstellen, bearbeiten, laden, ausführen, umbenennen und löschen; gemeinsamer Filter/Editor.
- Unterstützte lokale Filter bleiben bei Änderung **nur** der Quellenparameter sichtbar und unverändert, sofern nicht ausdrücklich bearbeitet.
- Umbenennen erhält Ergebnisbasis und Historie; inhaltliche Änderung setzt die Basis zurück.
- Nicht unterstützte Definitionen sichtbar kennzeichnen. Ausführung und inhaltliche Bearbeitung sind gesperrt, Definition niemals stillschweigend ändern.
- Vorhandene Ergebnisse öffnen, umbenennen, löschen und ausdrücklich quittieren bleiben möglich.

### Ergebnisbasis und Mitgliedschaft

- Der erste **vollständige, radiusbestätigte** Lauf bildet die Basis ohne künstliche Neu-Treffer.
- Nur spätere vollständige bestätigte Läufe verändern die Mitgliedschaft. Teilresultate und unbestätigte 404 dürfen keine falschen Entfernungen auslösen.
- Ergebnisse nach stabiler Inserat-ID, offline und paginiert; kein 1.000-Treffer-Limit.

### Neuigkeiten, Revision und Fehler

- Neuigkeiten werden **nur durch ausdrückliche Quittierung** als gelesen markiert; Öffnen, Reload, Pagination, Rückkehr oder andere Mutationen quittieren nichts.
- Keine veralteten Deltas, Fehlermeldungen oder Aktionen gegen eine inzwischen geänderte Definition.
- Version, Definitionsbytes, Commit-, Fehler- und Ack-Grenzen: [ARCHITECTURE](ARCHITECTURE.md#gespeicherte-suchen).

## Karte

- Kartenmarker ausschließlich für geografisch zuordenbare Listings der gemeinsamen Entdecken-Mitgliedschaft; kein separater DB-weiter Bestand oder Karten-Favoritenfilter. Öffnen eines Kartenprofils oder einer Grid-Kachel startet das exakt ausgewählte Listing im regulären Swipe-Deck; Android-Back erhält den Ursprung einschließlich gültigem Scrollanker bzw. Kamera und Auswahl. Standalone-Profile außerhalb Entdecken bleiben unverändert.
- Ungefähre deutsche PLZ-Zentren, ansonsten Stadtzentren. Keine Ersatzkoordinaten; GPS/Gerätestandort, exakte Adressen oder andere private Profilinformationen niemals an Geocoder geben.
- Marker sind keine Treffpunkte/Navigationsziele. Nicht auflösbare Orte bleiben ohne Marker; gleich positionierte Profile lassen sich gemeinsam auswählen.
- Standortauflösung nur ausdrücklich, höchstens 15 unterschiedliche Schlüssel pro Lauf und keine konkurrierenden Läufe. Abbruch nach laufender Anfrage.
- Vorübergehend fehlgeschlagene Schlüssel hinter unversuchte stellen. Teilergebnisse und Koordinaten sind offline verfügbar; Kacheln cacheabhängig.
- Ausgeschlossen: automatisches Geocoding, manuelle Marker, Routing, Treffpunkte und Offline-Kachelpakete.

## Einstellungen und lokale Diagnose

- DB-/Core-Status, Replay-Likes und lokale Diagnose; keine Telemetrie.
- INFO und optional DEBUG, **kein TRACE**. Reale Operations-/Netzwerk-/Verarbeitungszeiten, sichtbare Fehler und TXT-Export.
- HTTP-Aufzeichnung ausschließlich bewusst: **Aus** (Standard nach jedem Neustart), Metadaten oder zusätzlich geeignete Textantworten.
- Eine begrenzte Sitzung bleibt erhalten; Löschen entfernt sie und schaltet Aufnahme aus. HAR 1.2 ist speicher-/teilbar mit realen Werten und erkennbaren Auslassungen/Fehlern, ohne erfundene Einzelzeiten.
- Keine Medienbodies, zusätzlichen Requests, Replay, Mitschnitt fremder Apps, Proxy oder MapLibre-Kacheln.
- Vertrauliche Header und Parameter maskieren. Übrige Inhalte können personenbezogen sein: nur bewusster Export, keine automatische Weitergabe.
- Technische Grenzen: [ARCHITECTURE](ARCHITECTURE.md#diagnoseinfrastruktur).

## Weitere Produktgrenzen

### Persönliche Begegnungen und Preise

Bestätigtes fachliches Soll für M06; Umsetzung und Status ausschließlich in [ROADMAP](ROADMAP.md#abgeschlossene-arbeiten).

- Begegnungen gehören einer Person; ein optionales Inserat muss ihr zugeordnet sein. Zeitpunkt, optionale Dauer, Grund-/Gesamtpreis, Gesamt-/Sympathiebewertung (1–5), Begegnungsnotiz und einfache persönliche Leistungszeilen können bewusst erfasst, korrigiert und gelöscht werden. Personennotiz, Präferenz, Favorit, Quellinformationen und Begegnungsdaten bleiben unabhängig.
- Angebotspreise werden ausschließlich manuell als datierte Preislisten in EUR erfasst. Eine neue Liste erhält die frühere Liste; Korrekturen und bestätigtes Löschen sind möglich. Die neueste erfasste Liste ist kein Nachweis aktueller Gültigkeit. Tatsächlich bezahlte Beträge werden ausschließlich aus Begegnungen abgeleitet und getrennt bezeichnet.
- Fehlende Angaben bleiben unbekannt und werden im Profil ohne pauschale Leertexte weggelassen; keine erfundenen Beträge, Bewertungen oder automatische Übernahme zwischen Begegnung, Preisliste und Quelle. Keine Pflicht, alle vorhandenen Schemafelder zu verwenden.
- Begegnungen und Preislisten sind im gemeinsamen Profil bedarfsorientiert zugänglich; keine zusätzliche Root-Navigation oder zweite Profilimplementierung. Alle persönlichen Daten bleiben lokal, ohne neuen Netzwerkzugriff.

### Nicht vorgesehene Erweiterungen

Globale Statistik, globaler Verlauf, eigenständiger Medienbrowser und weitere Karten-/Geocoding-Funktionen sind nicht Teil des bestätigten Umfangs. Bestehende Medienarchivierung, Quellhistorie und Kartenfunktionen bleiben unverändert. Neue Erweiterungen erst bei konkretem Bedarf und ausdrücklichem Auftrag; keine Legacy-Parität um ihrer selbst willen.

## Bestehende Regressionsreferenzen

P-/X-IDs bleiben für laufende Arbeiten auffindbar, keine neue Nummerierungspflicht. Maßgeblich sind aktuelle Abschnitte statt historische Legacy-Pfade. [Historische Matrix](https://github.com/Justify87/ladies-explorer-android/blob/5ee73da667ccbc11e0b8e26d97566f155d447b5e/docs/M05-PARITY.md).

| IDs | Zuständiger Vertrag |
| --- | --- |
| P01 P02 P03 P07 P15 P19 P21 P24 P27 P28 P34 P40 P46 P47 P48 P50 | [DESIGN](DESIGN.md): Shell, Navigation, Profile, Grid, Gesten, Accessibility |
| P05 P06 P08 P09 P10 P11 P12 P13 P14 | [Suche und Filter](#suche-und-filter) |
| P20 P22 P25 P26 P49 | [Entdecken und Entscheidungen](#entdecken-und-entscheidungen); P49 auch DESIGN |
| P16 P17 P18 P29 P32 P45 | [Profile, Medien und Kontakte](#profile-medien-und-kontakte); Identität/Historie unten |
| P30 P31 | [Personen, Identität und Historie](#personen-identität-und-historie) |
| P33 P35 P36 | [Gespeicherte Personen](#gespeicherte-personen) |
| P37 P38 P39 | [Gespeicherte Suchen](#gespeicherte-suchen) |
| P04 | [Karte](#karte) |
| P23 P42 P43 P44 P51 | [ARCHITECTURE](ARCHITECTURE.md): Prefetch, Netzwerk, Medien, ARC, Operationsmodell |
| P41 | [Prüfung/CI](../README.md#prüfung--ci), [Merge-Regel](ROADMAP.md#pflege) |
| X01 X04 | [Produktziel und Grenzen](#produktziel-und-grenzen) |
| X02 X03 X06 X07 X08 | [Weitere Produktgrenzen](#weitere-produktgrenzen), ROADMAP; keine Nutzerdatenmigration |
| X05 | [Personen, Identität und Historie](#personen-identität-und-historie) |
| X09 | [Profile, Medien und Kontakte](#profile-medien-und-kontakte) |
| X10 | [Suche und Filter](#suche-und-filter) |
| X11 | [DESIGN](DESIGN.md) |
