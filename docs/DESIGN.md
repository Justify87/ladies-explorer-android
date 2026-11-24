# UI- und Designvertrag

Dieser Vertrag ist die maßgebliche UI-/UX-Sollbeschreibung für die native Android-App. Der lokale [Designprototyp](../design-referenz/README.md) veranschaulicht Gestaltung und Interaktionen, ist aber weder WebView-Vorlage noch Produkt- oder Architekturvertrag. Fachfunktionen: [PRODUCT](PRODUCT.md); technische Grenzen: [ARCHITECTURE](ARCHITECTURE.md); Aufgabenstatus: [ROADMAP](ROADMAP.md).

**Designentscheidung (24.11.2026):** Dunkles Orange-Design gemäß v3-Referenz ersetzt bisheriges Pink und frühere widersprüchliche visuelle Werte. Gültige fachliche und barrierefreie Verträge bleiben bestehen. Gleichwertiger nativer Light Mode ist verpflichtend und wird in M08.1 gestaltet; die HTML-Referenz zeigt allein Dark Mode.

## 1. Quellen, Autorität und verbindlicher Geltungsbereich

**Priorität:** (1) ausdrücklich bestätigte Nutzerentscheidung; (2) `docs/PRODUCT.md` für Fachsemantik und Produktgrenzen; (3) `docs/ARCHITECTURE.md` für technische Invarianten, Lifecycle und Datenhoheit; (4) `docs/DESIGN.md` für UI/UX; (5) `design-referenz/` als ergänzende **visuelle und interaktive** Referenz; (6) vorhandene, hiervon abweichende visuelle Implementierung. Eine Demo-Interaktion überschreibt niemals Produkt- oder Architekturvorgaben.

**[MUSS]** Die gemeinsame dunkle, ruhige Gestaltung mit Orange als Hauptakzent ersetzt die bisherige verbindliche **pinke** Markenfarbe, ihre inkonsistenten visuellen Farb-/Shape-Vorgaben und die verstreute alte Abstandsleiter. Material 3 Expressive und `MaterialTheme`-Rollen **beibehalten**. `LadiesTheme.kt` ist nach der M08.1-Implementierung die konkrete native Tokenquelle; die folgenden CSS-Variablen beschreiben Sollwerte zur Übersetzung, keine parallele Android-Tokenbibliothek.

**[MUSS]** Bereits gültige UI- und Fachverträge bleiben: vier Root-Ziele; ein gemeinsamer Profilfluss für Swipe/Standalone; keine zweiten vertikalen Scroller; Galerie-Tapzonen, Video/Play-Priorität, Hero-Swipe ausschließlich bei dominant horizontaler Geste und Schwellwert ≥max(72 dp, 25 % Kartenbreite); saubere Cancel-/Undo-/Owner-/Lifecycle-Verträge; M06-Begegnungen und Preislisten; separate Personennotiz; Suche/Resultat/Preview-Trennung; SavedSearch-Revision/Quittierung; Karte als lokaler Gesamtbestand; Diagnosegrenzen; 48-dp-Touchziel; helle und dunkle Darstellung.

**Begriffe:** `[MUSS]` = später verbindlich; `[RICHTWERT]` = nativ zu messen/abzugleichen; `[DEMO]` = nicht normativ, ausschließlich Abbildung. Nicht stillschweigend aus Mockup-Bildartefakten, erfundenen Orten, Namen oder Zählerständen weitere Produktfeatures ableiten.

## 2. Farbrollen und Typografie

Maßgebliche CSS-Quelle: [`../design-referenz/styles.css`](../design-referenz/styles.css), `:root`. Semantische Rollen und Startwerte:

