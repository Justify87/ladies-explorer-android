# UI- und Designvertrag

Meilensteinübergreifende UI-/UX-Autorität. Modern, ruhig, nativ, bildorientiert, kompakt und informationsreich. Bestehendes Branding und Material 3 verwenden; neue Funktionen bevorzugt mit vorhandenen Mustern gestalten. Fachliche Anforderungen: [PRODUCT](PRODUCT.md); technische Umsetzung und Grenzen: [ARCHITECTURE](ARCHITECTURE.md).

## Visuelle Identität

Maßgebliche konkrete Farb-, Shape- und Typografie-Tokens: [LadiesTheme.kt](../app/src/main/java/de/justify87/ladiesexplorer/ui/LadiesTheme.kt). Markentypisches Pink, helle/neutrale Oberflächen, passende dunkle Palette, semantische Status-/Fehlerfarben. System-Dunkelmodus gleichwertig unterstützen; dynamische Systemfarben nicht standardmäßig. Vorhandene MaterialTheme-Rollen bevorzugen, keine parallele Token-Bibliothek. Lesbare Hierarchie, wenige Akzente, weiche Rundungen, Abstände am Kontext ausrichten (Legacy 4/7/10/14/18/24 dp als Orientierung). Kontrast, Font Scale, Insets und Bedienbarkeit haben Vorrang.

## Komponenten und Interaktion

- Pro Handlungskontext erkennbare Hauptaktion, sekundäre Aktionen zurücknehmen. Chips für Filter/kleine Auswahlen, nicht für jeden Datenwert. Kompakte Listenzeilen mit Thumbnail, Identität, Status und direkten Aktionen; Detailinformationen progressiv offenlegen.
- Fehler, unbekannte Werte, Loading, Empty, Teilresultate und Retry klar und erreichbar darstellen; Status nicht nur durch Farbe. Keine künstlichen Handyrahmen, Systemleisten, Dummy-Features oder erfundenen Profilinformationen.
- Funktionale, unterbrechbare Animationen; reduzierte Bewegung/Systemanimationseinstellungen berücksichtigen. Native Semantik, TalkBack, Tastatur/DPAD, Fokus und Touch-Ziele ≥48 dp. 

## Navigation und Bildschirmmuster

