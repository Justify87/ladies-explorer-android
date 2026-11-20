# Lokale Diagnose: Logging, Performance und HTTP/HAR

Stand: 18. November 2026. Diese Funktionen dienen der lokalen Fehlersuche und sind **keine Telemetrie**.

## Diagnose-Log (TXT)

Unter **Einstellungen → Diagnose** ist das normale INFO-Logging aktiv. **Debug-Details** schaltet zusätzliche DEBUG-Ereignisse ein; es gibt keinen TRACE-Modus. Kermit schreibt ausgewählte Kotlin-Ereignisse in Android Logcat und asynchron über UniFFI in **dieselbe** von Rust verwaltete Datei wie die Rust-Tracing-Events:

`filesDir/diagnostics/ladies-debug.log`

Rust verwendet einen eigenen Tracing-Dispatch pro `LadiesCore`, keinen globalen, auf die erste Datenbank festgelegten Dateipfad. Eine kleine synchronisierte Senke begrenzt die Datei auf ungefähr 1,5 MB und behält beim Rotieren die letzten ca. 700 KB. Ein Schreibfehler wird in den Einstellungen angezeigt. Das Log enthält Level, Zeit, Ziel/Komponente, Meldung und gegebenenfalls Operation. Normale Ereignisse loggen **keine vollständigen HTTP-Antworten**.

**Debug-Log als Datei speichern** öffnet den Android-Dateiauswahldialog (SAF). **Debug-Log teilen** verwendet FileProvider und einen unveränderlichen Cache-Snapshot. Der ursprüngliche Logpfad wird niemals freigegeben. Geteilte Snapshots verbleiben zunächst im app-privaten Cache; beim folgenden Export werden über 48 Stunden alte Snapshots entfernt.

## Performance

Rust misst Gesamtdauern von Suche, Profilabruf, Legacy-Import und Medienarchiv mittels monotonic `Instant`. Innerhalb ausgewählter Grenzen misst DEBUG das Netzwerk bis zu Antwortheadern, das Body-Lesen, die HTML-/JSON-Verarbeitung, SQLite-Schreibphasen und Bildverarbeitung. Kotlin erfasst ausgewählte UniFFI-Aufrufe mit `elapsedRealtimeNanos`. Gesamtzeiten erscheinen auf INFO, Detailzeiten nur auf DEBUG. Die 350-ms-Pagination-Pause ist **keine** Parserzeit; die Gesamtdauer kann sie dennoch einschließen. HTTP-Zeit bis zu den Headern umfasst Verbindungsaufbau und etwaige Redirects und darf nicht als reine Serverwartezeit interpretiert werden.

## HTTP-Aufzeichnung und HAR

Unter **Einstellungen → HTTP-Aufzeichnung** stehen drei Modi zur Verfügung:

| Modus | Umfang |
|---|---|
| Aus | Standard und **bei jedem App-Neustart**, kein neuer Mitschnitt |
| Metadaten | Tatsächliche HTTP-Requests mit Methode, URL, Status/Fehler, Headern, MIME, soweit messbaren Größen und Zeiten |
| Mit Textantworten | Wie Metadaten, zusätzlich vollständiges HTML, JSON oder andere geeignete Textantworten innerhalb des Limits |

Die Aufzeichnung liegt getrennt vom normalen Log ausschließlich app-privat als begrenzte **eine** persistierte Sitzung unter `filesDir/diagnostics/ladies-http.jsonl`. Die Sitzung bleibt bei Neustart erhalten, die Aufnahme ist jedoch wieder **aus**. `Aufzeichnung löschen` entfernt die Sitzung und setzt den Modus auf Aus zurück. Maximal **2 MiB pro Textantwort** und **25 MiB pro Sitzung**. Überschreitet ein Body das Einzellimit, wird er komplett ausgelassen (nie stillschweigend als vollständiges Dokument ausgegeben). Bei Erreichen der Sitzungsgrenze stoppt die Aufnahme; vorhandene Daten bleiben exportierbar. Der Status wird in den geöffneten Einstellungen regelmäßig aktualisiert. Ein fehlgeschlagener Schreibvorgang stoppt die Aufzeichnung, damit keine beschädigte Sitzung unbemerkt fortgesetzt wird. Ein unvollständiges letztes JSONL-Ereignis wird beim Neustart entfernt und die Wiederherstellung als Statusmeldung gemeldet; ein beschädigtes Ereignis mitten in einer laufenden Sitzung verhindert den Export statt stillschweigenden Datenverlust vorzutäuschen.

Rust zeichnet Such-, Profil-, Anzeige-Bild-, Medienarchiv- und Geocoder-Requests **ohne zusätzliche Anfragen** direkt in den bestehenden `reqwest::blocking`-Lesepfaden auf. Coil delegiert jeden Netzwerk-Bildabruf an Rust; Cachetreffer sind keine neuen Netzwerkrequests. Kodierte Bildbytes werden für die Anzeige begrenzt über UniFFI übertragen, jedoch nicht in der HTTP-Aufzeichnung gespeichert. MapLibre-Kachel-/Stilrequests und externe Intents bleiben außerhalb des Recorders.