| Rolle | CSS-Token | Startwert | Geplante Material-3-Abbildung |
| --- | --- | --- | --- |
| App-Hintergrund | `--bg` | `#202123` | `background` / `surface` nach Kontext |
| Erhöhte Oberfläche | `--surface` | `#343638` | `surfaceContainerHigh` / `surfaceVariant` |
| Zwischenfläche | `--surface-alt` | `#292B2D` | `surfaceContainer` |
| Tiefere Fläche | `--surface-deep` | `#191B1D` | `surfaceContainerLow` |
| Primärakzent | `--accent` | `#FF510A` | `primary`, aktive Selection |
| Text auf Akzent | `--on-accent` | `#1E160F` | `onPrimary` |
| Primärtext | `--primary` | `#F7F7F7` | `onSurface` |
| Sekundärtext | `--secondary` | `#A6A6AA` | `onSurfaceVariant` |
| Kontur/Trennlinie | `--line` | `#48494D` | `outlineVariant` |
| Ablehnen | `--negative` | `#F72F49` | getrennte semantische negative Aktionsfarbe |
| Gefällt | `--positive` | `#4CDA36` | getrennte semantische positive Aktionsfarbe |
| Favorit | `--favorite` | `#D26BEC` | eigene Favoritenrolle, nicht Preference-Like |
| Erfolg | `--success` | `#5DE44E` | Status, nicht automatisch Like |
| Fokus | `--focus` | `#FFC18D` | gut sichtbarer Focus-Ring |

**[MUSS]** Akzenttext klein und regulär **dunkel**, nicht weiß: berechneter Kontrast Dunkel auf Orange ca. 5,47:1; Weiß auf Orange nur ca. 3,26:1. Primärtext auf Grund ca. 15:1. Kontrast auf Porträts ist mit Scrim/Gradient und Prüfung für reale Hintergründe zu sichern. Fehler/Erfolg/Favorit nie ausschließlich farblich signalisieren. Die Farbe des Vorschaumodus wird nicht durch Zufallsbilder abgeleitet. Status-/Fehlerfarben im nativen Theme für beide Helligkeiten separat prüfen.

**[MUSS]** Benannte Rollen mit Gewicht/Zeilenhöhe verwenden, ohne per Screen unbekannte Sondergrößen zu verteilen. Die lokale Browserreferenz verwendet eine verfügbare System-Sans-Schrift; Android richtet sich nach Material-3-/Roboto-Metriken und `sp`. Keine fremden Webfonts oder CDN-Abhängigkeiten.

| Rolle | CSS-Richtgröße | Gewicht / Zeile | Typischer Einsatz |
| --- | --- | --- | --- |
| `title` | 22 px / 1,375 rem | 700 / 1,23 | Marke und große Hauptüberschriften |
| `heading` | 19 px / 1,1875 rem | 700 / 1,23 | Abschnittsüberschriften |
| `body` | 14 px / 0,875 rem | 400–600 / 1,4 | Beschreibung und Gruppen |
| `label` | 13 px / 0,8125 rem | 500–700 / 1,4 | Segment, Zeile, Action |
| `caption` | 12 px / 0,75 rem | 400–600 / 1,4 | ergänzende Metadaten |

**[RICHTWERT]** CSS `px` für Dimensionen → Android `dp`, Texthierarchie → `sp`; nicht von identischer Pixelgeometrie ausgehen. Bei Font Scale 1,3/2, IME und Barrierefreiheit responsive auf mehrzeilige/vertikale Anordnung wechseln. Die browserseitige Textskalierung ist kein TalkBack-Test.

## 3. Raster, Formen und wiederverwendbare Komponenten

| Dimension | Variable | Ziel |
| --- | --- | --- |
| Abstandsleiter | `--space-1/2/3/4/6/8` | 4 / 8 / 12 / 16 / 24 / 32 dp |
| Außenabstand | `--outside` | 16 dp |
| Kartenabstand | `--card-gap` | 12 dp |
| Abschnittsabstand | `--section-gap` | 16 dp |
| Hero/Karte | `--card-radius` | 24 dp |
| Sekundärgruppe | `--group-radius` | 17 dp, Rundungsfamilie vereinheitlichen |
| Segment/Pill | `--pill-radius` | vollgerundet |
| Header | `--header-height` | 56 dp |
| Root-Navigation | `--nav-height` | 64 dp (ohne Android-Systeminsets) |
| Minimales Interaktionsziel | `--touch` | **48 × 48 dp** |