- Vier Root-Ziele Entdecken/Suche/Gespeichert/Einstellungen. Kontextabhängiger Header mit Branding, Alle/Jetzt/Heute in Entdecken, Suchsymbol bzw. gespeicherte Suchen mit Neu-Indikator in Suche. Swipe/Grid/Karte als Ansichtswechsel. Standalone-Profil ohne Root-Navigation, Back/Mehr im festen Header.
- Grid: zwei Spalten im üblichen Smartphone-Fenster, Breite/Höhe ca. 0,77, rund 23 dp als Richtwert, Cover, Dunkelgradient unten, Name/Alter und direktes Like oben rechts; Platzhalter bei fehlenden Medien.
- Suche: globaler Filtertext, Ort/Radius, Top 5, Verfügbarkeit, Alter (dual range), Treffpunkt, einklappbare Kategorien/Eigenschaften (inkl. Größe)/Services/weitere Optionen; Footer Reset + Suche mit Count/Status; Gruppenstatus und Scroll erhalten.
- Gespeichert: Tabs, Counts, Sortierung, Statusprüfung und aufklappbare Watchlist gemäß [PRODUCT](PRODUCT.md#gespeicherte-personen). Kompakte Zeilen mit Thumbnail, Name/Alter, Ort, Status/Aktivitätszeit, Änderungsbadge/zusätzliche Anzeigen, Herz/Mehr. Gespeicherte Suchen als eigene Übersicht mit Neu-Indikator, Delta, Editor und Ergebnisgrid.
- Karte: große Kartenfläche, zurückhaltende Controls, tatsächlichen Datenumfang und ungefähre Standorte kennzeichnen. Marker/Cluster, gemeinsame Auswahl gleich positionierter Profile, bestehende Profilnavigation, Kamera/Auswahl bei Tab- und Profilwechsel erhalten, Fehler/Retry, Auflösungsfortschritt/Abbruch sichtbar; gecachte Zentren bleiben während Auflösung sichtbar. Einstellungen: schlichte funktionale Gruppen. Neue Funktionen nutzen diese Muster.

## Profile und Medien

Priorität: (1) korrekte Identität/Entscheidung, keine falsche Aktion oder Datenverluste; (2) eindeutige Tap-/Swipe-/Scroll-Interaktion; (3) große Galerie und Orientierung; (4) kompakte zugängliche Details; (5) Animation/Dekoration. Keine neuen Felder nur fürs Layout; Datenumfang und Evidenz gemäß [PRODUCT](PRODUCT.md#profile-medien-und-kontakte).

### UX-Vertrag — zwei Zustände, ein Scrollfluss

**A — Swipe-Ausgangszustand (default):** kompakte Marken-/Such-/Filterleiste, eine eindeutig gekennzeichnete aktuelle Suche/Modus/Verfügbarkeit; großflächiges Foto unmittelbar darunter. Keine obere Galerie-Bedienleiste, Storysegmente, Zähler, permanente Pfeile oder Gestentutorials; Original/Fit im Profilmenü. Native seitliche Tap-Zonen, Video erhält mittiges Play/Pause. Name, Alter, Ort, explizite Verfügbarkeit/Status nur soweit bekannt als kontrastgesichertes Overlay am unteren Bildrand; bei fehlender Altersangabe weglassen statt schätzen. Ein kurzer Detailhinweis/Anschnitt zeigt Scrollbarkeit; keine vollständige Beschreibung im ersten Viewport erzwingen. Eine fixierte Decision Rail unmittelbar über Root-Navigation: Undo, Nein, Später, Gefällt (Nein/Gefällt visuell primär; Undo/Später sekundär), mindestens 48 dp Touch-Ziele; keine zusätzliche Like-/Dislike-Leiste im Inhalt. Favorit unabhängig, erreichbares separates Symbol/Option. Neue Karte beginnt in A; die alte Karte bleibt bis bestätigter Entscheidung bzw. eindeutigem Kontextwechsel sichtbar.

**B — heruntergescrollt (Details):** derselbe vertikale Scrollfluss, kein Modal, keine zweite Route und kein zweiter Content-Scroller. Swipe-Header/Filter scrollen aus oder kollabieren koordiniert mit dem Scrollen. Nach Verlassen der Identitätsüberlagerung kompakter Profilheader (Name/Alter, ggf. belegter Ort/Status), optional kleiner Thumbnail nur wenn ohne Platzverlust oder zusätzliche Ladezeit. Keine zweite dauerhaft sichtbare Identitätsüberschrift. Decision Rail bleibt erreichbar und benennt eindeutig das aktuell sichtbare Profil; Root-Navigation bleibt erhalten, keine zusätzliche Leiste. Primäre Swipe-Gesten nur auf Hero; in Details vertikal scrollen, über Buttons entscheiden. Zurückscrollen zum Hero stellt A und die gewählte Galerieposition wieder her. Tap auf kompakte Identität bzw. klarer, zugänglicher Zurück-zum-Foto-Shortcut scrollt zum Hero ohne neue Navigation. Header-Wechsel ohne Layoutsprung, Flackern, Scroll-Reset oder Interaktionsverlust; Status-/Fehler-/Abbruchanzeige darf nicht verschwinden, wenn sie gerade relevant ist. Keine erzwungene Snap-/Scroll-Animation; reduzierte Bewegung/Systemanimationseinstellungen respektieren. Standalone verwendet denselben Inhalt, Back statt Deck-Steuerung, keine Root-Navigation wie bisher, keine horizontale Profilentscheidung.

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

H = gesamte tatsächlich nutzbare Höhe zwischen Systemstatus-/Navigationsinsets einschließlich eigener App-Bar, Action Rail und Root-Navigation; W = nutzbare Fensterbreite. Messen an einem geladenen Normalprofil in Portrait, Startscroll, Font Scale 1, ohne IME/Fehler. Ziel Foto 70 % H, regulär mindestens 65 % vor Fallback, höchstens 72 % H soweit Controls passen; W 390–430 dp, H ≥780 dp. Bei W um 360 dp/H ≥680 dp Ziel ≥60 %. Hero-Anfang höchstens 14 % H (bei H=780 z.B. 109 dp), Ziel 12 %; 0–8 dp Seitenabstand. Header 48–56 dp, maximal eine zusätzliche kompakte Filter/Modus-Zeile, keine Doppel-Padding-Kette. Planungsbudget bei H=780: App-Bar 48 + Filterzeile 48 + Rail 56 + Root-Navigation 64 + Detailhinweis/Abstand 12 = 228 dp; Hero 546 dp (70 %) passt mit 6 dp Reserve. Dies sind Zielbudgets, nicht feste Material3-Höhen; tatsächlich gemessene Insets und Komponentenhöhen entscheiden. Verfügbare Hero-Höhe = H minus gemessene feste Leisten, notwendige Abstände und optionalen Detail-Peek; begrenze Wunschhöhe auf diese Höhe. Wenn ≥65 % unmöglich: zuerst dauerhaft sichtbare Sekundär-Controls zusammenfassen, doppelte Abstände entfernen, optionalen Peek reduzieren; danach begründeter adaptiver Fallback (kleine Fenster, Font Scale, Querformat, IME, Fehler); keine Unterschreitung durch willkürlichen Max-Height, abgeschnittene Aktionen oder verkleinerte Touch-Ziele. In Desktop-/Tablet-Fenstern sinnvoll begrenzte Inhaltsbreite oder adaptive Anordnung, kein neues Desktop-Feature. Hero-Cover als Default; vorhandener Fit-Vollbildmodus explizit, Video eingebettet, stumm, Autoplay/Loop bei sichtbarem Medium und gültigem Lifecycle; Medienfallback gemäß [PRODUCT](PRODUCT.md). Messwerte für Hero-Start/Höhe/Breite/Leisten, 360×740/390×844/430×932 dp und 600 dp, Font Scale 1/1.3/2, hell/dunkel, Rotation, Insets, IME, TalkBack/DPAD. Normalfall ist verbindliche Layoutanforderung.

### Informationshierarchie — progressive Offenlegung

Visuelle Hierarchie nach Relevanz für schnelle Profilentscheidung; keine Sammlung gleich gewichteter Karten. Scanbare kurze Labels und Werte statt redundanter Überschriften; Gruppenabstand 12–16 dp, innerhalb 4–8 dp, Außenabstand Details 12–16 dp als Richtwert, Touch-Ziele bleiben ≥48 dp; Material3-Typografie, klare Kontraste, Dark Mode. Keine durchgängigen dicken Kartenrahmen oder für jedes Merkmal eigene Pill. Ruhige Oberfläche, wenige Akzentfarben, Divider nur zur Trennung sinnvoller Gruppen. Werte nicht doppelt (z.B. Alter nur in Identität, nicht nochmals Info-Raster; wenn Overlay weg, kompakter Header). Wichtige Informationen ohne Pflicht-Tabwechsel sichtbar:

| Rang | Darstellung | Umfang/Regel |
|---|---|---|
| 0 | Identität/entscheidungsrelevanter Status im Hero bzw. kompakten Header | Name, Alter, Ort, angezeigter Quellenstatus/Verfügbarkeit mit Unsicherheit; Archiv-/Fehler-/Ownerwarnung sichtbar und nicht mit Aktivität verwechseln |
| 1 | Kompakte Übersicht | Tatsächlich vorhandene Öffnungszeiten/Verfügbarkeit und wichtige belegte Merkmale, responsive 1–2 Spalten; keine erfundene „jetzt verfügbar“-Ableitung |
| 2 | Beschreibung | Kurzer mehrzeiliger Auszug, „Mehr anzeigen“ wenn abgeschnitten; voller Originaltext zugänglich, kein KI-Resümee |
| 3 | Dienste/Filterbelege | Gruppierte Textzeilen oder wenige Chips, explizit Ja/Nein/Unbekannt; kompakte Zusammenfassung mit Zugang zu allen Werten, keine irreführende Verdichtung |
| 4 | Kontakt und externe Aktionen | Direkt zugängliche vorhandene Kontaktregeln/Telefon/WhatsApp/Original; Favorit separat, keine automatischen Aktionen |
| 5 | Analyse/Identitätskandidaten | Eingeklappt, vorhandene Evidenz und explizite Bestätigung; Evidenz gemäß PRODUCT |
| 6 | Meine Notiz | Eingeklappt; Entwurfs-/Konflikt-/Speicherhinweis bereits am Abschnittskopf; bestehende Speichern/Konfliktauflösung in-place |
| 7 | Verlauf und verknüpfte Anzeigen | Eingeklappt, Liste mit bestehenden Zeit-/Filter-/Vergleichsfunktionen, externe Original-URLs unverändert |

Kein Verlust tatsächlich verfügbarer Felder. Bei fehlenden optionalen Feldern kein leerer Kartenblock; fachlich relevante unbekannte Werte zeigen. Kritische Warnungen/Fehler, aktive Mutationen, Notizkonflikte und Loading/Retry nicht hinter Menüs verbergen. Kurze Abschnittstitel und semantische Überschriften; Ausklappen nur für die betreffende Sektion, übrige Scrollanker stabil. Bei langen Werten/Font Scale auf einspaltig wechseln und nicht abschneiden. Zwischen A und B keine Doppelroute, kein künstlicher Sheet-Handle. Filter: ausgeführter Ort/Radius, Verfügbarkeit, Modus, Quelle/visible/deck als kompakte Zusammenfassung bzw. beschriftete zugängliche Detailanzeige; aktive Suche vs lokaler Bestand immer erkennbar; Teilresultat/Radiuswarnung/Cancel beim Auftreten sichtbar. Replay/Reset/Refresh ins erreichbare Deckmenü, Undo direkt. Kein neuer Suchlauf durch Darstellung, Scroll oder Moduswechsel. Andere Screens nicht ohne Bedarf neu gestalten.

### Gesten und Präsentation

Seitliche native Tap-Zonen wechseln Medien; am Rand bleibt der Index unverändert. Horizontaler Swipe entscheidet nur auf dem Hero, nach eindeutig dominanter Achse und mindestens 25 % der Kartenbreite sowie 72 dp; kein Fling allein. Vertikal scrollen. Diagonale Mehrdeutigkeit, Long-Press vor Lock, Mehrfinger, Cancel, Back, Busy oder Kontextwechsel verhindern Entscheidung. Interaktive Kindziele (Play/Pause/Retry, Menü, Back, Vollbild, Editor, Kontakt, Rail) haben Vorrang. Nach Drag kein Tap, Feedback nur während Drag; bei Cancel, Writefehler oder unterschwelligem Drag zurückfedern. Bestätigter Exit erst nach gültiger Entscheidung. Standalone hat dieselbe Galerie ohne Swipe-Entscheidung.

Undo stellt die letzte noch gültige Medien-/Scrollposition und Sektionen wieder her, sonst kontrollierter Hero-Fallback. Back/Moduswechsel/Rotation im gültigen Kontext bewahren Position und Medien; neuer Kontext/Reset startet in A. Ein einziger Scrollfluss, keine zweite Detailroute. Bei sehr kleinen Fenstern oder hoher Schriftvergrößerung Identität außerhalb des Bildes und Rail-Aktionen im erreichbaren Menü; kritische Statusanzeige bleibt verfügbar. Video-Retry am Medium.

## Prüfung und Gestaltungsspielraum

Verbindlich sind bestätigte UX-Entscheidungen, Normalfall-Hero-Anforderungen mit adaptiven Ausnahmen, verständliche Zustände, zugängliche Aktionen und konsistente Markenidentität. Andere Maße, sekundäre Anordnung und Material-Komponenten sind Richtwerte bzw. autonome Entscheidungen. Bei geänderten Screens gezielt helle/dunkle Darstellung, 360/390/430 dp und größere Fenster (600 dp), Font Scale 1/1,3/2, Rotation, Insets/IME, TalkBack/DPAD, Back, schnelle Navigation und Fehlerfälle prüfen soweit relevant und verfügbar. Optionale native Geräteprüfung nach [Merge-Regel](ROADMAP.md#pflege); konkrete Belege im PR, keine privaten Bilder. Kein neuer Design- oder Teststack.
