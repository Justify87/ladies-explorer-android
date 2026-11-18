# M05 – Frontend-Parität: Implementierungsplan

Freigegebene Planungsrichtung: 18.11.2026. M05 aus PR #6 wird erweitert und erneut abgenommen. Dieser Arbeitsstrang ist vor weiteren M06/M07-Arbeiten zu bearbeiten. Keine Implementierung durch den Planungs-PR.

## Referenz und Dokumentrollen

- Legacy: [ladies-webapp @ 9c514af6](https://github.com/Justify87/ladies-webapp/tree/9c514af6ce3659089807fefd4ed5040599975a5a).
- Native Ausgangsbasis: [ladies-explorer-android @ d0290b9f](https://github.com/Justify87/ladies-explorer-android/tree/d0290b9fe652a03ac79e256b805219aae839cfa9), inkl. #1–#11. Bei Umsetzung aktuellen main erneut prüfen.
- [Paritätsmatrix](M05-PARITY.md): einzelne Soll-Anforderungen, Ist-Evidenz, Aufgaben-Zuordnung. [Aufgabenregister](M05-TASKS.md): Abhängigkeiten, Akzeptanz, Status und Gates. [AGENTS](../AGENTS.md): Ausführungs-Harness. [Roadmap](ROADMAP.md): Hauptplan/Scope. Fachdateien: tatsächlicher Ist-Vertrag, nicht vorab geplante Änderungen.
- Analyse ist quellcodebasiert, nicht auf Gerät/Browser ausgeführt. Legacy bootstrap.js installiert ux_patch.js, search_intelligence.js, search_runtime.js, saved_searches.js, regressions.js und requested_fixes.js; deren finale DOM-/State-Wirkung zählt, nicht allein statische Templates. M05.0 verifiziert die Referenz mit synthetischen Daten und notiert unerreichbare Szenarien.

## Ziel und Scope

Native Weiterentwicklung des wiedererkennbaren Web-UI in Compose/Material 3; keine HTML/CSS/JS-Portierung. Vollständige M05-Parität: App-Shell, Suche, Filter, entdeckungsbezogener Zustand, Swipe/Grid, Profile/Galerie, Favoriten/Likes/Dislikes, Notizen, profilbezogene Historie. Bestehende gespeicherte Suchen visuell adaptieren und vorhandene Karte als dritten Modus anbinden; ohne neue fachliche Kartenlogik oder Änderung der gespeicherten Such-Deltas. Bestehender externer Kontakt und Video-Intent werden erhalten. Notwendige eng begrenzte Rust/UniFFI-API-Ergänzungen sind zulässig.

Nicht in M05: Begegnungen, Bewertungen, manuelle Preislisten/Preishistorie (M06), neue Kartenfunktionen, neue Identitäts-Matching-/Split-/Merge-Regeln (bestehende evidenzbasierte Bestätigung erhalten), globale Statistik und eigenständige Medienarchive (separat abgleichen), allgemeine Performance-/Release-/Backup-Arbeiten (M07), Messenger, automatische Anfragen, Synchronisationsdienst, automatische KI/OCR-Preise, neue KI-Bildanalyse. Keine Dummy-Register oder vorgetäuschte Features. Legacy private Begegnungsnotizen bleiben von nativen Personennotizen getrennt.

## Fachliche Leitplanken

- Ein aktiver, unveränderlicher Suchlauf mit Filter-Snapshot, Inserat-/Person-Identität und Ergebnisreihenfolge; bearbeitete Filter sind getrennt. Swipe und Grid sind Projektionen desselben Laufs. Bestehender datenbankweiter neutraler Feed darf nicht unbemerkt in aktive Suchergebnisse einfließen; falls als expliziter Offline-/Startmodus erhalten, klar kennzeichnen. Identitätsauflösung darf keine unbemerkten Duplikate oder falschen Zuordnungen erzeugen. Abbruch, neue Suche und verspätete Antworten via RequestControl/Epoch absichern.
- Quelle/Radius/Pagination gemäß [SOURCE](SOURCE.md): keine automatische Radiusaufweitung, keine falschen Nulltreffer oder Deltas; Legacy meldet Auto-Erweiterung, native Variante warnt/lehnt entsprechend ihrem strengeren Vertrag ab. Vorschau debounced (Legacy 280 ms als Orientierung), abbrechbar, nur leichtgewichtige Zähl-/Metadatenabfrage, keine Archivierung, kein Suchlauf-Commit. Bei fehlendem belastbarem Count unbekannt/Fehler anzeigen; angezeigte Quellzahl ausdrücklich von lokal gefilterter Trefferzahl unterscheiden.
- Lokale Filter über vorhandene Daten und Rust-Regeln anwenden, mit konsistenten Standardwerten: nur Bilder/Video, Likes/Dislikes ausblenden, ALL/AI/NATURAL auf Quellmarkern. Legacy blendet Business standardmäßig aus und kennt STUDIO/FACEAPP; laut SOURCE fehlen native belastbare Klassifikationsdaten. Erst vorhandene Legacy-/Quell-Evidenz nachweisen; bei fehlenden Daten klar als nicht unterstützt ausweisen, keine fingierte Klassifikation und kein URL-Pfad als Business-Heuristik. Importierte nicht ausführbare Definitionen nicht still ändern. Replay-Likes/Hide-Likes unterscheiden; Favorit unabhängig von LIKE/DISLIKE.
- Statusprüfung: vorhandene sichere Profil-/Quellpfade nutzen bzw. minimal ergänzen; nur belegte INACTIVE/UNAVAILABLE-Signale als solche speichern. Ausfälle/Timeout/Prüfseite sind Fehler/Unbekannt, keine falsche Inaktivität. Batch begrenzt, abbrechbar, Fortschritt + Teilfehler.
- Undo: vorherigen vollständigen Präferenzzustand und Deckposition erfassen; nur nach erfolgreicher Persistenz UI fortsetzen; Reentranz/Fehler/ID-Merge berücksichtigen. Reset startet aktive Queue neu, setzt nicht automatisch persistierte Entscheidungen zurück. Native Später bleibt sessionbezogen.
- Karte: vorhandene MapLibre-Komponente/Lifecycle/Geocoding behalten. Aktiver Suchbezug nur soweit bestehende Schnittstellen dies erlauben; nötige reine UI-Filterung aus vorhandenen Daten zulässig. Wenn bestehender Kartenvertrag einen anderen Datenumfang liefert, deutlich kennzeichnen statt neue Kartenprojektion in M05 einzubauen.
- Profile: gemeinsamer Medien-/Identitäts-/Aktions-/Inhaltsaufbau für Swipe und Standalone; lokale Medien zuerst, Remote-Fallback, vorhandene externe Video- und WhatsApp-Intents. Keine eingebettete Videoplayer-/Chat-Infrastruktur. Personennotizen transaktional, keine Überschreibung fremder Felder; historische Identität und Zuordnung unverändert.

## Designvorgaben

Legacy tokens.css: Primär #D12D7C, strong #C52472, soft #F9DBE9, softer #FFF2F7; Hintergrund #FBFBFD, Oberfläche #FFFFFF, soft #F6F6FA, muted #ECEEF4; Text #172238 / #59657A / #8A93A5; Rahmen #ECEEF3; Status grün #1FBF78, blau #658BC8, gelb #DFA51D, Gefahr #B94865. Kartenradius 22–23 dp, Controls ca. 18 dp, Pills rund; Abstände an 4/7/10/14/18/24 orientieren. CSS-Pixel nicht dogmatisch übertragen: Kontrast, system font scale, Touch-Ziele ≥48 dp und Insets haben Vorrang. Dynamische Systemfarben nicht standardmäßig; vorhandenen Dunkelmodus mit markentreuer Palette erhalten.

- Shell: kontextabhängiger Header mit Branding, Alle/Jetzt/Heute in Entdecken; Suchsymbol bzw. gespeicherte Suchen mit Neu-Indikator in Suche; Bottom-Navigation Entdecken/Suche/Gespeichert/Einstellungen; Swipe/Grid/Karte als Ansichtswechsel in Entdecken. Keine künstlichen Handyrahmen oder Systemleiste. Eigenständiges Profil ohne Bottom-Navigation, Back/Mehr auf Medien.
- Swipe: große bilddominierte Galerie (nahe sichtbarer Höhe, responsive), Story-Balken/Zähler, Tippzonen, Verfügbarkeit, Undo; vertikal Details, horizontal Entscheidung, keine Konflikte mit Tabs/Scroll/Media. Native zugängliche Entscheidungsaktionen beibehalten.
- Grid: zwei Spalten, Seitenverhältnis ca. 0,77 (Breite/Höhe), rund 23 dp, Cover, Dunkelgradient unten, Name/Alter, direktes Like oben rechts; Lazy Grid, stabile Schlüssel; Platzhalter.
- Suche: globale Filtersuche; Ort/Radius, häufige Filter (Top 5), Verfügbarkeit, Alter (dual range), Treffpunkt, einklappbare Kategorien/Eigenschaften (inkl. Größe)/Services/weitere Optionen; fester Footer Reset + Suche mit Count/Status. Filtergruppenstatus und Scroll erhalten.
- Profile: Identität/Name/Alter/Ort/Status/Medienkennzeichen, Action Rail Like/Favorit/WhatsApp/Kontakt, Tabs Info/Dienste/Analyse/Notizen/Verlauf; Info-Raster (bis 3 Spalten responsiv), Filter Ja/Nein/Unbekannt, Merkmale/Öffnungszeiten, Beschreibung einklappbar; Dienste separat; Analyse nur belegte Konsistenz-/Medien-/Identitätsevidenz; Verlauf Zeitstrahl, Anzeigen und Snapshot-Vergleich. Keine Begegnungs-/Preisregister in M05.
- Gespeichert: Tabs Favoriten/Likes/Dislikes, Anzahl, Sortierung Datum/Name, Statusprüfung, Watchlist „Neu & geändert“ (36 h, zunächst 3, aufklappbar, keine Duplikate in Restliste), kompakte Zeile mit Thumbnail, Name/Alter, Ort, Status/Aktivitätszeit, Änderungsbadge/zusätzliche Anzeigen, Herz/Mehr-Menü. Gespeicherte Suchen als eigene Übersicht, Neuigkeitsindikator, Delta, Editor und Ergebnisgrid.

## Zielarchitektur

App-Shell + Navigation; klar getrennte UI-State-Verantwortung für Suche, Entdecken, Profile und Gespeichert. MainViewModel (922 Zeilen im Ausgangsstand) nicht weiter monolithisch aufblasen; nur notwendige Extraktion, kein Zwang zu mehreren ViewModels oder Gradle-Modulen. Gemeinsame Compose-Komponenten: LadiesTheme, ProfileMedia, ProfileIdentity, ProfileActionRail, ProfileContent, ProfileGridCard, SavedPersonRow, SearchFilterEditor, FilterChipGroup, ProfileStatus, Loading/Empty/Error. Namen Vorschläge. CoreStore/UniFFI/Rust für fachliche Zustände, Query, Persistenz; Kotlin für Präsentation, UI-State und Lifecycle. Bestehende Coil-3-, Diagnose- und MapLibre-Infrastruktur beibehalten. Neue Libraries nur bei nachweisbarem Bedarf. Keine Parallelimplementierungen, ersetzten Code entfernen.

## Reihenfolge und Gates

M05.0 Referenzinventar → .1 Design/Shell → .2 Suche → .3 gemeinsame Profile/Medien → .4 Swipe → .5 Grid → .6 Details/Notizen/Verlauf → .7 Gespeichert → .8 gespeicherte Suchen/UI-Konsistenz → .9 Gesamtabnahme. Konkrete Aufgaben und Abhängigkeiten ausschließlich in [M05-TASKS](M05-TASKS.md). Pro Meilenstein begrenzter PR, Review vor Merge, nächster auf integriertem Stand. Bei tausenden Zeilen sinnvolle kleinere PRs mit eindeutigen IDs, keine überlappenden State-Schreibarbeiten.

Gates:
- G0 Referenz: jede Matrixzeile mit Quellenbeleg, finalem Legacy-Verhalten und Scope; repräsentative synthetische Browser-/Android-Referenzen, fehlende Slots explizit.
- G1–G8: alle jeweiligen Aufgaben mit gezielten Tests und Review gegen Matrix; keine offenen kritischen/hohen Defekte, Scope-Abgleich, Folgefehlerprüfung, aktualisierte Ist-Dokumentation nur bei geänderten Verträgen. Geräteabhängige Fälle ausdrücklich DEVICE_PENDING statt behauptetem Erfolg.
- G9: vollständige Paritäts-/Regressionsmatrix, keine offenen in-scope Anforderungen oder unehrlichen Abnahmen; fehlende Daten-Evidenz als Blocker/akzeptierte Abweichung mit Nutzerentscheidung. CI grün, APK verifiziert, visuelles Review und echte Geräteabnahme. Fehlt diese, bleibt G9 offen; Code-/CI-Teil kann separat belegt werden. Keine M07-Freigabe aus M05-CI ableiten.

## Teststrategie

Je Aufgabe gezielte Rust-Fixtures/SQLite-Tests oder JVM-State-Tests; bei UI Gesten/Navigations- und visuelle Testfälle, Compose UI-Test nur wenn sinnvoll in bestehende Toolchain integrierbar. Bestehende CI (README): Export-Roundtrip, Rust fmt/tests/clippy, Android JVM, Lint, arm64 APK. Keine neue CI-Orchestrierung nötig.

Synthetische Referenzfälle: 0/1/viele Suchtreffer, vollständige/partielle/unbestätigte Radiusläufe, mehrere Inserate einer Person, neue/alte/geänderte/aktive/inaktive/unbekannte Profile, Like/Dislike/Favorit-Kombinationen, Bilder/Video/fehlende Medien, Offline-Cache/Timeout, importierte Notizen und gespeicherte Definitionen. Keine privaten Daten in Git. UI prüfen auf 360/390/430 dp und größer (z. B. 600 dp), Schriftgröße normal/erhöht, hell/dunkel, Rotation/Back/Systemleisten, Tastatur, TalkBack/Touch-Ziele, schnelle Navigation, App-Neustart; Vergleich mit Legacy-Browser-Viewport bei äquivalenten Daten. Belege in PR/Tasks als konkrete Datei, Testname, Kommando/CI-Lauf, Screenshot-Link oder Geräteprotokoll; keine privaten Bilder.

Abschluss: Design, Suche inkl. Vorschau, gemeinsame Ergebnismenge, Deck/Undo, Grid, Profilnotizen/Verlauf/Offline, gespeicherte Personen/Status und gespeicherte Suchen vollständig prüfen. Kein Datenverlust, keine unbemerkte Änderung von Radius-/Pagination-/Identitäts-/Delta-Verträgen, keine M06/M07-Scope-Erweiterung.