**[MUSS]** Gemeinsame, kontextfreie UI-Komponenten statt pro Screen duplizierter Strukturen: `AppShell`, `AppHeader`, `RootNavigation`, `ViewModeSwitch`, `ContextStrip`, `ProfileHero`, `ProfileCard`, `DecisionRail`, `FilterSection`, `SettingsGroup`, `ProfileSection`, `RecordEditorDialog`, `FeedbackState`. Aktiver/normaler/deaktivierter/Fokuszustand darf keine Geometrie verändern. Die CSS-Fassung nutzt keine externen Icons, sondern zentral definierte SVG-Stroke-Symbole; in Compose nach Möglichkeit vorhandene Material-Icons statt neuer paralleler Icon-Bibliothek verwenden.

**[MUSS]** Labels und Werte sind anhand tatsächlich verfügbarer Felder darzustellen. Kein erfundener „jetzt verfügbar“-Status, keine fiktive Entfernung als bestätigt, keine zufällige Quellaktivität, kein stillschweigender Statuswechsel. Leere optionale Felder auslassen; relevante Unbekanntheit explizit zeigen. Buttons erscheinen nur bei gültigem Handlungsbezug oder sind mit plausibler Erklärung deaktiviert.

## 4. Shell, Navigation, Scroll- und Insets-Vertrag

**[MUSS]** Die Root-Ansichten Entdecken, Suche, Gespeichert und Einstellungen teilen **dieselbe** App-Bar und 4-Ziele-Bottom-Navigation. Entdecken hat **drei** Modi Swipe, Grid und Karte mit identischer Umschalterposition; der Moduswechsel führt keinen neuen Suchlauf aus. Suche und Einstellungen teilen Abschnittstitel-Muster. Das Standalone-Profil ersetzt Header/Navigation kontextgerecht durch Back, Identität, Favorit und Mehr – **keine Bottom-Navigation, keine Decision Rail, keine Profil-Deck-Geste**.

**[MUSS]** Jede Ansicht verwendet genau einen vertikalen Hauptscrollfluss; bei Swipe bleiben Hero und Details im selben Fluss. Die feststehenden Header-/Aktions-/Navigationsflächen überdecken keine Inhalte. In der Android-App Systemleisten, Edge-to-Edge, IME, Tastatur und Navigationsgesten über vorhandene `WindowInsets`-/Scaffold-Verträge behandeln; Browserrahmen `.reference-stage`/`.phone` und eventuell sichtbare Desktopumgebung **nicht übertragen**. Kein WebView-Port, kein CSS-/JS-Runtime in der APK.

**[MUSS]** Gültige Scroll-, Foto- und Modusposition beim Profilbesuch und Back erhalten. Bei Notizentwurf oder Record-Editor Back/Owner-Wechsel nicht still verwerfen. Dialoge haben eine eindeutige Überschrift, Fokusführung und explizites Schließen/Abbrechen. Keyboard, DPAD und TalkBack dürfen nicht von Touch-Gesten abhängen.

**[MUSS – ausdrücklich ersetzte alte Hero-Geometrie]** Die v3-Gestaltung verwendet zwei kompakte Kontext-/Moduszeilen oberhalb des Fotos. Der bisherige pauschale 14-%-Maximalwert für den Hero-Beginn und die mindestens 65-%-Höhe bei 390–430 dp sind für dieses **neue visuelle Soll aufgehoben**, nicht unverändert weiter anzuwenden. Bei 390×844 CSS-px misst die Referenz näherungsweise `x=12, y=171, w=366, h=534` (63 % der gesamten Referenzhöhe). Dies sind **Browserreferenzwerte**, keine unveränderten nativen dp-Mindestwerte. M08.2 hat den Hero anhand realer WindowInsets, 48-dp-Touchziele und der sichtbaren Decision Rail zu vermessen: großer Bildanteil und sofort erkennbare Identität, ohne verdeckte Aktionen oder doppeltes Padding. Auf 360 dp, bei IME, Font Scale, Rotation und niedrigen Höhen ist ein begründeter kompakter Fallback zulässig. Die fachliche Ein-Scrollfluss-/Swipe-Arbitration bleibt voll gültig.

