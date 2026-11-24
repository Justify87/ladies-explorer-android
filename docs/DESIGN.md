# UI- und Designvertrag

Meilensteinübergreifende UI-/UX-Autorität. Modern, ruhig, nativ, bildorientiert, kompakt und informationsreich. Bestehendes Branding und Material 3 verwenden; neue Funktionen bevorzugt mit vorhandenen Mustern gestalten. Fachliche Anforderungen: [PRODUCT](PRODUCT.md); technische Umsetzung und Grenzen: [ARCHITECTURE](ARCHITECTURE.md).

## Visuelle Identität

**Verbindlich:**
- Bestehendes Branding und Material 3 verwenden. Markentypisches Pink, helle/neutrale Oberflächen, passende dunkle Palette und semantische Status-/Fehlerfarben.
- Maßgebliche Farb-, Shape- und Typografie-Tokens: [LadiesTheme.kt](../app/src/main/java/de/justify87/ladiesexplorer/ui/LadiesTheme.kt).
- System-Dunkelmodus gleichwertig unterstützen; dynamische Systemfarben nicht standardmäßig.
- Vorhandene `MaterialTheme`-Rollen bevorzugen, keine parallele Token-Bibliothek.
- Lesbare Informationshierarchie, Kontrast, Font Scale, Insets und Bedienbarkeit haben Vorrang.

**Richtwerte:** Wenige Akzente, weiche Rundungen und kontextabhängige Abstände; Legacy 4/7/10/14/18/24 dp sind eine Orientierung, keine fixen Tokens.

## Komponenten und Interaktion

- Pro Handlungskontext eine erkennbare Hauptaktion; sekundäre Aktionen zurücknehmen.
- Chips für Filter/kleine Auswahlen, nicht für jeden Datenwert. Kompakte Listenzeilen mit Thumbnail, Identität, Status und direkten Aktionen; Details progressiv offenlegen.
- Fehler, unbekannte Werte, Loading, Empty, Teilresultate und Retry erkennbar und erreichbar darstellen; Status nie nur über Farbe.
- Keine künstlichen Handyrahmen, Systemleisten, Dummy-Features oder erfundenen Profilinformationen.
- Funktionale, unterbrechbare Animationen. Reduzierte Bewegung und Systemanimationseinstellungen berücksichtigen.
- Native Semantik, TalkBack, Tastatur/DPAD und Fokus; **Touch-Ziele mindestens 48 dp**.

## Navigation und Bildschirmmuster

### Root-Navigation und Entdecken

- Vier Root-Ziele: Entdecken, Suche, Gespeichert, Einstellungen.
- Kontextabhängiger Header mit Branding, Alle/Jetzt/Heute in Entdecken und Suchsymbol beziehungsweise gespeicherten Suchen mit Neu-Indikator in Suche.
- Swipe/Grid/Karte sind Ansichtswechsel. Standalone-Profil ohne Root-Navigation, mit Back/Mehr im festen Header.

### Grid

- Gemeinsame Profilkarte für Entdecken und gespeicherte Suchergebnisse; zwei Spalten im üblichen Smartphone-Fenster.
- Seitenverhältnis Breite/Höhe circa 0,77 und Rundung circa 23 dp als **Richtwerte**.
- Cover, Dunkelgradient am unteren Bildrand, Name/Alter und Platzhalter bei fehlenden Medien.
- Direktes Like oben rechts in Entdecken; übrige Aktionen kontextabhängig.

### Suche

- Globaler Filtertext, Ort/Radius, Top 5, Verfügbarkeit, Alter (dual range), Treffpunkt, einklappbare Kategorien/Eigenschaften (einschließlich Größe), Services und weitere Optionen.
- Footer mit Reset und Suche samt Count/Status; Gruppenstatus und Scrollposition erhalten.
- Letzter Suchlauf bleibt unabhängig vom Filterentwurf zugänglich: Liste der lokal sichtbaren Run-Treffer einschließlich der nur im Grid temporär ausgeblendeten Likes, mit Profilöffnung und eindeutiger Rückkehr zum Filter.

