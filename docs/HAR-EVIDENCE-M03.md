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

## Erweiterung: aktuelles HAR vom 17.11.2026, 23:32 Uhr

**Dieses vierte Archiv ist anders als die Oktober-Archive:** Es enthält
411 Requests und die echten HTTP-200-Antworten für die Startseite,
die Leipziger Suchseite, die Such-API-Seiten 2–4 und ein Profil.
Die Suchseite hat ca. 490 KB HTML. Ihre 30 \`div.ladies-card\`-Elemente
enthalten 28 \`a.card-link[data-ref-id]\`-Links, davon 27 zu
\`/sex-anzeigen/...\`; ein weiterer gehört zu einer anderen Rubrik.
Die HTML-Suchkonfiguration meldet \`totalPages=8\`,
\`advertCount=203\` und \`distanceSearched=20\`.

Die API-Antworten von Seiten 2–4 sind JSON-Objekte mit
\`content\` (HTML-Karten), \`pagination.totalPages\`,
\`advertCount\` und \`distanceSearched\` (als String).
Die bestehenden Rust-Selektoren stimmen mit den aufgezeichneten
Kartenklassen überein. **Ein globaler Selektorwechsel ist nicht begründet.**

Die Orts-API liefert ein JSON-Array mit Schlüsseln \`Name\`,
\`Slug\`, \`Type\` und weiteren Standortattributen.
Die Rust-Implementierung nutzte zuvor nur kleingeschriebene
Schlüssel und fiel deshalb trotz gültiger Ortsantwort auf eine
selbstberechnete Slug zurück. Der Resolver liest die Schlüssel nun
wie die alte Web-App case-insensitiv.

Wenn die HTML-Suche keine erkennbaren Karten enthält, kann Rust
jetzt die **gleiche Quell-Such-API** für Seite 1 benutzen, die
beim Browser für Folgeseiten nachgewiesen ist. Die API-Seite 1 selbst
war **nicht** Bestandteil des HAR; ihr Format wird ausdrücklich
als Hypothese getestet, abgeleitet vom Vertrag der Seiten 2–4.
Radius, gültige Karten und Seiteninformationen werden überprüft;
eine Prüfseite darf weder als erfolgreiche leere Suche noch als
bestätigter Suchradius gespeichert werden.

Der Browser hatte **bereits bei der ersten aufgezeichneten Anfrage**
Sitzungs-, Altersfreigabe- und Cloudflare-Cookies. Keiner der
beobachteten erfolgreichen Requests belegt, dass eine leere
Rust-Cookie-Jar dieselbe Freigabe eigenständig erhält. Das bleibt
der zentrale Unterschied der Browser- und Android-Sitzungen.

### Sichere Testbasis

Nur anonymisierte, aus Struktur und Datenformen rekonstruierte
kleine Rust-Fixtures werden committed, **keine HAR-Rohdaten**,
keine Originalprofile, vollständigen URLs, Cookiewerte oder Tokens.
Der reale DOM-Nachweis wurde anhand der privaten Datei lokal
ausgewertet, ohne sie zu verteilen.

**Offene Abnahme:** Der Android-Client muss mit der neuen APK erneut
einen Leipziger Suchlauf absolvieren. Wenn beide Antwortformen
durch eine Zugangskontrolle ersetzt werden, ist dies nicht mit
weiteren HTML-Selektoren zu beheben. Die sichtbare Strukturdiagnose
begründet dann die nächste Entscheidung.