**[RICHTWERT]** Im Grid sind die Referenzkarten näherungsweise quadratisch (`aspect-ratio:1.03`, Web) – anders als der bislang dokumentierte Richtwert Breite/Höhe 0,77. Der bisherige Richtwert 0,77 wird durch die v3-Referenz ersetzt; die native Umsetzung übernimmt diesen Richtwert auf Grundlage dieser visuellen Freigabe, ohne Inhaltsverlust oder Textabschneiden. Auf Tablet/600 dp Karte/Grid adaptiv begrenzen, nicht die Gesamtoberfläche skalieren.

## 5. Ansichts- und State-Verträge

### Entdecken: Swipe und Grid

**[MUSS]** Der sichtbare lokale Ausgangsbestand ist explizit vom **ausgeführten** Suchlauf getrennt. Swipe und Grid teilen denselben gültigen Run-/Results-Snapshot; Wechsel oder Scroll lösen keinen HTTP-Read aus. Neutraler Ausgangskontext heißt in v3 sichtbar „Lokaler Bestand“. Die Karte zeigt unabhängig davon den datenbankweiten Bestand.

**[MUSS]** Der Hero hat Cover, unten kontrastgesicherten Namen/Alter/Ort sofern bekannt und seitliche Galerie-Tapzonen. Keine permanente Galerie-Chrome oder fingierte Videos. Horizontaler Entscheidungs-Swipe nur am Hero mit eindeutiger Achse, ohne Tap-Kollision. Vertikales Scrollen öffnet Details im selben Fluss. `Undo`, `Nein`, `Später`, `Gefällt` bleiben erreichbar; `Später` ist sessionlokal. Favorit ist eine **andere** Personeneigenschaft als Preference-Like. Deckentscheidungen erst nach bestätigtem Commit weiterschalten; Undo stellt den vorherigen vollständigen gültigen Präferenz- und Presentationsschritt her.

**[MUSS]** Grid zeigt dieselben Treffer, kann direkt liken und nur im **aktiven Grid** nach erfolgreicher Aktion einen gelikten Eintrag temporär ausblenden. Ausblenden nach Fehler zurücknehmen; nicht zusätzlich in einer zweiten Datenmenge verändern. Profilöffnung und Rückkehr ohne Requery/Positionverlust.

### Karte

**[MUSS]** Lokaler Gesamtdatenbestand unabhängig vom aktuellen Suchlauf. Favoritenfilter, Marker/Cluster, gemeinsame Auswahl für identische angenäherte Ortslagen, Auswahlkarte und Profileinstieg. Ungefähre Stadt-/PLZ-Zentren erkennbar, niemals exakte versteckte Adressen/GPS, keine erfundenen Koordinaten. Geocoding nur ausdrücklich, begrenzt, abbrechbar, mit erhaltenen Teilergebnissen und gültiger Owner-/Lifecycle-Policy. MapLibre im realen Android-Code beibehalten; die SVG-Karte aus `prototype/assets/` ist ausschließlich Illustration.

### Standalone-Profil und neuere M06-Bereiche

**[MUSS]** Gemeinsamer vorhandener `ProfileFlow` statt zweiter Detailimplementierung. Wesentliche belegte Merkmale, Beschreibung, Kontakt nur bei gültigem Datensatz, Dienste/Evidenz, Identitätsanalyse, getrennte Personennotiz, **Meine Begegnungen**, **Manuelle Angebotspreise**, Historie und verknüpfte Anzeigen bleiben im selben Profil zugänglich. Galerie und Video mit bestehenden Sicherheits-/Lifecycle-Verträgen, Kontakt nur durch ausdrückliche Nutzerhandlung.

