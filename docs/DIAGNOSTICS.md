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

Die Aufzeichnung liegt getrennt vom normalen Log ausschließlich app-privat als begrenzte **eine** persistierte Sitzung unter `filesDir/diagnostics/ladies-http.jsonl`. Die Sitzung bleibt bei Neustart erhalten, die Aufnahme ist jedoch wieder **aus**. `Aufzeichnung löschen` entfernt die Sitzung und setzt den Modus auf Aus zurück. Maximal **2 MiB pro Textantwort** und **25 MiB pro Sitzung**. Überschreitet ein Body das Einzellimit, wird er komplett ausgelassen (nie stillschweigend als vollständiges Dokument ausgegeben). Bei Erreichen der Sitzungsgrenze stoppt die Aufnahme; vorhandene Daten bleiben exportierbar. Der Status und Fehler werden in den Einstellungen angezeigt.

Rust zeichnet Such-, Profil- und Medienarchivrequests **ohne zusätzliche Anfragen** direkt in den bestehenden `reqwest::blocking`-Lesepfaden auf. Coil-3-Bilder über OkHttp werden als echte Netzwerkrequests mit **Metadaten** erfasst, ohne den Bildstream vorab zu lesen. Cachetreffer sind keine neuen Netzwerkrequests. Bild- und Videobodies werden weder gespeichert noch über UniFFI transportiert. Externe Browser- und WhatsApp-Intents liegen außerhalb des App-Netzwerk-Scopes.

**HAR speichern** öffnet SAF, **HAR teilen** verwendet denselben FileProvider-Mechanismus wie TXT. Der Export ist ein einzelnes **HAR 1.2** mit Einträgen aus Rust und Coil; `log.entries` enthält Request- und Responsefelder, erlaubtes `content.text` sowie optionale `_diagnostics`-Informationen für Auslassungen, Fehler und finale Redirect-URL. DNS/TCP/TLS/TTFB-Einzelphasen werden **nicht** erfunden. Die aufgezeichnete Gesamtdauer ist echt, nicht gemessene Unterphasen sind unbekannt. Kein Replay, Mitschnitt anderer Apps, lokaler Proxy oder Entwickler-Panel.

**Datenschutzhinweis:** Cookies, Autorisierungs- und Token-Header sind maskiert. Sensible Query-Parameter werden maskiert. Andere URLs und insbesondere vollständige HTML-/JSON-Antworten können dennoch Telefonnummern, Profile oder weitere personenbezogene Inhalte enthalten. Diese Dateien nur bewusst exportieren und niemals automatisch weitergeben.

## Prüfschritte

1. App starten, INFO-Log exportieren; DEBUG einschalten und eine Suche mit Pagination durchführen. Bei Fehlern Diagnose-Status prüfen.
2. HTTP-Modus Metadaten einschalten, Suche/Profil/Medienarchiv durchführen; HAR speichern. Im HAR echte HTTP-Requests und keine Medienbodies kontrollieren.
3. Modus Mit Textantworten einschalten, Suche/Profil durchführen; vollständiges HTML/JSON innerhalb 2 MiB in `response.content.text` kontrollieren.
4. In Firefox DevTools → Netzwerk → HAR importieren und Request, Response, Redirect und Auslassungsgründe inspizieren.
5. Mit Modus Aus wiederholen, dann App neu starten und kontrollieren, dass HTTP-Capture standardmäßig aus ist. Ergebnisse und Requestanzahl mit Aufnahme an vergleichen.
6. Snapshot- und Rotations-Tests sowie `cd rust && cargo fmt --all -- --check && cargo test --workspace` ausführen; Android-Build mit `./gradlew :app:assembleDebug -Pladies.abis=arm64-v8a` bzw. bestehender CI prüfen.

**Abnahmegrenze:** Rust-Tests und ein gebautes APK beweisen keine erfolgreiche echte Leipzig-Suche, Bildanzeige auf einem Android-Gerät oder einen manuell bestätigten Firefox-HAR-Import. Diese Schritte separat dokumentieren, wenn sie wirklich durchgeführt wurden.

## Referenzen

- [Tracing](https://docs.rs/tracing/latest/tracing/) / [tracing-subscriber](https://docs.rs/tracing-subscriber/latest/tracing_subscriber/)
- [Kermit](https://kermit.touchlab.co/docs/)
- [Coil/OkHttp Netzwerk](https://coil-kt.github.io/coil/network/)
- [OkHttp Interceptors](https://square.github.io/okhttp/features/interceptors/)
- [HAR 1.2](https://github.com/ahmadnassri/har-spec/blob/master/versions/1.2.md)
