# UI- und Designvertrag

Maßgebliches gestalterisches und interaktives Soll der nativen Android-App. Die [Designreferenz v3](../design-referenz/README.md) zeigt die freigegebene visuelle Richtung mit synthetischen Daten; sie ist keine WebView-Vorlage und kein eigenständiger Produktvertrag. Fachliche Funktionen und Grenzen: [PRODUCT](PRODUCT.md); technische Architektur und Lebenszyklus: [ARCHITECTURE](ARCHITECTURE.md); Aufgaben, Status und Abnahme: [ROADMAP](ROADMAP.md). Agentenablauf: [AGENTS](../AGENTS.md).

## Zuständigkeit und Pflege

**Verbindlich:**
- Dieser Vertrag bestimmt das sichtbare UI-/UX-Soll. `PRODUCT.md` entscheidet über Fachsemantik und erlaubte Funktionen; `ARCHITECTURE.md` über Datenhoheit, Operationen und Integrität. Bestätigte Nutzerentscheidungen haben Vorrang. Ein Screenshot oder Demo-Klick ändert keine dieser Grenzen.
- Die orangefarbene v3-Gestaltung ist das Ziel des nativen Redesigns. Bis zu dessen schrittweiser Umsetzung dürfen vorhandene Compose-Screens noch visuell abweichen; allein diese bekannte Übergangsabweichung ist kein neuer Funktionsfehler. Bei jeder M08-Änderung betroffene Screens und gemeinsame Komponenten gegen diesen Vertrag prüfen.
- **Verbindliche Regeln** beschreiben erwartbares Verhalten und Zugänglichkeit. **Richtwerte** steuern die Gestaltung, soweit native Messung, Font Scale und Insets es erlauben. **Referenz-/Demodaten** dienen ausschließlich der Illustration. Bestehende Compose-Klassen und CSS-Dateinamen sind technische Zuordnungen, keine erzwungene neue Architektur.
- Funktionen, Datenbedeutungen, Owner-/Cancel-/Lifecycle-Verträge und sicherheitsrelevante Grenzen nicht aus visuellen Vereinfachungen ableiten oder abschwächen. Änderungen am Soll nach [AGENTS](../AGENTS.md#architektur--und-designvertrag) gemeinsam mit den betroffenen Verträgen prüfen; Aufgabenstände und Historie ausschließlich in `ROADMAP.md` pflegen.

## Visuelle Identität

**Verbindlich:**
- Ruhige, bildorientierte, kompakte und informationsreiche Oberfläche; dunkel-neutrale Flächen mit Orange als primärem Interaktionsakzent. Das bisherige pinke Branding und widersprechende alte Farb-, Shape- und Abstandsrichtwerte sind durch diese Richtung ersetzt.
- Bestehendes Material 3 Expressive und die semantischen `MaterialTheme`-Rollen verwenden; keine zweite native Tokenbibliothek. [LadiesTheme.kt](../app/src/main/java/de/justify87/ladiesexplorer/ui/LadiesTheme.kt) ist nach nativer Anpassung die konkrete Implementierung. Dynamische Systemfarben nicht standardmäßig aktivieren.
- **Dark und Light Mode gleichwertig unterstützen.** Die HTML-Referenz legt nur die dunkle Richtung fest; eine helle Palette ist in M08 nativ auszugestalten und abzuprüfen, nicht durch blindes Invertieren. Semantische Statusfarben bleiben in beiden Modi unterscheidbar.
- Farbe allein vermittelt weder Auswahl noch Like, Favorit, Quellenstatus, Fehler oder Erfolg. Symbol, Text bzw. zugängliche Zustandsbeschreibung ergänzen sie. Kontrast auf Fotos durch Scrim/Gradient gegen wechselnde Hintergründe sichern.

**Richtwerte – gemeinsame dunkle Start-Tokens** aus [`styles.css`](../design-referenz/styles.css) (`:root`):

| Rolle | Referenztoken | Wert | Native Zuordnung |
| --- | --- | --- | --- |
| Hintergrund | `--bg` | `#202123` | `background` / `surface` |
| Erhöhte Fläche | `--surface` | `#343638` | `surfaceContainerHigh` |
| Zwischen-/tiefe Fläche | `--surface-alt`, `--surface-deep` | `#292B2D`, `#191B1D` | passende `surfaceContainer`-Rollen |
| Primärakzent | `--accent` | `#FF510A` | `primary` / aktive Auswahl |
| Text auf Akzent | `--on-accent` | `#1E160F` | `onPrimary` |
| Primär-/Sekundärtext | `--primary`, `--secondary` | `#F7F7F7`, `#A6A6AA` | `onSurface`, `onSurfaceVariant` |
| Trennlinie | `--line` | `#48494D` | `outlineVariant` |
| Ablehnen / Gefällt | `--negative`, `--positive` | `#F72F49`, `#4CDA36` | eigenständige Aktionsrollen |
| Favorit / Erfolg | `--favorite`, `--success` | `#D26BEC`, `#5DE44E` | voneinander getrennte Statusrollen |
| Tastaturfokus | `--focus` | `#FFC18D` | sichtbarer Fokusindikator |

Für klein gesetzten Akzenttext ist der **dunkle** Vordergrund vorgesehen (ca. 5,47:1 auf Orange); Weiß auf `#FF510A` erreicht nur ca. 3,26:1 und ist für kleinen Normaltext nicht ausreichend. Der tatsächliche Kontrast aller nativen Zustände ist getrennt nach Theme zu prüfen.

**Richtwerte – Maße und Typografie:**

| Rolle | Referenz | Anwendung |
| --- | --- | --- |
| Abstandsleiter | 4 / 8 / 12 / 16 / 24 / 32 dp | konsistente Innen- und Außenabstände |
| Außen / Karten / Abschnitte | 16 / 12 / 16 dp | gemeinsame Screen-Raster |
| Hero-/Kartenradius | 24 dp | große bildbetonte Flächen |
| Gruppierung | ca. 17 dp, kleinere Elemente passend gerundet | wiederkehrende Sektionen; keine pro Screen abweichenden Sonderradien |
| Header / Root-Navigation | 56 / 64 dp ohne Systeminsets | gemeinsame App-Shell |
| Interaktionsfläche | **mindestens 48 × 48 dp** | alle Buttons, Chips, Marker und Icons |
| `title` / `heading` | 22 / 19 sp als Startwerte, fett | Marke, Haupt- und Abschnittstitel |
| `body` / `label` / `caption` | 14 / 13 / 12 sp als Startwerte | Fließtext, Aktionen, Metadaten |

Benannte Typografierollen mit passenden Zeilenhöhen verwenden; native `sp`-Metriken und lange Beschriftungen haben Vorrang vor CSS-Pixelgleichheit. Die früheren Legacy-Abstandsfolgen und Grid-/Hero-Maße gelten nicht neben diesen Werten weiter.

## Komponenten und Interaktion

**Verbindlich:**
- Gemeinsame, fachlich benannte Komponenten für `AppShell`, `AppHeader`, `RootNavigation`, `ViewModeSwitch`, `ContextStrip`, `ProfileCard`, `ProfileHero`, `DecisionRail`, `FilterSection`, `SettingsGroup`, Profilsektionen und Rückmeldezustände verwenden; vorhandene Compose-Strukturen bevorzugen. Gleiche Komponenten haben über alle Screens gleiche Insets, Radien, Farben, Icons und Fokus-/Aktiv-/Deaktiviertzustände.
- Pro Handlungskontext eine erkennbare primäre Aktion; sekundäre Aktionen visuell zurücknehmen. Chips für Filter und Auswahlen statt für jeden Wert, kompakte Zeilen mit Thumbnail, Identität, Status und direkten Aktionen. Details progressiv offenlegen, ohne vorhandene Daten zu verlieren.
- Aktivierung ändert keine Komponentengeometrie. Touchflächen sind mindestens 48 dp groß, auch wenn ihre sichtbaren Icons/Segmente kleiner wirken; Flächen nicht überlappen lassen. Screenlayout mit nativen `Row`/`Column`/`Box`/Lazy-Layouts, keine Screenshot-Koordinaten oder `nth-child`-Korrekturen.
- Loading, Empty, Unknown, Fehler mit Retry und Partial erkennbar unterscheiden. Keine Nulltreffer aus unbekannten Vorschauen ableiten. Zustands- und Fehlermeldungen bleiben bei betroffenen Aktionen erreichbar, nicht nur hinter einem Menü.
- Keine künstlichen Systemleisten, Entwicklungsmenüs, Dummy-Funktionen oder erfundenen Profilangaben in der nativen Oberfläche. Kontakte und externe Profilaufrufe nur ausdrücklich durch Nutzeraktion. Animationen funktional, unterbrechbar und bei reduzierter Bewegung abgeschwächt.

## Navigation und Bildschirmmuster

### Root-Navigation und Entdecken

- **Verbindlich:** Vier Root-Ziele `Entdecken`, `Suche`, `Gespeichert`, `Einstellungen` teilen dieselbe App-Bar und Bottom-Navigation. Such- und Einstellungsüberschriften verwenden ein gemeinsames Abschnittsmuster; Branding/Suche/Mehr bleiben konsistent. Standalone-Profile besitzen Back/Mehr, aber keine Root-Navigation.
- **Verbindlich:** Entdecken hat die Modi `Swipe`, `Grid`, `Karte` mit demselben Umschalter und unverändertem Platz; ein Moduswechsel startet keine neue Suche. Ausgeführter Suchlauf, lokaler Ausgangsbestand und DB-weiter Kartenbestand sind sichtbar und fachlich getrennt.
- **Verbindlich:** Alle/Jetzt/Heute im Entdecken-Kontext ist eine Schnellwahl, die unmittelbar einen Suchlauf auslösen darf; eine entsprechende Änderung im Suchentwurf führt ohne ausdrückliches `Suchen` keinen Suchlauf aus. Aktiver Run/Radius/Verfügbarkeit und Teilresultate dürfen nicht als neutraler lokaler Bestand erscheinen.
- **Richtwert:** Der Header folgt der 56-dp-Rolle, Kontext- und Moduszeile der kompakten v3-Komposition. Keine unnötige doppelte Titelleiste oder Paddingkette.

### Grid

- **Verbindlich:** Gemeinsame Profilkarte für Entdecken und gespeicherte Suchergebnisse; auf typischen Smartphones zwei Spalten. Bild, unterer Scrim, Name/Alter und belegter Ort; bei fehlendem Medium ein lesbarer Fallback. Direktes Like im Entdecken-Grid und Profilöffnung getrennt bedienen.
- **Verbindlich:** Grid und Swipe verwenden dieselbe **ausgeführte** Ergebnismenge. Direkt gelikte Karten werden ausschließlich im aktiven Grid nach erfolgreicher Mutation temporär ausgeblendet; bei Fehler wieder sichtbar. Keine zweite fachliche Datenliste, keine automatische Quellabfrage durch Moduswechsel.
- **Richtwert:** v3-Gridkarten sind annähernd quadratisch (Web-`aspect-ratio: 1.03`); der alte Richtwert Breite/Höhe 0,77 wird ersetzt. Abgerundete Ecken ca. 24 dp. Bei großen Fenstern adaptive Begrenzung und bei Font Scale lesbare Identität vor starrer Bildquote.

### Suche

- **Verbindlich:** Globaler Filtertext, Ort/Radius, Verfügbarkeit, Altersbereich mit zwei Grenzwerten, Größenbereich, Rubrik, Treffpunkt, Kategorien/Merkmale, Services, Seitenlimit und weitere bestehende Optionen bleiben erreichbar. Gültige Quellenwerte sind keine aus dem Mockup abgeleiteten neuen Filter.
- **Verbindlich:** Top 5 der **abgeschlossenen** früheren Suchläufe zugänglich halten; Sortierung und Zählregeln kommen aus [PRODUCT](PRODUCT.md#parameter-und-top-5). Unterstützte lokale Filter (nur Foto/Video, Likes/Dislikes ausblenden, `ALL`/`AI`/`NATURAL` auf belegten Markern) bedienbar lassen. `NATURAL` bedeutet fehlende KI-Markierung, nicht nachgewiesene Natürlichkeit.
- **Verbindlich:** Filterentwurf, nicht persistierende Vorschau und ausgeführter Suchlauf bleiben unabhängig. Die letzte ausgeführte Trefferliste ist trotz geändertem Entwurf mit Profilöffnung und Rückkehr zugänglich; auch nur im Grid temporär versteckte Likes können dort erscheinen. Gespeicherte Suchen verwenden denselben Editor und respektieren dessen gültigen Entwurfskontext.
- **Verbindlich:** Suchstart mit Loading, Abbrechen, Fehler, Retry und Teilresultat; erfolgreicher Run öffnet Entdecken. Fehler/Abbruch erhalten den letzten bestätigten Run. Vorschau-Count bei nicht bestätigtem Radius bzw. unzuverlässiger Quellzahl **unbekannt** statt null zeigen; Radius nicht automatisch aufweiten. Lokale Sichtbarkeitsfilter beeinflussen nicht den Quellen-Count.
- **Richtwert:** Einklappbare Filtergruppen, Footer mit `Reset` und `Suchen` sowie Count/Status; Scrollposition und Gruppenöffnung während relevanter Navigation erhalten. Lange Auswahltexte werden umgebrochen statt abgeschnitten.

### Gespeichert

- **Verbindlich:** Favoriten, Likes und Dislikes getrennt mit Counts, Sortierung und korrekter Personenzuordnung. Watchlist „Neu & geändert“ (36 h, zunächst drei Einträge, ausklappbar) und explizite Einzel-/Batchstatusprüfung samt Fortschritt, Abbruch und Teilfehlern zugänglich halten.
- **Verbindlich:** Gespeicherte Suchen bieten Übersicht, Editor, letzten Offline-Ergebnisbestand, Delta-/Neu-Indikator, Run und **ausdrückliche** Quittierung. Öffnen, Reload, Rückkehr oder Pagination bestätigen keine Neuigkeiten. Nicht unterstützte Definitionen erkennbar eingeschränkt, nicht still verändert.
- **Verbindlich:** Quellenstatus nur aufgrund belegter Beobachtung als aktiv/nicht verfügbar zeigen. Letzte Sichtung, Änderung und Statusprüfung begrifflich und zeitlich trennen; unbekannte bzw. historisch nicht belegte Inaktivität bleibt unbekannt. Lokales Neuladen ist keine Quellenstatusprüfung.
- **Richtwert:** Kompakte Zeilen mit Thumbnail, Name/Alter, Ort, Status, Zeitbezug, Anzeigenanzahl, Herz/Mehr; weder Status noch Count allein durch Farbe vermitteln.

### Karte und Einstellungen

- **Verbindlich (Karte):** Datenbankweiter Bestand unabhängig vom aktuellen Run, optional nur Favoriten. Ungefähre Stadt-/PLZ-Zentren kennzeichnen; Marker/Cluster und gemeinsame Auswahl gleicher Orte mit Profilöffnung. Kein GPS, keine exakten versteckten Orte, keine Ersatzkoordinaten, kein Routing. Standortauflösung ausdrücklich und abbrechbar; gecachte Zentren, Teilresultate, Fehler/Retry und Auswahl/Kamera bei gültigem Tab-/Profilwechsel erhalten.
- **Verbindlich (Einstellungen):** Echten lokalen DB-/Core-Status, Replay-Likes, INFO/DEBUG-Diagnose, bewussten HTTP-Capture-Modus und TXT/HAR-Aktionen abbilden. Aus bleibt nach Neustart Standard, keine automatische Weitergabe oder fingierte Statusbestätigung. Details der Aufzeichnung und Maskierung gehören zu [PRODUCT](PRODUCT.md#einstellungen-und-lokale-diagnose) und [ARCHITECTURE](ARCHITECTURE.md#diagnoseinfrastruktur).
- **Richtwert:** Große, ruhige Kartenfläche mit zurückhaltenden Controls; Einstellungen in wenigen funktionsbezogenen Gruppen, nicht als Sammlung gleichwertiger großer Karten.

## Profile und Medien

**Verbindlich – Priorität:** (1) richtige Identität und fachlich gültige Entscheidung ohne Datenverlust; (2) eindeutige Gesten- und Scrollzuordnung; (3) großes Medium; (4) kompakte zugängliche Details; (5) Dekoration. Swipe und Standalone verwenden denselben bestehenden Profilinhalt mit getrennten Navigations- und Operationskontexten. Alle tatsächlich vorhandenen Felder bleiben zugänglich; fehlendes Alter/Status/Preise niemals schätzen.

### UX-Vertrag — zwei Zustände, ein Scrollfluss

**A – Start am Hero:**
- **Verbindlich:** Kompakte Marken-/Such-/Kontext- und Modusdarstellung, unmittelbar darunter ein großer Hero mit Foto in Cover-Darstellung. Keine dauerhafte Galerie-Leiste, Storysegmente, Zähler, Pfeile oder Gestentutorials. Name, Alter, belegter Ort/Status kontrastgesichert am unteren Bildrand; kleines Detail-Peek deutet Vertikalscrollen an.
- **Verbindlich:** Decision Rail direkt über der Root-Navigation mit `Undo`, `Nein`, `Später`, `Gefällt`; Nein/Gefällt primär, Undo/Später sekundär. Favorit ist separat erreichbar. Die sichtbare Karte bleibt bis zur bestätigten Entscheidung oder eindeutigem Kontextwechsel bestehen. Keine zweite Decision Rail innerhalb des Profils.

**B – Details nach vertikalem Scrollen:**
- **Verbindlich:** **Derselbe vertikale Scrollfluss**; kein zweites Modal, keine zweite Profilroute, kein verschachtelter Detail-Scroller. Oberer Kontext scrollt aus oder kollabiert koordiniert. Wenn das Identitäts-Overlay nicht mehr sichtbar ist, ein kompakter Profilheader mit belegter Identität und verfügbarem Status – ohne doppelte dauerhafte Namensüberschrift.
- **Verbindlich:** Rail bleibt für das aktuell sichtbare Profil erreichbar. Details werden vertikal gescrollt, horizontale Profilentscheidung nur am Hero. Tap auf kompakte Identität oder ein zugänglicher „Zum Foto“-Befehl führt zum Hero zurück, ohne neue Navigation. Zurückscrollen erhält den gewählten Galerieindex.
- **Verbindlich:** Headerwechsel ohne Layoutsprung, ungewolltes Neuladen oder Scroll-Reset; aktive Fehler, Teilresultate und Ownerwarnungen bleiben sichtbar. Standalone verwendet denselben Inhalt und Galerie, aber Back statt Deck-Rail und **keine** Swipe-Entscheidung.

### Messbarer Flächenvertrag und responsive Darstellung

- **Verbindlich:** Die v3-Komposition mit **zwei** kompakten Kontext-/Moduszeilen ersetzt die alte Vorgabe von maximal einer Zusatzzeile. Damit sind auch die früheren festen Vorgaben „Hero-Beginn ≤14 % H“ und „Hero-Höhe mindestens 65 % H“ aufgehoben. Diese Werte dürfen nicht parallel zum neuen Layout erzwungen werden.
- **Richtwert:** Browserreferenz bei 390 × 844 CSS-px ungefähr `x=12`, `y=171`, Breite `366`, Höhe `534` (ca. 63 % der Gesamthöhe). Keine unveränderte dp-Übertragung; `H` ist nativ **nach Systeminsets** und mit Header, Rail und Root-Navigation zu messen. Hero so groß wie sinnvoll, ohne Touchziele, Identität, Rail oder Status zu verdecken.
- **Verbindlich:** Bei 360 × 740, 390 × 844, 430 × 932 und 600 dp, hoher Schriftgröße, kurzen Höhen, Querformat und IME adaptiv reagieren. Zuerst unnötige sekundäre Controls/Abstände reduzieren, dann Peek verkleinern; erst danach den Hero stärker verkleinern. Kein abgeschnittener Inhalt, zweite Scrollachse oder fehlende Aktion. Breitere Fenster begrenzen Inhalte, skalieren nicht den ganzen Screen.

### Informationshierarchie — progressive Offenlegung

**Verbindlich:** Tatsächlich belegte Informationen gemäß [PRODUCT](PRODUCT.md#profile-medien-und-kontakte) vollständig erreichbar halten, bei fehlenden optionalen Feldern leere Gruppen weglassen. Kürzere Bezeichnungen und progressive Abschnitte statt redundanter Karten, Pills oder erneut angezeigter Identität. Zustands-/Konfliktwarnungen nicht hinter einer eingeklappten Sektion verstecken.

| Priorität | Bereich | Sichtbarer Umfang |
| --- | --- | --- |
| 0 | Identität und Status | Name/Alter/Ort/Verfügbarkeit soweit belegt; Owner-, Archiv- und Fehlerhinweise |
| 1 | Übersicht | Öffnungszeiten, wichtige belegte Merkmale, Anzeigenanzahl soweit verlässlich; auf kleinen Screens eine Spalte |
| 2 | Beschreibung | Mehrzeiliger Auszug, vollständiger Originaltext über „Mehr anzeigen“; kein KI-Resümee |
| 3 | Dienste und Filterbelege | Kompakte Gruppen mit eindeutigen Ja/Nein/Unbekannt-Belegen; keine geglättete Quellenunsicherheit |
| 4 | Kontakte | Telefon/WhatsApp/Original nur bei gültigen Daten und bewusster Handlung; Favorit getrennt |
| 5 | Analyse / Identität | Kandidaten mit Evidenz, ausdrücklich bestätigte Operation, keine automatischen Merges |
| 6 | Meine Notiz | Personennotiz, Entwurf/Fehler/Konflikt schon im Abschnittskopf; Save/Retry/Conflict ohne Datenverlust |
| 7 | Meine Begegnungen / Manuelle Angebotspreise | Getrennte lokale Sektionen, bedarfsgesteuerter Editor, frühere Listen, bezahlte Beträge nur aus Begegnungen |
| 8 | Verlauf / verknüpfte Anzeigen | Echte Änderungen und Snapshots mit explizitem Vergleich, relevante Zeit-/Filterfunktionen und Original-URLs |

- **Verbindlich:** Analyse, Notiz, Begegnungen, Angebotspreise und Verlauf dürfen eingeklappt starten. Öffnen lädt nur erforderliche lokale Daten; keine erneute fachliche Mutation. Ungespeicherte Entwürfe, Commit-/Readfehler, Ownerwechsel und Konflikte müssen in den bestehenden nativen Editorverträgen verarbeitet werden. Personennotiz und Begegnungsnotiz bleiben getrennt; Angebotspreise nicht mit gezahlten Beträgen verwechseln.
- **Richtwert:** Gruppenabstände 12–16 dp, innerhalb 4–8 dp, wenige Divider. Scanbare Labels und Werte; bei langen Angaben oder Font Scale auf einspaltige Darstellung wechseln. „Mehr anzeigen“ und aufklappbare Bereiche halten ihren Scrollanker stabil.

### Gesten und Präsentation

**Verbindlich – Galerie, Video und Handlungsvorrang:**
- Medien in Quellreihenfolge; verfügbare lokale Archivdatei bevorzugen, dann zulässigen Remote-Fallback, bei Fehler Platzhalter mit Retry. Original/Fit als ausdrücklich erreichbare Medienaktion im Profilmenü, nicht als dauerhafte Galerie-Chrome.
- Seitliche Tapzonen wechseln Bilder, am Rand ohne Überlauf. Bei Video mittiges Play/Pause mit Vorrang vor Tapzonen; Wiedergabe eingebettet und stumm, Autoplay/Loop nur bei sichtbarem Medium und gültigem Lifecycle. Stop/Surface-/Tempfile-/Retry-Grenzen gemäß [ARCHITECTURE](ARCHITECTURE.md#persistentes-archiv-und-anzeige). Keine Medienarchivierung durch reines Anzeigen.
- Kindinteraktionen (Video, Menü, Zurück, Vollbild, Retry, Kontakt, Editor, Rail) gehen Gesten vor. Standalone besitzt Galerie-Taps, aber keine Entscheidungs-Swipe-Geste.

**Verbindlich – Entscheidungsgeste:**
- Horizontaler Swipe entscheidet **nur im Hero** bei eindeutig dominanter horizontaler Achse und Distanz mindestens `max(72 dp, 25 % der Kartenbreite)`; Fling allein genügt nicht. Vertikal- und Diagonalscrollen löst keine Entscheidung aus.
- Mehrdeutiger Achsenverlauf, Long-Press vor Lock, Mehrfinger, Cancel, Back, Busy oder Owner-/Run-Wechsel verbieten den Commit. Nach Drag folgt kein Galerie-Tap; Drag-Feedback bleibt rein präsentational. Unterschwellige Bewegung und Writefehler federn zurück; erst bestätigter fachlicher Commit erlaubt den Exit.
- Buttons und Geste verwenden dieselben fachlichen Entscheidungsoperationen. `Später` ist sessionlokal, `Favorit` von Like/Dislike unabhängig. Undo stellt den letzten **noch gültigen vollständigen** Präferenz-/Deckzustand und nach Möglichkeit Foto-, Scroll- und Abschnittsposition wieder her; bei ungültiger Referenz kontrollierter Hero-Fallback. Reset/Replay starten Traversierung neu, löschen keine Präferenzen. Standalone-Präferenz, Rücksetzung auf Neutral und Favoritenwechsel erzeugen keine Deck-Visits.
- Back, Moduswechsel und Rotation erhalten gültige Kontext-/Medienposition; neue Suche oder eindeutig neuer Owner startet am Hero. Bei kleinem Fenster/hoher Font Scale bleiben Rail-Aktionen über zugängliche Alternativen erreichbar; kein dauerhaftes zweites Menü als Hauptbedienung.

## Native Übertragung

**Verbindlich:** Die Referenz definiert Form, Hierarchie und erkennbare Interaktionen, aber keinen zweiten Daten- oder Zustandsvertrag. Bereits vorhandene Compose-Komponenten und ViewModels passend gestalten, statt Web-State, DOM-Ereignisse oder eine neue Navigation nachzubauen.

| Visuelle Referenzrolle | Vorhandener nativer Anker | Fachlicher UI-State / Event |
| --- | --- | --- |
| Shell, Header, Root-Navigation | `LadiesApp`, `ShellRoute`, `LadiesTheme` | Root-Ziel, Back, Insets, Theme |
| Kontext-/Modusumschalter, Swipe, Grid, Rail | `DiscoverModeSwitcher`, `DiscoverScreen`, `SwipeDecisionControls`, `MainViewModel` | Run/Owner, Modus, Like/Dislike/Skip/Undo/Favorit |
| Profilhero, Galerie und Sektionen | `ProfileFlow`, `ProfilePanels`, `GalleryVideo` | gültiges Medium, Scroll/Foto, Busy/Owner, Retry |
| Begegnungen, Preislisten, Notiz | vorhandene `EncounterEditor`, `PriceEditor`, `NoteEditorActions` | Entwurf, CAS/Commit, Konflikt, Read-Retry |
| Suche und gespeicherte Suchen | `SearchScreen`, `MainViewModel`, `SavedSearchController` | Draft/Preview/Run, Run/Cancel, Revision/Ack |
| Karte, gespeicherte Personen | `MapScreen`, MapLibre und vorhandene Saved-Panels | DB-Projektion, Auswahl, Statusprüfung, Favorit |
| Einstellungen und Diagnose | `SettingsScreen`, `MainViewModel`, `CoreStore` | DB-Status, Logging, Capture, Export |

**Richtwert:** CSS-Pixel sind Layoutreferenzen für native dp, Typorollen für sp; eine identische Pixelzahl garantiert weder gleiche Fontmetriken noch Insets. Die HTML-Seiten enthalten keine zwingend zu portierenden Android-Modelle, Serverzugriffe, Backendtests oder Datenschutzannahmen.

## Prüfung und Gestaltungsspielraum

**Verbindlich:** Änderungen an sichtbaren Flows mit ihren tatsächlichen Zuständen und Fachverträgen prüfen, nicht nur mit statischen Mockups. Bestehende Rust-/UniFFI-/ViewModel-Grenzen und vorhandene Tests verwenden; keine neue dauerhafte Browser-Testinfrastruktur für die Android-App einführen.

**Prüfpunkte nach Betroffenheit:**
1. Gemeinsame Header-/Navigation-Rechtecke, Tokens, Radien, Typografie, Kontrast, 48-dp-Ziele, TalkBack/DPAD/Tastatur-Fokus und reduzierte Bewegung; Dark **und** Light.
2. 360 × 740, 390 × 844, 430 × 932 sowie 600 dp; Font Scale 1 / 1,3 / 2, System-/Navigationsinsets, Rotation, IME, lange Werte und kleine Höhen. Hero-Beginn, verfügbare Bildfläche sowie Überdeckung von Rail und Navigation tatsächlich messen.
3. Swipe/Grid identischer gültiger Run, Karte unabhängig; Suchentwurf/Vorschau/Run und letzter Run getrennt; gespeicherte Suche ohne implizite Ack-/Membership-Änderung, Top 5, lokale Filter.
4. Galerie-Tap/Video/Swipe/Scroll/Kind-Vorrang; Like, Dislike, Skip, Undo, Favorit, Profilzurück, Replay/Reset, Fehler/Busy/Cancel und gültige Owner-/Lifecycle-Wechsel.
5. Profilinhalt einschließlich Notiz, Identität, Snapshotvergleich, Begegnungen, Preislisten sowie gesicherte Entwürfe, Konflikte, Read-/Writefehler und begrenzte lokale Reads.
6. SavedPeople, Watchlist, Statusprüfung, MapLibre/Standortauflösung und lokale Diagnose ohne neue Quellzugriffe, fiktive Statusnachweise oder versehentlichen Export.

**Richtwerte und Freiraum:** Größe des Bildausschnitts, sekundäre Abstände, native Material-Details und adaptive Mehrspaltigkeit dürfen anhand von Geräte- und Accessibility-Messungen angepasst werden. Keine neuen Produktfunktionen aus der Bildvorlage ableiten. Die [Referenz](../design-referenz/README.md) mit `index.html`, `styles.css`, `app.js`, `demo-data.js` und lokalen Bildern zeigt ausschließlich die dunkle Gestaltung; **Demo-Orte, Zahlen, Suchergebnisse, Bilder und simulierte Editoraktionen sind nicht normativ**. Die native App bleibt Kotlin/Compose auf Rust-Core und SQLite – keine WebView oder zweite Daten-/Netzwerkimplementierung.

Prüf- und Abschlussbelege gehören zum jeweiligen PR und in [ROADMAP](ROADMAP.md#pflege); die dortige Merge-Regel ersetzt keine behauptete tatsächliche Geräteausführung. Diese Datei enthält bewusst keine M08-Aufgabenstände oder Fertigmeldungen.