**[MUSS]** Begegnungen sind personenbezogen und beinhalten nach vorhandenem Produktvertrag bewusste Eingabe von Zeitpunkt, ggf. Dauer, Grund-/Gesamtpreis, Gesamt-/Sympathiebewertung, Begegnungsnotiz und persönliche Leistungszeilen. Angebotspreise werden manuell als datierte Preislisten mit Positionen erfasst; frühere Listen bleiben erhalten, bezahlte Beträge stammen **nur** aus Begegnungen und werden getrennt ausgewiesen. Notizentwürfe, CAS-/Owner-Prüfung, Konflikte, Fehler und Rückkehr verlässlich behandeln. **[DEMO]** Die v3-Formulare veranschaulichen Feldgruppen und lokale Rückmeldung; sie implementieren **nicht** die vollständigen nativen Editier-/Merge-/Persistenzverträge.

### Suche

**[MUSS]** Filterentwurf, leichtgewichtige nichtpersistente Vorschau und ausgeführter Run sind unterschiedliche Zustände. Alter, Radius, Ort, Verfügbarkeit, Kategorie, Treffpunkt, Merkmale, Größe, Services und Seitenlimit bleiben erreichbar. Letzte ausgeführte Ergebnisliste unabhängig vom Entwurf erreichbar. Suche zeigt Loading und Cancel, Erfolg öffnet Entdecken; Fehler/Abbruch erhalten letzten bestätigten Run. Teilresultat und unbestätigter Radius sind kein vollständiger Nulltreffer und verändern keine SavedSearch-Mitgliedschaft. Schnellfilter Alle/Jetzt/Heute startet nur an den dafür vorgesehenen Stellen sofort.

### Gespeichert

**[MUSS]** Favoriten/Likes/Dislikes getrennt mit korrekten Personenzuordnungen, Counts, Status-/Sichtungszeiten und Sortierung. Watchlist „Neu & geändert“ nach bestehendem Produktvertrag einschließlich explizitem Prüf- und Abbruchverhalten. Gespeicherte Suchen mit getrenntem Editor, Ergebnissen, Neu-Indikator, Delta, expliziter Quittierung und Fehlerzuständen. Öffnen/Reload markiert Neuigkeiten **nicht** automatisch gelesen. Die Demo enthält keine wahrheitsfähigen Quellenstatusdaten; Anzeigenzahl/Zeiten und Watchlist-Logik dürfen nicht aus Beispielwerten übernommen werden.

### Einstellungen und Diagnose

**[MUSS]** Echter DB-/Core-Status; Replay-Likes sauber von lokaler Hide-Likes-Sichtbarkeit unterscheiden. INFO/DEBUG und HTTP-Aufzeichnung Aus/Metadaten/Textantworten nur gemäß bestehendem Core- und Diagnosevertrag. Ein Neustart schaltet Capture aus; keine unbewusste Freigabe privater Logs/HAR/Notizen, keine automatische Weitergabe. Die Demo meldet die fehlende Datenbankverbindung und erzeugt lediglich ein gekennzeichnetes Beispielprotokoll.

### Repräsentative Fehlerzustände

**[MUSS]** Loading, Empty, Fehler mit Retry, Partial und unbekannte Werte sind als normale Produktzustände präsentationsfähig. Testbedingte Statusinjektion ist kein sichtbares App-Feature und kein neues fachliches Event. Bestehende FFI-/Owner-/Session-Konfliktsemantik hat immer Vorrang vor JS-Simulation.

## 6. Compose-Zuordnung ohne Parallelarchitektur

