# Ladies Explorer – browserbasierte Designreferenz v3

**Zweck:** Offline bedienbare, historische Illustration der visuellen Ausgangsrichtung für das **native** Jetpack-Compose-Redesign. Sie zeigt nicht den aktuellen Pure-Vertrag; abweichende native Shapes, Maße, Icons und Interaktionshierarchie richten sich nach DESIGN. Dies ist **keine WebView-App** und enthält weder echte Profile noch eine echte Datenbank, Quellzugriffe, Konto- oder Kommunikationsfunktionen.

## Start

`index.html` direkt in einem modernen Browser öffnen. HTML, CSS, JavaScript, lokale Illustrationskarte und ausschließlich synthetische Erwachsenen-Porträts liegen in diesem Verzeichnis. Kein Build, CDN oder Backend erforderlich. Falls der jeweilige Browser den direkten Dateizugriff beschränkt, kann alternativ ein beliebiger statischer lokaler Dateiserver verwendet werden. Er ist weder Teil noch Voraussetzung der fertigen Android-Anwendung.

## Oberfläche

Es gibt vier Root-Ziele (Entdecken, Suche, Gespeichert, Einstellungen); Entdecken schaltet zwischen Swipe, Grid und Karte um. Profilaufrufe öffnen eine eigenständige Ansicht ohne Root-Navigation. Die Hauptaktionen, Galerie, Favoriten/Like, Undo, Suchfilter, gespeicherte Suchen, Notizen und illustrative Standortauflösung sind lokal bedienbar.

**Meine Begegnungen** und **Manuelle Angebotspreise** besitzen lokale exemplarische Bearbeitungsdialoge. Diese Dialoge demonstrieren die Struktur, **nicht** das vollständige native Fachmodell, CAS-Verhalten, Identitätsprüfung oder Persistenz. Insbesondere die vereinfachten Leistungszeilen sind keine fachliche Spezifikation.

Die Karte enthält lediglich eine schematische Illustration mit ungefähren Marken; keine Geokodierung. Kontakte werden ausdrücklich nicht geöffnet. Ein ausgeschalteter bzw. nicht verbundener Datenbank-/HTTP-Status wird nicht als erfolgreiche Verbindung ausgegeben. Jede Interaktion läuft im flüchtigen Browser-Speicher; Reload stellt die Ausgangslage wieder her.

## Übernahmegrenze

Visuelle Hierarchie, Tokens, Interaktionsoberflächen, States und Komponentenzuschnitte sind nur insoweit übertragbar, wie sie dem aktuellen DESIGN-Vertrag entsprechen. `.reference-stage`, `.phone`, `demo-data.js`, SVG-Demokarte, lokale Simulationsantworten und JavaScript-State **nicht nach Android portieren**. Bestehende `LadiesTheme`, `LadiesApp`, `MainViewModel`, `ProfileFlow`, `CoreStore` und die nativen Verträge sind maßgeblich.

Maßgebliche UI-/UX-Regeln stehen ausschließlich in [`docs/DESIGN.md`](../docs/DESIGN.md). Die Referenz ist kein aktueller Abnahmebeleg und keine Fach- oder Architekturautorität. Aufgaben: [`docs/ROADMAP.md`](../docs/ROADMAP.md).

## Dateien

- `index.html`: Vorschau-Einstieg; ausschließlich `#app` entspricht der App-Inhaltsfläche.
- `styles.css`: gemeinsame Design-Tokens und semantisch benannte CSS-Komponenten; inklusive responsiver und Fokusregeln.
- `app.js`: Renderfunktionen und lokale Interaktionen; keine Frameworks und keine echten Netzwerkanfragen.
- `demo-data.js`: acht erfundene volljährige Profile, ausschließlich Beispielwerte.
- `assets/profiles/`: generierte Porträtdateien; `assets/leipzig-map.svg`: schematische Ortsillustration.

Eine plattformübliche Systemschrift wird offline verwendet; es liegen keine Drittanbieter-Webfont-Dateien im Paket. Die monochromen Icons sind lokal definierte SVG-Pfade. Keine externen Bibliotheken oder automatischen Quellenkontakte.

## Herkunft und Nutzungsgrenzen

Die Profilbilder sind synthetische, ausschließlich für die Referenz generierte Portraits erwachsener fiktiver Personen. Die Karte ist eine lokal gezeichnete Illustration, kein Kartendienst. Keine privaten Originalprofile/Schlüssel oder URL-basierte Bilder; keine Webfonts oder CDN-Abhängigkeiten. Für App-Lizenzen und Drittanbieterbestandteile weiterhin Repository-`NOTICE` und L01 beachten.
