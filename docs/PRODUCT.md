# Produkt und Funktionen

Maßgeblicher fachlicher Soll-Vertrag der eigenständigen lokalen Android-App. Technik: [ARCHITECTURE](ARCHITECTURE.md); UI/UX: [DESIGN](DESIGN.md); offene Umsetzung: [ROADMAP](ROADMAP.md). Abweichungen vom Soll sind keine stillschweigenden Produktentscheidungen.

## Produktziel und Grenzen

Auf einem Gerät Profile, Suchergebnisse, Personen, Medien, persönliche Entscheidungen und Historie verwalten. Kein separates Backend, Synchronisierungsdienst, integrierter Messenger, automatische Nachrichten/Anrufe, KI-Bildanalyse, automatische OCR-/KI-Preiserfassung oder erfundene Daten.

**Verbindliche Nutzerentscheidung vom 20.11.2026:** Die frühere Web-App ist obsolet. Keine Datenübernahme aus der Web-App und keine nutzerseitige Datenmigration, unabhängig von Format oder Verfahren (SQL, ZIP, Dateien, Gerätewechsel). Kein Nutzerimport und keine Ersatzmigration vorhalten oder entwickeln. Daten entstehen nativ in der Android-App. Interne integritätserhaltende Schemaentwicklung derselben Android-Datenbank ist keine Nutzerdatenmigration. Diagnoseexporte sind kein Datenübernahmeverfahren. Der entgegen diesem Soll noch vorhandene Legacy-Import wird gemäß [Arbeitsregister](ROADMAP.md#unabhängige-technische-bereinigung) entfernt.

## Suche und Filter

- Ort, Rubrik, Radius 5/10/20/30/50/70/100 km, Alters-/Größenbereich, Treffpunkt, Kategorien, Merkmale, Services, Seitenlimit 1–50; Werte gemäß belegtem Quellkatalog. Alle/Jetzt/Heute kanonisch, nur Schnellwahl im Header führt unmittelbar Suche aus. Top 5 aus tatsächlich abgeschlossenen Suchläufen, dedupliziert je Lauf, stabil sortiert, Rubrik zählt; Vorschau/bloße Auswahl zählen nicht, definierte Standardwerte ausgenommen.
- Aktiver Suchlauf und Filterentwurf getrennt. Vorschau abbrechbar, leichtgewichtig, keine Speicherung von Suchläufen, Inseraten, Beobachtungen oder Medien. Quellzahl von lokal sichtbaren Treffern unterscheiden, bei fehlenden Metadaten unbekannt statt null. Erfolg öffnet Entdecken, Fehler/Abbruch erhalten letzten aktiven Lauf.
- Keine automatische Radiusaufweitung; unbekannte Struktur kein Nulltreffer. Explizite Radiusabweichung auf erster Seite Fehler, später bestätigte Karten als Teilresultat. Fehlende Radiusbestätigung/unvollständige Pagination: Teilresultat, keine bestätigte Entfernung oder vollständige Ergebnismenge vortäuschen. Erster HTTP-404-Sonderfall leer, aber nicht radiusbestätigt, kein bestätigter Nulltreffer für Deltas. Reguläre Teilresultate speicherbar, Abbruch/nicht als Teilresultat gültiger Fehler kein Erfolg.
- Lokale Filter: nur Fotos/Video, Likes/Dislikes ausblenden, ALL/AI/NATURAL ausschließlich auf vorhandenen Markern. STUDIO/FACEAPP/Business ohne belastbare Daten nicht unterstützt; keine URL-Heuristik. Replay-Likes und Hide-Likes unterscheiden; normale und gespeicherte Suche verwenden gleiche Sichtbarkeitsregeln.

## Entdecken und Entscheidungen

- Swipe und Grid sind verschiedene Ansichten derselben ausgeführten Ergebnismenge, kein Vermischen mit neutralem, sichtbar lokalem Startbestand ohne aktiven Lauf. Stabile Reihenfolge und eindeutige Inserate; Moduswechsel ohne neue Suche, gültige Position erhalten.
- Like/Dislike und Favorit unabhängig. Geste/Buttons gleiche fachliche Entscheidung, erst bestätigte Entscheidung erlaubt Weitergehen. Später bleibt sessionlokal. Undo stellt vorherigen vollständigen Präferenzzustand und gültigen Deckschritt wieder her; ältere Medien-/Scrollreferenz kann kontrolliert fehlen. Reset/Replay starten Traversierung neu, keine persistierten Entscheidungen löschen. Standalone-Präferenz, Neutral-Rücksetzung, Favorit keine Deck-Visits.
- Grid direktes Like ohne Profilöffnung; neu gelikte Inserate nur im aktiven Grid temporär ausblenden, bei Writefehler zurücknehmen. Nach erfolgreichem Commit und Lesefehler kein zweiter Write. Keine Aktionen auf inzwischen anderes Profil.

## Profile, Medien und Kontakte

- Gemeinsamer Profilumfang in Swipe/Standalone: Identität, Name/Alter/Ort, belegte Verfügbarkeit/Status, Öffnungszeiten, Merkmale, Beschreibung, Dienste, Filterbelege (Ja/Nein/Unbekannt), Präferenz/Favorit, Medien, Kontakte, Analyse, Notizen, Verlauf, Snapshotvergleich, verknüpfte Anzeigen. Alle vorhandenen Informationen erreichbar, keine erfundenen Werte.
- Galerie in Quellreihenfolge; lokale archivierte Medien zuerst, Remote-Fallback; Fehler/Platzhalter. Reine Anzeige verändert Archiv und Datenbestand nicht. Eingebettete stumme Videos, Original/Fit; UX gemäß DESIGN. Verknüpfte Anzeigen externe Original-URLs. Telefon, WhatsApp, Originalprofil nur ausdrücklich ausgelöst, keine automatischen Aktionen.
- Neu/bekannt, Video, Aktivität nur auf Evidenz; Vergleich ausdrücklich, keine künstlichen Scores oder Datenfelder nur für Mockups.

## Personen, Identität und Historie

Eine Person kann mehrere Quellprofile/Inserate besitzen. Präferenz, Favorit und Notiz gehören der Person; Quellenbeobachtungen und Nutzerzustand getrennt. Historie zeigt reale Übergänge, keine künstlichen Änderungen gleicher Inhalte. Identitätskandidaten auf vorhandener Evidenz; keine automatische Zusammenführung oder freier manueller Merge/Split. Explizite Bestätigung in beiden Profilkontexten zeigt Konsequenzen; fehlende Evidenz, manuell getrennte Inserate, widersprüchliche Präferenzen oder ungespeicherte Notizen betroffener Personen blockieren Merge. Daten und historische Zuordnungen bewahren; aktuelles Listing möglichst erhalten, sonst gültiger Kontext mit Hinweis; kein Undo-Step. Personennotiz getrennt von Begegnungsnotiz, Konflikte ausdrücklich lösen, keine stillen Überschreibungen/Umdeutung von Entwürfen. Verlauf, Anzeigen, Original-Link und Snapshotvergleich.

## Gespeicherte Personen

Favoriten/Likes/Dislikes mit korrekten Personen, Counts, Datum-/Namenssortierung und Watchlist „Neu & geändert“ (36 h, zunächst drei, ausklappbar, keine Duplikate). Einzel-/Batchstatusprüfung mit Fortschritt, Abbruch und Teilfehlern. Nur bestätigte Quellenbeobachtung ändert Status: direkte same-path 404/410 bestätigen UNAVAILABLE; erkannte 200-Detailseite mit erwarteter ID ACTIVE; unterstützte Detailseite ohne ID kann Inhalte liefern, aber UNKNOWN, explizite Prüfung zählt als Fehler. Falsche ID/Pfad, Prüfseite, Timeout oder Transportfehler sind keine Inaktivität. Statuszeit = letzte bestätigte Quellenbeobachtung, Status-only ändert nicht Inhalts-/Personenreihenfolge oder archivierte Medien. Abbruch erhält bestätigte Teilresultate.

## Gespeicherte Suchen

Definitionen erstellen, bearbeiten, laden, ausführen, umbenennen, löschen; gemeinsame Filter/Editor. Umbenennen erhält Ergebnisbasis/Historie, inhaltliche Änderung setzt Basis zurück. Erster vollständiger radiusbestätigter Lauf bildet Basis ohne künstliche Neu-Treffer. Nur spätere vollständige bestätigte Läufe erzeugen Mitgliedschaftsänderungen; Teilresultate/unbestätigtes 404 keine falschen Entfernungen. Stabile Inserat-ID, Treffer offline/paginiert, kein 1.000-Treffer-Limit. Nicht unterstützte Definitionen sichtbar kennzeichnen und nicht still ändern. Neuigkeiten nur ausdrücklich als gelesen markieren; Öffnen, Reload, Pagination, Rückkehr, andere Mutationen quittieren nichts. Keine veraltete Aktion gegen geänderte Definition.

## Karte

DB-weiter Bestand, alle Profile oder nur Favoriten, aktuelles Inserat pro Person. Ungefähre deutsche PLZ-Zentren, sonst Stadtzentren; keine Ersatzkoordinaten, GPS-/Gerätestandort oder exakten Adressen/anderen privaten Profilinformationen an Geocoder. Marker keine Treffpunkte oder Navigationsziele; nicht auflösbare Orte ohne Marker. Profile am selben Ort gemeinsam auswählen. Standortauflösung nur ausdrücklich, max. 15 verschiedene Schlüssel je Lauf, Abbruch nach laufender Anfrage, keine konkurrierenden Läufe; vorübergehend fehlgeschlagene Schlüssel hinter unversuchte stellen. Teilergebnisse und Koordinaten offline verfügbar, Kacheln cacheabhängig. Kein automatisches Geocoding, manuelle Marker, Routing, Treffpunkte oder Offline-Kachelpakete.

## Einstellungen und lokale Diagnose

DB-/Core-Status, Replay-Likes und lokale Diagnose, keine Telemetrie. INFO, optional DEBUG (kein TRACE), reale Operations-/Netzwerk-/Verarbeitungszeiten, sichtbare Fehler, TXT-Export. HTTP-Aufzeichnung bewusst: Aus (Standard nach jedem Neustart), Metadaten oder zusätzlich geeignete Textantworten. Eine begrenzte Sitzung bleibt erhalten; Löschen entfernt sie und schaltet Aufnahme aus. HAR 1.2 speicher-/teilbar, reale Werte und erkennbare Auslassungen/Fehler, keine erfundenen Einzelzeiten. Keine Medienbodies, zusätzlichen Requests, Replay, Mitschnitt fremder Apps, Proxy oder MapLibre-Kacheln. Vertrauliche Header/Parameter maskieren, übrige Inhalte können personenbezogen sein; nur bewusster Export, keine automatische Weitergabe. Technische Grenzen: [ARCHITECTURE](ARCHITECTURE.md#diagnoseinfrastruktur).

## Weitere Produktgrenzen

Begegnungen/Bewertungen, manuell erfasste Preislisten, weitere Kartenfunktionen, globale Statistik/Historie und eigenständiges Medienarchiv nur im ausdrücklich bestätigten Umfang gemäß ROADMAP. Vorhandene Tabellen allein sind keine zugesagte Funktion. Keine Legacy-Parität um ihrer selbst willen.

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