### Gespeichert

- Tabs, Counts, Sortierung, Statusprüfung und aufklappbare Watchlist gemäß [PRODUCT](PRODUCT.md#gespeicherte-personen).
- Kompakte Zeilen: Thumbnail, Name/Alter, Ort, Anzeigenstatus, eindeutige Sichtungs-/Änderungs-/Statusprüfzeitpunkte, Anzeigenanzahl sowie Herz/Mehr. Lokales Neuladen von der Quellenstatusprüfung unterscheiden.
- Gespeicherte Suchen: eigene Übersicht mit Neu-Indikator, Delta, Editor und Ergebnisgrid.

### Karte und Einstellungen

- Große Kartenfläche, zurückhaltende Controls; Datenumfang und ungefähre Standorte kennzeichnen.
- Marker/Cluster und gemeinsame Auswahl gleich positionierter Profile. Bestehende Profilnavigation sowie Kamera/Auswahl bei Tab-/Profilwechseln erhalten.
- Fehler/Retry, Auflösungsfortschritt und Abbruch sichtbar; gecachte Zentren bleiben während der Auflösung sichtbar.
- Einstellungen in schlichten funktionalen Gruppen. Neue Funktionen verwenden vorhandene Muster.

## Profile und Medien

Priorität: (1) korrekte Identität/Entscheidung, keine falsche Aktion oder Datenverluste; (2) eindeutige Tap-/Swipe-/Scroll-Interaktion; (3) große Galerie und Orientierung; (4) kompakte zugängliche Details; (5) Animation/Dekoration. Keine neuen Felder nur fürs Layout; Datenumfang und Evidenz gemäß [PRODUCT](PRODUCT.md#profile-medien-und-kontakte).

### UX-Vertrag — zwei Zustände, ein Scrollfluss

**A — Swipe-Ausgangszustand (Default):**

- Kompakte Marken-/Such-/Filterleiste mit eindeutig gekennzeichneter aktueller Suche, Modus und Verfügbarkeit; großflächiges Foto unmittelbar darunter.
- Keine obere Galerie-Bedienleiste, Storysegmente, Zähler, permanente Pfeile oder Gestentutorials. Original/Fit im Profilmenü.
- Native seitliche Tap-Zonen; Video erhält mittiges Play/Pause.
- Name, Alter, Ort und explizite Verfügbarkeit/Status nur soweit bekannt, kontrastgesichert am unteren Bildrand. Fehlendes Alter nicht schätzen.
- Kurzer Detailhinweis/Anschnitt zeigt Scrollbarkeit; keine vollständige Beschreibung im ersten Viewport erzwingen.
- Fixierte Decision Rail direkt über Root-Navigation: Undo, Nein, Später, Gefällt; Nein/Gefällt visuell primär, Undo/Später sekundär, alle Ziele mindestens 48 dp. Keine zusätzliche Like-/Dislike-Leiste im Inhalt.
- Favorit unabhängig über separates erreichbares Symbol oder Option.
- Neue Karte startet in A. Alte Karte bleibt bis bestätigter Entscheidung oder eindeutigem Kontextwechsel sichtbar.

**B — heruntergescrollt (Details):**

- **Derselbe** vertikale Scrollfluss: kein Modal, zweite Route oder zweiter Content-Scroller. Swipe-Header/Filter scrollen aus oder kollabieren koordiniert.
- Nach Verlassen der Identitätsüberlagerung kompakter Profilheader mit Name/Alter und gegebenenfalls belegtem Ort/Status. Kleiner Thumbnail nur optional ohne Platzverlust/Ladezeit; keine zweite dauerhafte Identitätsüberschrift.
- Decision Rail bleibt erreichbar und nennt das aktuell sichtbare Profil; Root-Navigation bleibt, keine zusätzliche Leiste.
- Primärer Profil-Swipe nur am Hero; in Details vertikal scrollen und per Buttons entscheiden.
- Zurückscrollen zum Hero stellt A samt gewählter Galerieposition wieder her. Tap auf kompakte Identität oder zugänglicher „Zum Foto“-Shortcut scrollt ohne neue Navigation zurück.
- Headerwechsel ohne Layoutsprung, Flackern, Scroll-Reset oder Interaktionsverlust. Relevante Status-/Fehler-/Abbruchanzeige bleibt sichtbar.
- Keine erzwungene Snap-/Scroll-Animation; reduzierte Bewegung/Systemanimationseinstellungen beachten.
- Standalone nutzt denselben Inhalt mit Back statt Deck-Steuerung, ohne Root-Navigation und ohne horizontale Profilentscheidung.

Schematische Abnahme (nicht maßstabsgetreue Figma-Spezifikation):
```text
A: Entdecken/Filter/Modus (kompakt)     B: Name · belegter Kurzstatus (kompakt)
   ┌──────────────────────────────┐       Verfügbarkeit · wichtige Fakten
   │                              │       Merkmale · Beschreibung
   │                              │       Dienste · Kontakt
   │        GROSSES FOTO          │       ▸ Analyse / Identität
   │ tap ←              → tap     │       ▸ Meine Notiz • falls Entwurf
   │                              │       ▸ Verlauf / verknüpfte Anzeigen
   │ Name, Alter · Ort/Status     │
   └──────────────────────────────┘
   ↓ Profilinfos                         ↑ Zum Foto (zugänglich)
   Undo | Nein | Später | Gefällt        Undo | Nein | Später | Gefällt
   Entdecken · Suche · Gesp. · Einst.    Entdecken · Suche · Gesp. · Einst.
```

### Messbarer Flächenvertrag und responsive Darstellung

**Messbasis:** `H` ist die tatsächlich nutzbare Gesamthöhe zwischen Systemstatus-/Navigationsinsets **einschließlich** eigener App-Bar, Action Rail und Root-Navigation; `W` ist die nutzbare Fensterbreite. An einem geladenen Normalprofil in Portrait, Startscroll, Font Scale 1, ohne IME/Fehler messen.

| Geltungsbereich | Hero-Fotohöhe / Startposition | Verbindlichkeit |
| --- | --- | --- |
| `W` 390–430 dp, `H` ≥780 dp | Ziel 70 % `H`, regulär mindestens 65 % vor Fallback, höchstens 72 % `H` soweit Controls passen | Normalfall-Anforderung |
| `W` um 360 dp, `H` ≥680 dp | Ziel mindestens 60 % `H` | Normalfall-Ziel für kleineres Fenster |
| Hero-Anfang bei `H` 780 dp (Beispiel 109 dp) | Maximal 14 % `H`, Ziel 12 % `H` | Normalfall-Anforderung und Ziel |
| Hero-Seitenabstand | 0–8 dp | Layout-Ziel |
| Header und zusätzliche Filter-/Moduszeile | Headerhöhe 48–56 dp als Richtwert | **Verbindlich:** maximal eine zusätzliche kompakte Zeile, keine doppelte Padding-Kette |

**Planungsbudget bei `H=780`:** App-Bar 48 + Filterzeile 48 + Rail 56 + Root-Navigation 64 + Detailhinweis/Abstand 12 = 228 dp; Hero 546 dp (70 %) mit 6 dp Reserve. Das ist **kein** Satz fester Material-3-Höhen. Tatsächlich gemessene Insets und Komponenten entscheiden.

**Berechnung:** Verfügbare Hero-Höhe = `H` minus gemessene feste Leisten, notwendige Abstände und optionalen Detail-Peek. Wunschhöhe auf diesen Platz begrenzen.

**Fallback, wenn 65 % nicht erreichbar sind – in dieser Reihenfolge:**
1. Dauerhaft sichtbare sekundäre Controls zusammenfassen.
2. Doppelte Abstände entfernen.
3. Optionalen Detail-Peek reduzieren.
4. Erst danach begründeten adaptiven Fallback zulassen (kleines Fenster, Font Scale, Querformat, IME oder Fehler).

Keine willkürliche Max-Height-Unterschreitung, abgeschnittene Aktionen oder verkleinerte Touch-Ziele. Für Desktop-/Tablet-Fenster sinnvoll begrenzte Inhaltsbreite oder adaptive Anordnung; kein neues Desktop-Feature.

**Medien:** Hero-Cover als Default; vorhandener Fit-Vollbildmodus explizit. Video eingebettet, stumm, mit Autoplay/Loop nur bei sichtbarem Medium und gültigem Lifecycle. Medienfallback gemäß [PRODUCT](PRODUCT.md).

**Abnahme:** Hero-Start, -Höhe, -Breite und Leisten messen; 360×740/390×844/430×932 dp und 600 dp, Font Scale 1/1.3/2, hell/dunkel, Rotation, Insets, IME, TalkBack/DPAD. Normalfall ist verbindliche Layoutanforderung.

### Informationshierarchie — progressive Offenlegung

Visuelle Hierarchie nach Relevanz für schnelle Profilentscheidung; keine Sammlung gleich gewichteter Karten. Scanbare kurze Labels und Werte statt redundanter Überschriften; Gruppenabstand 12–16 dp, innerhalb 4–8 dp, Außenabstand Details 12–16 dp als Richtwert, Touch-Ziele bleiben ≥48 dp; Material3-Typografie, klare Kontraste, Dark Mode. Keine durchgängigen dicken Kartenrahmen oder für jedes Merkmal eigene Pill. Ruhige Oberfläche, wenige Akzentfarben, Divider nur zur Trennung sinnvoller Gruppen. Werte nicht doppelt (z.B. Alter nur in Identität, nicht nochmals Info-Raster; wenn Overlay weg, kompakter Header). Wichtige Informationen ohne Pflicht-Tabwechsel sichtbar:

| Rang | Darstellung | Umfang/Regel |
|---|---|---|
| 0 | Identität/entscheidungsrelevanter Status im Hero bzw. kompakten Header | Name, Alter, Ort, angezeigter Quellenstatus/Verfügbarkeit mit Unsicherheit; Archiv-/Fehler-/Ownerwarnung sichtbar und nicht mit Aktivität verwechseln |
| 1 | Kompakte Übersicht | Tatsächlich vorhandene Öffnungszeiten/Verfügbarkeit, Anzeigenanzahl der Person und wichtige belegte Merkmale, responsive 1–2 Spalten; keine erfundene „jetzt verfügbar“-Ableitung. Anzeigenanzahl nur bei fehlender Personenzusammenfassung aus der Analyse ergänzen. |
| 2 | Beschreibung | Kurzer mehrzeiliger Auszug, „Mehr anzeigen“ wenn abgeschnitten; voller Originaltext zugänglich, kein KI-Resümee |
| 3 | Dienste/Filterbelege | Gruppierte Textzeilen oder wenige Chips, explizit Ja/Nein/Unbekannt; kompakte Zusammenfassung mit Zugang zu allen Werten, keine irreführende Verdichtung |
| 4 | Kontakt und externe Aktionen | Direkt zugängliche vorhandene Kontaktregeln/Telefon/WhatsApp/Original; Favorit separat, keine automatischen Aktionen |
| 5 | Analyse/Identitätskandidaten | Eingeklappt, vorhandene Evidenz und explizite Bestätigung; Evidenz gemäß PRODUCT |
| 6 | Meine Notiz | Eingeklappt; Entwurfs-/Konflikt-/Speicherhinweis bereits am Abschnittskopf; bestehende Speichern/Konfliktauflösung in-place |
| 7 | Verlauf und verknüpfte Anzeigen | Eingeklappt, Liste mit bestehenden Zeit-/Filter-/Vergleichsfunktionen, externe Original-URLs unverändert |

Kein Verlust tatsächlich verfügbarer Felder. Bei fehlenden optionalen Feldern kein leerer Kartenblock; fachlich relevante unbekannte Werte zeigen. Kritische Warnungen/Fehler, aktive Mutationen, Notizkonflikte und Loading/Retry nicht hinter Menüs verbergen. Kurze Abschnittstitel und semantische Überschriften; Ausklappen nur für die betreffende Sektion, übrige Scrollanker stabil. Bei langen Werten/Font Scale auf einspaltig wechseln und nicht abschneiden. Zwischen A und B keine Doppelroute, kein künstlicher Sheet-Handle. Filter: ausgeführter Ort/Radius, Verfügbarkeit, Modus, Quelle/visible/deck als kompakte Zusammenfassung bzw. beschriftete zugängliche Detailanzeige; aktive Suche vs lokaler Bestand immer erkennbar; Teilresultat/Radiuswarnung/Cancel beim Auftreten sichtbar. Replay/Reset/Refresh ins erreichbare Deckmenü, Undo direkt. Kein neuer Suchlauf durch Darstellung, Scroll oder Moduswechsel. Andere Screens nicht ohne Bedarf neu gestalten.

### Gesten und Präsentation

**Galerie und Entscheidung:**
- Seitliche native Tap-Zonen wechseln Medien; am Rand bleibt der Index unverändert.
- Horizontaler Swipe entscheidet **nur am Hero**, bei eindeutig dominanter Achse und **mindestens 25 % Kartenbreite sowie 72 dp**; Fling allein genügt nicht. Vertikal scrollen.
- Diagonale Mehrdeutigkeit, Long-Press vor Lock, Mehrfinger, Cancel, Back, Busy oder Kontextwechsel verhindern die Entscheidung.
- Interaktive Kindziele (Play/Pause/Retry, Menü, Back, Vollbild, Editor, Kontakt, Rail) haben Vorrang.
- Nach Drag kein Tap; Feedback nur während Drag. Bei Cancel, Writefehler oder unterschwelligem Drag zurückfedern; bestätigter Exit erst nach gültiger Entscheidung.
- Standalone verwendet dieselbe Galerie **ohne** Swipe-Entscheidung.

**Wiederherstellung und kleiner Viewport:**
- Undo stellt die letzte **noch gültige** Medien-/Scrollposition und Sektionen wieder her; sonst kontrollierter Hero-Fallback.
- Back, Moduswechsel oder Rotation bewahren Position und Medien im gültigen Kontext. Neuer Kontext oder Reset startet in A.
- Ein Scrollfluss, keine zweite Detailroute.
- Bei sehr kleinen Fenstern/hoher Schriftvergrößerung Identität außerhalb des Bildes und Rail-Aktionen im erreichbaren Menü; kritische Statusanzeige bleibt verfügbar.
- Video-Retry am Medium.

## Prüfung und Gestaltungsspielraum

Verbindlich sind bestätigte UX-Entscheidungen, Normalfall-Hero-Anforderungen mit adaptiven Ausnahmen, verständliche Zustände, zugängliche Aktionen und konsistente Markenidentität. Andere Maße, sekundäre Anordnung und Material-Komponenten sind Richtwerte bzw. autonome Entscheidungen. Bei geänderten Screens gezielt helle/dunkle Darstellung, 360/390/430 dp und größere Fenster (600 dp), Font Scale 1/1,3/2, Rotation, Insets/IME, TalkBack/DPAD, Back, schnelle Navigation und Fehlerfälle prüfen soweit relevant und verfügbar. Optionale native Geräteprüfung nach [Merge-Regel](ROADMAP.md#pflege); konkrete Belege im PR, keine privaten Bilder. Kein neuer Design- oder Teststack.
