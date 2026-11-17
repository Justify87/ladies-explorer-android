# Evidenz aus Browser-HAR-Archiven (11.10.2026)

Drei vom Anwender bereitgestellte Chrome-/Firefox-HAR-Archive wurden lokal
ausgewertet, um die M03-Suche und Medienparser anhand realer Requests zu prüfen.
Die Originalarchive sind privat und werden **nicht** in diesem Repository
abgelegt; insbesondere Cookie-Werte, HTTP-Headers mit Authentifizierung,
personenbezogene URLs, Bodydaten und Bilddateien werden nicht übernommen.

## Verifizierte Beobachtungen

- Die Browserseite zur Suche verwendet den Pfad
  `https://www.ladies.de/leipzig/sex-anzeigen` – der gleiche Pfadtyp wie
  die im Rust-Core generierte Such-URL. In der HAR-Aufzeichnung ist das
  die *Seitenmetadaten-URL*, kein mitgespeicherter HTML-Request.
- Die beiden weiteren Mitschnitte stammen von einzelnen
  `/sex-anzeigen/huren-hostessen/...`-Profilseiten.
- Insgesamt 379 Ressourceneinträge, davon 247 Bildrequests an
  `bilder1.ladies-cdn.de`. Bei Bildern sind sowohl
  `/Bilder/<Ziffern>/<Ziffern>-F1.JPG` als auch
  `/Bilder/<Ziffern>/<Ziffern>-A2.JPG`-ähnliche Pfade und
  Query-Parameter `h` als Revisionskennung zu sehen. Ein anonymisierter
  Parser-Regressionstest verifiziert diese Muster.
- Die Browser-Requests enthalten unter anderem die Cookie-Namen
  `cf_clearance`, `__cf_bm`, `notice_fsk18_shown` und
  `PHPSESSID`. Das zeigt, dass die Browseraufzeichnung mit einer
  bestehenden Website-/Cloudflare-/Altersfreigabe-Sitzung erstellt wurde.
  Es beweist **nicht**, dass der Android-Rust-Client dieselben Cookies
  erhalten kann oder dass ein bestimmter Schutzmechanismus den Fehler
  ausgelöst hat.

## Aussagegrenzen

Die Archive enthalten **keine** eigentlichen HTML-Dokumentantworten der
Such- oder Profilnavigation und **keine** JSON-Antwort des
`/api/.../sex-anzeigen`-Endpunkts. Deshalb lassen sich die aktuellen
`div.ladies-card`-Selektoren, `SearchConfig`-Felder und der konkrete
HTML-Fehler auf Android damit **nicht** beweisen oder widerlegen.

Für den Android-Befund wurde der HTTP-Client an die zuvor vorhandene
Browser-Session-Semantik angenähert (Cookie-Speicher und begrenzte
Weiterleitungen innerhalb der Site). Zusätzlich enthält die Suchfehlermeldung
nun nichtgeheime Strukturdiagnostik (Dokumenttitel, Länge,
Karten-/Linkanzahl und erkannte Challenge-Indikatoren). Cookies oder
HAR-Tokens werden niemals aus den Mitschnitten in die App kopiert.

## Erforderliche Live-Abnahme

Auf dem Gerät mit der neuen Debug-APK eine Suche ausführen. Bei erneuter
Fehlermeldung deren Diagnose-Text kopieren und mit dem Titel/Typ vergleichen:
Bot-/Altersseite, Redirect auf eine andere URL oder geänderte Kartenstruktur
erfordern unterschiedliche gezielte Lösungen. Ein erfolgreiches Rust-CI
allein kann die erfolgreiche Live-Website-Suche nicht garantieren.
