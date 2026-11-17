# Debug-Protokoll und Fehlerdiagnose (M03)

Der Android-Client und Rust-Dienst erzeugen automatisch eine lokale Logdatei.
App-interner Speicher: filesDir/diagnostics/ladies-debug.log.
Die Datei wächst nicht unbegrenzt: ab ca. 1,5 MB bleiben die letzten
ca. 700 KB erhalten. Der Dienst versendet die Datei nicht selbst.

## Fehler reproduzieren und hier hochladen

1. Ladies Explorer öffnen und die fehlerhafte Online-Suche erneut ausführen.
2. In **Einstellungen** auf **Debug-Log als Datei speichern** tippen.
3. Beispielsweise in Downloads unter dem vorgeschlagenen TXT-Namen speichern.
4. Die TXT-Datei hier im Chat als Anhang hochladen.

Alternativ **Debug-Log teilen** verwenden. Das Android-Teilen-Menü
gibt die Datei über FileProvider und eine temporäre Leseberechtigung weiter.
Es wird keine zusätzliche Speicherberechtigung benötigt.

## Erfasste Details

- Kotlin-/UniFFI-Aufrufe, Operationsergebnisse und Stacktraces bei Fehlern.
- Rust-Suchstart, Stadt, Radius, Filterpfad und Ortsauflösung.
- HTTP-Anfragen, endgültige URL, Status, Content-Type und Antwortgröße.
- Seitenanzahl, erkannte Karten, Verifikationsstatus des Radius.
- Profile: Parserfortschritt, Snapshot-Speicherung und Fehlerursachen.
- Import aus der alten Web-App: Start, Ende und Fehler.
- Bei nicht erkanntem HTML werden bis 24.000 Zeichen der **tatsächlichen
  Antwort** in die Datei geschrieben, mit klaren BEGIN/END-Markierungen.
  Auch eine 537-Byte-Antwort ist dadurch vollständig analysierbar.

Alle Logzeilen besitzen Unix-Zeitstempel in Millisekunden.

## Sensible Daten

Ein Diagnoseprotokoll kann Suchorte, personenbezogene Profildaten,
Telefonnummern, URLs oder Inhalte der Website enthalten. Die Datei
nur bewusst weitergeben. Es werden keine Cookies, Passwörter
oder Authorization-Header absichtlich protokolliert; HTML-Ausschnitte
können jedoch sensible Daten enthalten.

## Bewusste Grenze

Der Logger verändert weder die Trefferprüfung noch die Parserlogik.
Fehlgeschlagene Extraktion wird nicht als erfolgreiche Nulltreffersuche
ausgegeben. Die Analyse des exportierten Logs ist die Grundlage für
die anschließende ursächliche Korrektur.