**HAR speichern** öffnet SAF, **HAR teilen** verwendet denselben FileProvider-Mechanismus wie TXT. Der Export ist ein einzelnes **HAR 1.2** mit Einträgen aus Rust (einschließlich Anzeige-Bildern und Geocoding); `log.entries` enthält Request- und Responsefelder, erlaubtes `content.text` sowie optionale `_diagnostics`-Informationen für Auslassungen, Fehler und finale Redirect-URL. DNS/TCP/TLS-Einzelphasen werden **nicht** erfunden. Die gemessene Gesamtdauer ist echt. Da HAR 1.2 nichtnegative Werte für `send`, `wait` und `receive` verlangt, wird das aggregierte Intervall bis zum Eintreffen der Header als `wait` abgebildet; das ist **keine isolierte TTFB-Messung** und wird in `_diagnostics` erklärt. Bild-Downloads erfassen Header- und Body-Lesezeit im Rust-Medientransport; keine Kotlin-HTTP-Ereignisbrücke. Keine zusätzlichen Netzwerkanfragen durch Capture; kein Replay, Mitschnitt anderer Apps, lokaler Proxy oder Entwickler-Panel.

### Redirect-Grenzen je Netzwerkpfad

- Quellensuche/Profilabruf: Der eigene Redirect-Handler erfasst beobachtete Weiterleitungen separat (ohne Redirect-Body); siehe [network.rs](../rust/ladies-core/src/source/network.rs).
- Anzeige-Bilder: Rust-Medientransport ohne Redirects; gleicher Recorder wie Archivierung, siehe [media.rs](../rust/ladies-core/src/media.rs).
- Rust-Medienarchiv: automatische Redirects sind deaktiviert.
- Geocoding: reqwest folgt Redirects intern; der Recorder sieht die abschließende Antwort mit Status, finaler URL und aggregierter Header-Zeit. Zwischenliegende automatisch verfolgte Redirects sind nicht als separate HAR-Einträge sichtbar; siehe [map.rs](../rust/ladies-core/src/map.rs). MapLibre-Kacheln und -Stile sind vollständig außerhalb des Recorders.

**Datenschutzhinweis:** Cookies, Autorisierungs- und Token-Header sind maskiert. Sensible Query-Parameter, eingebettete URLs in Fehlern, URL-Zugangsdaten sowie sensible Parameter in Redirect- und Referer-Headern werden maskiert. Andere URLs und insbesondere vollständige HTML-/JSON-Antworten können dennoch Telefonnummern, Profile oder weitere personenbezogene Inhalte enthalten. Diese Dateien nur bewusst exportieren und niemals automatisch weitergeben.

## Prüfschritte

1. App starten, INFO-Log exportieren; DEBUG einschalten und eine Suche mit Pagination durchführen. Bei Fehlern Diagnose-Status prüfen.
2. HTTP-Modus Metadaten einschalten, Suche/Profil/Medienarchiv durchführen; HAR speichern. Im HAR echte HTTP-Requests und keine Medienbodies kontrollieren.
3. Modus Mit Textantworten einschalten, Suche/Profil durchführen; vollständiges HTML/JSON innerhalb 2 MiB in `response.content.text` kontrollieren.
4. In Firefox DevTools → Netzwerk → HAR importieren und Request, Response, Redirect und Auslassungsgründe inspizieren.
5. Mit Modus Aus wiederholen, dann App neu starten und kontrollieren, dass HTTP-Capture standardmäßig aus ist. Ergebnisse und Requestanzahl mit Aufnahme an vergleichen.
6. Geocoding ausdrücklich starten und im HAR dessen Requests und finale URLs kontrollieren; einzelne interne Redirect-Hops sowie MapLibre-Kachelrequests werden nicht erwartet.
7. Snapshot-, Rotations- und Recorder-Tests sowie die zentralen Build-/CI-Prüfungen aus [README](../README.md) ausführen.

**Abnahmegrenze:** Rust-Tests und ein gebautes APK beweisen keine erfolgreiche echte Leipzig-Suche, Bildanzeige auf einem Android-Gerät oder einen manuell bestätigten Firefox-HAR-Import. Diese Schritte separat dokumentieren, wenn sie wirklich durchgeführt wurden.

## Referenzen

- [Tracing](https://docs.rs/tracing/latest/tracing/) / [tracing-subscriber](https://docs.rs/tracing-subscriber/latest/tracing_subscriber/)
- [Kermit](https://kermit.touchlab.co/docs/)
- [Coil benutzerdefinierter Netzwerkclient](https://coil-kt.github.io/coil/network/)
- [HAR 1.2](https://github.com/ahmadnassri/har-spec/blob/master/versions/1.2.md)

## M05.7 Statusprüfung

Einzel- und Batchstatus laufen ausschließlich über bestehenden Rust-HTTP-Recorder. Bestätigte Checks protokollieren Listing-ID, Status und HTTP-Code; Kotlin zeigt Fortschritt, Fehleranzahl und letzten Fehler. Unbestätigte Antworten überschreiben keinen bestätigten Status. Bei Navigation/ON_STOP wird RequestControl abgebrochen, bereits erfolgreich gespeicherte Teilresultate bleiben erhalten und werden lokal neu projiziert. Auf Gerät kontrollieren: keine zusätzlichen Such-/Archivrequests, stabile Reihenfolge bei wiederholter Statusprüfung, Cancel/Timeout und Statusfehler. Gerätebelege siehe [Arbeitsregister](ROADMAP.md).