| Referenzkomponente (`design-referenz/`) | Bestehender oder passender Android-Code | State/Ereignis | Tokens |
| --- | --- | --- | --- |
| `AppShell`, `AppHeader`, `RootNavigation` – `app.js` | `LadiesApp`, `ShellRoute`, vorhandene Material-3-Navigation | `selectRoot`, `back`, aktives Ziel | Hintergrund, Header, Bottom-Navigation |
| `ViewModeSwitch`, `ContextStrip` | `DiscoverModeSwitcher`, `MainViewModel` | `onSelectMode`, aktiver Run vs lokaler Bestand | Akzent, Touch, Oberfläche |
| `ProfileHero`, `DecisionRail` | `DiscoverScreen`, `SwipeShellHeader`, `SwipeDecisionControls`, `ProfileFlow` | `onGallery`, `onLike`, `onDislike`, `onSkip`, `onUndo`, `onFavoriteToggle` | Hero-Radius, Statusfarben, Typografie |
| `ProfileCard`, `ProfilesGrid` | existierende Discovery-Grid-/Karten-Komponenten | `onOpenProfile`, `onLike`, Person/Listing-Identität | Grid-Gap, Card-Shape |
| `MapSurface`, `SelectedCard` | `MapScreen`, `NativeProfileMap`, MapLibre | `onResolve`, `onCancel`, `onFavoritesOnly`, `onProfile` | Oberfläche, Marker/Favorit |
| `ProfileSections`, `NoteEditor` | `ProfilePanels`/`ProfileFlow`, `NoteEditorActions` | `onSaveNote`, Draft/Conflict/Owner, `onBack` | Abschnitt, Text, Aktionen |
| `RecordEditorDialog` (Begegnung/Preis) | `EncounterEditor`, `PriceEditor`, `EncounterEditorDialog`, `PriceEditorDialog` | lokaler Draft; `onCreate/Update/Delete`, CAS/Owner | Dialog, Touch, Titel |
| `FilterSection`, `SearchResults` | `SearchScreen`, `MainViewModel`, vorhandene SearchUiState | Draft/Preview/Run, `onSearch`, `onCancel` | Felder, Segmente, Abstände |
| `SavedPeople`, `SavedSearches` | bestehende Saved-Panels / `SavedSearchController` | `onOpen`, `onAck`, `onRun`, Statusprüfung | Zeilen, Badge, Tab |
| `SettingsGroup`, `FeedbackState` | `SettingsScreen` in `LadiesApp`, `MainViewModel` | `onRefresh`, `onCaptureMode`, `onExport` | Gruppenoberfläche, Status |

**[MUSS]** Reihen/Spalten nicht screenshotabhängig duplizieren; Compose `Row`/`Column`/`Box`/Lazy-Layouts und bestehende ViewModels nutzen. Keine Kotlin-HTTP-Anfragen an die Quelle, kein SQL in der UI, keine WebView, keine Import-/Migrationsroute. Die Web-Simulation enthält keinerlei fachliche Zeit-/Transaktionsgarantien und darf daher nicht in den Core kopiert werden.

## 7. Fokus, Accessibility, responsive und Bewegung

**[MUSS]** Zielgrößen ≥48 dp auch bei Segmenten, Radius-Chips, Kartenmarkern und Menü-Icons. Verständliche Semantik/Accessible Names, TalkBack, DPAD, Hardkeyboard, Fokusindikator, Dialogfokus und IME-Rückkehr. Gesperrte Aktionen sind begründet und nicht als aktiv erscheinende tote Klickflächen darzustellen. Kontrast auf Fotos, dynamisch lange Werte und 200 % Schriftgröße prüfen. Nativer Scroll- und Insets-Vertrag gilt über Browserwerte hinaus.

**[MUSS]** Bewegung folgt der Aktion und wird unter System-Animator-Scale beziehungsweise Web `prefers-reduced-motion` reduziert; kein erzwungener Scroll-Snap. Gestenpriorität: interaktiver Child > Galerie-Tap > eindeutige horizontale Hero-Entscheidung > vertikales Profilscrollen; bei Drag kein verspäteter Foto-Tap.

