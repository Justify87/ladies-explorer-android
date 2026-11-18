# M05 – Native Entdecken-, Such- und Profiloberflächen

## Ziel und Zuständigkeiten

M05 ersetzt die temporäre M03-Testoberfläche durch native Compose-Screens.
Die Android-Schicht ist für Darstellung, Navigation und Lebenszyklus zuständig.
Rust bleibt allein verantwortlich für SQLite-Abfragen, Suchdefinitionen,
Deduplizierung, Suchergebnis-Historie, Präferenzen und Personen-Zuordnung.

- **Entdecken**: ein aktuelles Inserat pro Person, Bild, Kurztext, Detailöffnung;
  Wischen nach links/rechts oder Schaltflächen für DISLIKE/LIKE. „Später“ überspringt
  die Person nur für die aktuelle App-Sitzung. Dislikes werden nicht erneut angeboten.
- **Suche**: Ort, 5–100 km, Rubrik, Alter, Größe, Seitenlimit und alle Gruppen
  des Rust-Filterkatalogs. Ergebnisse werden über eine LazyColumn angezeigt.
  Unvollständige Suchläufe werden ausdrücklich kenntlich gemacht.
- **Favoriten**: unabhängig von der Swipe-Präferenz, in Rust gespeichert.
- **Profil**: Mediengalerie mit lokal archivierten Dateien als bevorzugte Quelle
  und Netzwerk-URLs als Fallback, Merkmale, Services, Zeiten, Telefon, externer
  WhatsApp-Aufruf, Änderungshistorie und manuell bestätigbare Identitätskandidaten.
- **Einstellungen**: Import, Datenbankstatus und Debug-Log-Export bleiben erhalten.

Die Quell-API und das SQLite-Schema v2 werden wiederverwendet; für M05 ist keine
zusätzliche Schema-Migration notwendig.

## Gespeicherte Suchen

Neue Suchdefinitionen werden typisiert als serialisierte `SearchRequest`
gespeichert. Die lokale ID bleibt stabil. Umbenennen erhält Ergebnisse und
Historie; eine inhaltliche Änderung setzt die alte Ergebnisbasis transaktional
zurück. Der Nutzer kann Vorlagen laden, aktualisieren, neu erstellen, ausführen,
löschen oder als gelesen markieren. Gespeicherte Treffer bleiben auch ohne Netz
über eine Rust-Abfrage sichtbar; vorhandene Inserate können aus dem Archiv
heraus als Profil geöffnet werden.

Importierte Web-App-Suchen mit `city/radius/category/ageMin/ageMax/heightMin/
heightMax/selected` werden in native Suchanfragen übersetzt. Die `local`-Filter
für Video, Foto, LIKE/DISLIKE und KI-/Studio-/Face-Marker werden soweit anhand
der tatsächlich gespeicherten Suchkarten bzw. Marker bestimmbar lokal angewandt.
Eine nicht eindeutig interpretierbare Definition bleibt erhalten und wird als
nicht ausführbar ausgewiesen, statt stillschweigend umgedeutet zu werden.

Gespeicherte Suchergebnisse werden nach stabiler LADIES-Inserat-ID verglichen.
Die erste erfolgreiche Ausführung stellt die Basis dar und erzeugt keinen
künstlichen Neu-Treffer. Spätere vollständige Suchläufe aktualisieren
`saved_search_results` und markieren neu hinzugekommene Treffer.
**Teilweise oder im Radius unbestätigte Suchläufe aktualisieren die
Ergebnis-Historie nicht**, damit fehlende Seiten keine falschen Entfernungen
erzeugen. Eine Ausführung lädt die Quelle über denselben Rust-Netzwerkpfad wie
die allgemeine Suche; es existiert kein zweiter HTTP-Stack.

Die frühere positive Erkennung von Business-Profilen ist im M03-Suchkartenmodell
nicht vorhanden; unbekannte Business-Klassifizierungen werden nicht erfunden.
Alle übrigen Suchquellenfelder müssen weiterhin an realen Seiten getestet werden.

## Medien und Links

Coil 3 lädt Vorschau- und Galeriebilder lifecycle-gebunden. Beim Abruf eines
Profils wird die M04-Medienarchivierung gestartet; gespeicherte App-Dateien
werden bei vorhandenem Archivbezug für die Galerie bevorzugt.
Archivierungsfehler führen nicht zum Verlust der Profildaten. Die M04-
Abbruchsteuerung wird beim Verlassen der Profilansicht betätigt.

Videos und WhatsApp werden **extern** über Android-Intents geöffnet;
kein Messenger, keine Chat-Synchronisierung und keine eigene Videoengine.
Vermeiden Sie automatische Zusammenführungen: Identitätskandidaten erfordern
eine zweite explizite Bestätigung.

## Prüfung und Abnahme

- Rust-Unit-Tests: Dislike-/Favoriten-Logik, eine Karte pro Person,
  gespeicherte Definitionen, Importkompatibilität, Umbenennen/Ändern,
  Ergebnis-Deltas und Gelesen-Status.
- CI: Rust-Formatierung und Tests, UniFFI-Bindings, Kotlin/Compose-Build,
  ARM64-Debug-APK.
- Die reale UX und Quell-HTML-/CDN-Kompatibilität erfordern weiterhin einen
  Android-Gerätetest; ein grüner CI-Lauf beweist sie nicht.
- M06 bleibt für Kartenansichten und MapLibre zuständig.
