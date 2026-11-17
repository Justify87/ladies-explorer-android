# Debug-Protokoll und Fehlerdiagnose (M03)

Die Android-App verwendet einen Logger pro Rust-Core. HTTP, Parser, SQLite und
Android/UniFFI-Operationen schreiben in denselben app-privaten Journalpfad:

    filesDir/diagnostics/ladies-debug.log

Logeinträge und Rotation sind synchronisiert. Das Log wird ab ungefähr
1,5 MB auf die jüngsten ca. 700 KB reduziert und nicht automatisch versendet.
Ein Schreibfehler wird gespeichert und in den Einstellungen angezeigt.

## Fehler reproduzieren und hier hochladen

1. Die App öffnen und den Fehler unter **Suche** erneut auslösen.
2. Unter **Einstellungen** die Verfügbarkeit bzw. den letzten Schreibfehler prüfen.
3. **Debug-Log als Datei speichern** auswählen.
4. Eine TXT-Datei beispielsweise in Downloads speichern und hier im Chat hochladen.

Alternativ **Debug-Log teilen** wählen: Android öffnet den Teilen-Dialog.
Beide Funktionen erzeugen zuerst einen **unveränderlichen Snapshot** unter
derselben Synchronisation wie die laufenden Logschreibvorgänge.
Die Original-Logdatei wird niemals für das Teilen freigegeben.

Der SAF-Dateiexport entfernt seinen Snapshot nach dem Kopieren.
Geteilte Snapshots verbleiben zunächst im app-privaten Cache, damit
Empfänger sie noch lesen können; beim nächsten Export werden über
48 Stunden alte Snapshots entfernt. Android kann Cache-Dateien früher
automatisch bereinigen. Die FileProvider-Konfiguration erlaubt
ausschließlich diese Cache-Snapshots.

## Was erfasst wird

- Kotlin-/UniFFI-Aufrufe und Stacktraces bei Fehlern.
- Rust-Ortsauflösung, Filter, Radius, HTTP-Anfragen, Status und Antwortgrößen.
- Parser-Ergebnisse, Pagination und Schreibvorgänge in SQLite.
- Bei nicht erkannten Seiten bis 24.000 Zeichen des echten HTML.
- Auch unerwartete HTTP-200-Weiterleitungen werden mit ihrem tatsächlich
  gelesenen HTML vor dem Abbruch dokumentiert.

Pro Logeintrag wird ein Unix-Zeitstempel in Millisekunden erfasst.

## Datenschutz

Debug-Protokolle können Profildaten, Telefonnummern, Suchorte,
Links und personenbezogene HTML-Inhalte enthalten. Bitte nur bewusst
teilen. Cookies oder Autorisierungs-Header werden nicht explizit
protokolliert; serverseitige HTML-Antworten können trotzdem sensible
Informationen enthalten.

## Abnahme

Rust-Tests prüfen Rotation, mehrere unabhängige Logger-Instanzen,
Fehlerzustände, gleichzeitige Snapshot-Erstellung und Weiterleitungs-
HTML. CI prüft UniFFI und Android-ARM64-APK. Ein realer
Android-SAF-/FileProvider-Smoke-Test ist zusätzlich nötig; die
CI ersetzt keinen Geräteversuch.

Die Diagnose ist von der Suchlogik getrennt. Parserfehler werden nicht
zu falschen leeren Suchergebnissen umgedeutet.