**[RICHTWERT]** Prüfraster 360×740, 390×844, 430×932 und 600 px Breite sowie Font Scale 1/1,3/2. Größere Browserfenster zentrieren die Preview; die native App darf keine künstlichen Geräte-/Screenshot-Rahmen erhalten. Die Referenz besteht nur im Dark Mode. Die **gleichwertige Light-Mode-Variante** ist vor vollständiger Designabnahme nativ auszugestalten und separat zu prüfen, nicht per CSS-Invertierung zu simulieren.

## 8. Prüfkriterien und Übergabegrenzen

1. Die gemessenen Header-/Bottom-Navigation-Rechtecke sind über sämtliche Root-Screens identisch; Farben, Radien, Segmente, Labels und Fokus reagieren konsistent.
2. Keine Demo-Toolbar, Szenario-Menüs, Reset-Oberfläche, künstliche Systemleisten, Netzwerkaufrufe, reale Kontakte oder automatische Quellabfragen. Desktop-Rahmen bleibt ausschließlich Vorschau.
3. Swipe-/Grid-Datensatz identisch im gültigen Run, Karte unabhängig, Profil öffnet mit identischem Owner und kehrt an gültige Position zurück.
4. Like/Dislike/Später/Undo/Favorit sind verschieden; kein zweiter fachlicher State oder impliziter Write beim Rendern.
5. Galerie/Video/Scroll/Gesten und persönliche Editoren respektieren interaktive Kindziele, Draft-/Owner-/Lifecycle-Sicherheit; keine Datenverluste beim Back.
6. Suche: Draft ≠ Preview ≠ Run, Cancel/Fehler/Teilresultat erhalten bestätigte Daten; gespeicherte Suchen ohne implizites Ack.
7. M06-Begegnungen/Preislisten bleiben vollständig erreichbar; keine fiktiven Preise, keine automatische Preiserfassung, bezahlter Betrag nicht mit Angebotspreis verwechseln.
8. MapLibre-/Geocoder- und Diagnose-APIs werden nicht durch HTML-/JS-Implementierungen ersetzt.
9. Dark **und** Light, Systeminsets, IME, 360/390/430/600 dp, Font Scale 1/1,3/2, TalkBack/DPAD und Fokus müssen auf nativer Zielplattform geprüft werden; Web-Tests ersetzen sie nicht.
10. Widersprüche der alten `docs/DESIGN.md` (Pink, Grid-Aspekt, altes Hero-Startbudget) werden beim späteren Integrations-PR **explizit aufgelöst**, nicht als pauschales Löschen der übrigen UX-Regeln verstanden. `PRODUCT.md`, `ARCHITECTURE.md` und bestehende Schutzprüfungen bleiben maßgeblich.

## 9. Unverbindliche Demo-Daten, Risiken und Grenzen

**[DEMO]** Acht erfundene volljährige Personen samt Fotos/Namen/Orten, ein schematisches Leipzig-SVG, flüchtige Such- und Mapzustände, lokale Begegnungs-/Preisformulare sowie nicht verbundene Datenbank/HTTP/Contact-Aktionen. Sie sind **keine** Referenz für echte Preise, Felder, Server-Semantik, Archivierung, Lizenz- oder Datenschutzfreigabe. Die Positionszeilen und Datumsfelder der Web-Editoren sind illustrative UI und keine vollständige Rust-/UniFFI-API-Spezifikation.

**Beleg:** Die v3-Referenz wurde vor Übernahme separat in Chromium an repräsentativen Viewports geprüft; diese frühere Browserprüfung ist **kein** Nachweis aktueller nativer Android-UI- oder Laufzeittests. Die HTML-Referenz ist unter [`design-referenz/`](../design-referenz/README.md) abgelegt.

**Offen für M08:** Vollständige native Light-Mode-Palette, präzise Android-Hero-/Insets-Messung, visuelles und fachliches Regressionsreview der bestehenden M06-/SavedSearch-/Watchlist-Komponenten. Das Design ist als Soll beschlossen, nicht bereits nativ implementiert.
