# Import der bisherigen Web-App-Daten

## Einmaliger Export (macOS oder Linux)

Das Exportskript verwendet nur Bash, sqlite3 und zip, **kein Python**.
Es liest die Web-App-Datenbank ausschließlich über einen konsistenten
SQLite-Backup-Snapshot; die aktive Web-App-Datenbank wird nicht verändert.

Die Verzeichnisstruktur der bisherigen Web-App ist laut README:

    data/
      ladies.sqlite3
      ladies_data/
        media/
        raw_profiles/

Beispiel:

    ./tools/export-legacy.sh /pfad/zur/ladies-webapp/data ~/Downloads/ladies-export.zip

Die ZIP-Datei enthält private Personen-/Verlaufsdaten. Sie gehört nicht
in GitHub, Git-Commits oder ein öffentliches Dateiverzeichnis.

## Import auf Android

1. Die erzeugte ZIP-Datei auf das Android-Gerät übertragen.
2. Ladies Explorer öffnen und unter **Einstellungen** auf
   **Web-App-Datenarchiv importieren** tippen.
3. Die ZIP-Datei auswählen. Die Android-Dateiauswahl benötigt keine
   zusätzliche Speicherberechtigung.
4. Nach erfolgreichem Import zeigt die App die Zahl der Personen,
   Quellenprofile, Inserate und übernommenen Medien an.

Der Rust-Core führt den Datenbankimport **atomar in einer SQLite-Transaktion**
durch. Er importiert nur in eine leere lokale Datenbank; vorhandene
Personen/Historien werden niemals überschrieben.

## Was erhalten bleibt

- Personen samt Personen-IDs, Nutzerentscheidungen (LIKE/DISLIKE),
  Favoriten, Notizen und Kennungen.
- Quellenprofile und Inserate mit denselben IDs, Status und Zuordnungen.
- Suchläufe, Beobachtungen, Snapshots, Feldwerte und Änderungs-/Identitätsereignisse.
- Gespeicherte Suchen einschließlich String-IDs, Ergebnisverläufen und
  Benachrichtigungsstatus.
- Archivierte Fotos/Videos mit SHA-256-Validierung, Remote-Quellen und
  ihren Metadaten. Rohprofilarchive werden mit übertragen.
- Bewertungen/Besuche sowie **manuell** erfasste Preislisten samt Positionen.

### Absichtliche Ausschlüsse

- WhatsApp-/Messaging-Daten und Kommunikationskonten.
- KI-/OCR-Analysen und maschinell erzeugte Preislisten einschließlich
  der zugehörigen Positionen.
- Die alten App-Konfigurationen (app_settings), da ihre Optionen und
  ggf. hinterlegten Zugangsdaten nicht zur nativen App passen.
- Alte reine Kommunikationslinks whatsapp_url, sms_url, tel_url
  aus Snapshots. Diese können später aus der normalisierten Nummer
  bzw. über Android-Intents rekonstruiert werden.
- Logs, temporäre Cachedateien, Python-/Flask-Umgebung.

Diese Ausschlüsse sind ausdrücklich Teil der gewünschten fachlichen Migration
und **kein vollständiges byteidentisches Backup**. Das ursprüngliche
Web-App-Datenverzeichnis bleibt unverändert.

## Datenkonsistenz und Fehlerbehandlung

- Die Quell-SQLite-Datenbank muss dem Web-App-Schema v1 entsprechen.
- Quelltabellen und -spalten werden gegen ein festes Importinventar
  geprüft. Unbekannte zusätzliche Spalten verhindern den Import, statt
  Daten unbemerkt zu verlieren.
- SQLite-Fremdschlüssel inklusive Personen-/Quellenprofil-Zugehörigkeit
  werden vor dem Commit überprüft.
- Für archivierte Medien wird der gespeicherte SHA-256 erneut berechnet.
- Fehlen Dateien oder ist das ZIP/Schema ungültig, wird der Vorgang
  abgebrochen, ohne vorhandene Daten zu überschreiben.
- Das Dateiverzeichnis filesDir/ladies_data enthält nach dem Import
  die originalen, relativ referenzierten Archivdateien.
- Die Datenübernahme verändert den Quellbestand nicht.
- Vor einer erneuten vollständigen Migration ist eine neue leere
  Android-App-Installation erforderlich. Vorher alle bereits neu
  erfassten Android-Daten separat sichern.

## Nutzung nach dem Import

Die Datensätze stehen der nativen Suche, Profilansicht, Entdecken, Favoriten und Karte zur Verfügung, soweit die jeweiligen Daten vorhanden sind. Gespeicherte Suchen bleiben erhalten; nicht ausführbare Legacy-Filter werden sichtbar gekennzeichnet ([SOURCE.md](SOURCE.md)). Ein importierter Datensatz bedeutet nicht automatisch, dass sämtliche früheren Bearbeitungsfunktionen in Android verfügbar sind ([ROADMAP.md](ROADMAP.md)). Kein Server, Python auf Android oder Backend im Heimnetz erforderlich.
