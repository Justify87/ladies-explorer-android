# UI- und Designvertrag

Maßgebliches gestalterisches und interaktives Soll der nativen Android-App. Die [Designreferenz v3](../design-referenz/README.md) zeigt die freigegebene visuelle Richtung mit synthetischen Daten; sie ist keine WebView-Vorlage und kein eigenständiger Produktvertrag. Fachliche Funktionen und Grenzen: [PRODUCT](PRODUCT.md); technische Architektur und Lebenszyklus: [ARCHITECTURE](ARCHITECTURE.md); Aufgaben, Status und Abnahme: [ROADMAP](ROADMAP.md). Agentenablauf: [AGENTS](../AGENTS.md).

## Zuständigkeit und Pflege

**Verbindlich:**
- Dieser Vertrag bestimmt das sichtbare UI-/UX-Soll. `PRODUCT.md` entscheidet über Fachsemantik und erlaubte Funktionen; `ARCHITECTURE.md` über Datenhoheit, Operationen und Integrität. Bestätigte Nutzerentscheidungen haben Vorrang. Ein Screenshot oder Demo-Klick ändert keine dieser Grenzen.
- Die orangefarbene v3-Gestaltung bleibt die historische visuelle Ausgangsrichtung; die native kompakte, flache, bildzentrierte Gestaltung dieses Vertrags hat bei Abweichungen Vorrang. Betroffene Screens und gemeinsame Komponenten bei jeder UI-Änderung gegen das aktuelle Soll prüfen.
- **Verbindliche Regeln** beschreiben erwartbares Verhalten und Zugänglichkeit. **Richtwerte** steuern die Gestaltung, soweit native Messung, Font Scale und Insets es erlauben. **Referenz-/Demodaten** dienen ausschließlich der Illustration. Bestehende Compose-Klassen und CSS-Dateinamen sind technische Zuordnungen, keine erzwungene neue Architektur.
- Funktionen, Datenbedeutungen, Owner-/Cancel-/Lifecycle-Verträge und sicherheitsrelevante Grenzen nicht aus visuellen Vereinfachungen ableiten oder abschwächen. Änderungen am Soll nach [AGENTS](../AGENTS.md#architektur--und-designvertrag) gemeinsam mit den betroffenen Verträgen prüfen; Aufgabenstände und Historie ausschließlich in `ROADMAP.md` pflegen.

## Visuelle Identität

**Verbindlich:**
- Ruhige, flache, bildorientierte und kompakte Oberfläche mit progressiv zugänglichen Informationen; neutrale Flächen in beiden Themes und Orange als primärer Interaktionsakzent. Pure priorisiert die Medien vor dauerhaft sichtbarem UI. Containerflächen nur zur funktionalen Gruppierung einsetzen; keine dekorativen Rahmen, gestapelten Pillen, starken Flächenkontraste oder konkurrierenden Akzente.
- Bestehendes Material 3 Expressive und die semantischen `MaterialTheme`-Rollen verwenden; keine zweite native Tokenbibliothek. [LadiesTheme.kt](../app/src/main/java/de/justify87/ladiesexplorer/ui/LadiesTheme.kt) ist nach nativer Anpassung die konkrete Implementierung. Dynamische Systemfarben nicht standardmäßig aktivieren.
- **Dark und Light Mode gleichwertig unterstützen.** Die HTML-Referenz illustriert nur die dunkle Ausgangsrichtung; Light und Dark nativ abstimmen und prüfen, nicht durch blindes Invertieren. Semantische Statusfarben bleiben in beiden Modi unterscheidbar.
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
| Ablehnen / Gefällt | `--negative`, `--positive` | `#F72F49`, `#4CDA36` als historische Referenz | eigenständige kontrastgeprüfte Aktionsrollen; in Pure dezente getönte Flächen mit farbigem Symbol statt vollgesättigter großer Flächen |
| Favorit / Erfolg | `--favorite`, `--success` | `#D26BEC`, `#5DE44E` | voneinander getrennte Statusrollen |
| Tastaturfokus | `--focus` | `#FFC18D` | sichtbarer Fokusindikator |

Für klein gesetzten Akzenttext ist der **dunkle** Vordergrund vorgesehen (ca. 5,47:1 auf Orange); Weiß auf `#FF510A` erreicht nur ca. 3,26:1 und ist für kleinen Normaltext nicht ausreichend. Der tatsächliche Kontrast aller nativen Zustände ist getrennt nach Theme zu prüfen.

**Richtwerte – Maße und Typografie:**

| Rolle | Referenz | Anwendung |
| --- | --- | --- |
| Abstandsleiter | 4 / 8 / 12 / 16 / 24 / 32 dp | konsistente Innen- und Außenabstände |
| Außen / Karten / Abschnitte | 16 / 12 / 16 dp | gemeinsame Screen-Raster |
| Hero-/Kartenradius | 12–16 dp | durchgehende Medienkontur, keine separate Rahmenrundung |
| Gruppierung / Segment / kleine Icon-Flächen | 8–12 dp | gemeinsame Shape-Rollen; Kreise nur bei bewusst kreisförmigen Aktionen |
| Header / Root-Navigation | ca. 56 / 64 dp ohne Systeminsets | kompakte gemeinsame App-Shell; tatsächliche Höhe nach Inhalt und Zugänglichkeit |
| Interaktionsfläche | **mindestens 48 × 48 dp** | alle Buttons, Chips, Marker und Icons |
| `title` / `heading` | ca. 20–22 / 19 sp, abgestuftes Gewicht | kompakter Seitentitel und Abschnittstitel ohne doppelte Dominanz |
| `body` / `label` / `caption` | 14 / 13 / 12 sp als Startwerte | Fließtext, Aktionen, Metadaten |

Benannte Typografierollen mit passenden Zeilenhöhen verwenden; native `sp`-Metriken und lange Beschriftungen haben Vorrang vor CSS-Pixelgleichheit. Alle gemeinsamen Komponenten nutzen diese Rollen statt eigener Spacing- oder Shape-Skalen; alte Grid-/Hero-Maße gelten nicht parallel. Gemeinsamer horizontaler Außenabstand als Richtwert 16 dp, bei kompakten Fenstern 12 dp; bewusst abweichende Vollflächen (z. B. Karte) sind zulässig.

## Komponenten und Interaktion

**Verbindlich:**
- Gemeinsame, fachlich benannte Komponenten für `AppShell`, `AppHeader`, `RootNavigation`, `ViewModeSwitch`, `ContextStrip`, `ProfileCard`, `ProfileHero`, `DecisionRail`, `FilterSection`, `SettingsGroup`, Profilsektionen und Rückmeldezustände verwenden; vorhandene Compose-Strukturen bevorzugen. Gleiche Komponenten haben über alle Screens gleiche Insets, Radien, Farben, Icons und Fokus-/Aktiv-/Deaktiviertzustände.
- Pro Handlungskontext primäre Aktionen klar von sekundären abgrenzen; bei entgegengesetzten Profilentscheidungen Nein/Gefällt gleichrangig. Sekundäre Aktionen durch kleinere sichtbare Flächen und neutrale Töne zurücknehmen, nicht durch kleinere Touchziele. Chips für Filter und Auswahlen statt für jeden Wert, kompakte Zeilen mit Thumbnail, Identität, Status und direkten Aktionen. Details progressiv offenlegen, ohne vorhandene Daten zu verlieren.
- Aktivierung ändert keine Komponentengeometrie. Touchflächen sind mindestens 48 dp groß, auch wenn ihre sichtbaren Icons/Segmente kleiner wirken; Flächen nicht überlappen lassen. Screenlayout mit nativen `Row`/`Column`/`Box`/Lazy-Layouts, keine Screenshot-Koordinaten oder `nth-child`-Korrekturen.
- Icons nach optischer Masse, Strich-/Füllstil und Bedeutung abstimmen. Entdecken verwendet ein Entdeckungs-/Kompasssymbol, Gefällt ein Herz, Gespeichert und Favorit ein Lesezeichen; fachlich getrennte Präferenz- und Favoritenzustände nicht zusammenlegen. Suche und Einstellungen behalten ihre eindeutigen Symbole. Deutsche sichtbare Labels und zugängliche Namen konsistent halten; Swipe als etablierter Gestenbegriff, Raster statt Grid, Rückgängig statt Undo (interne IDs unverändert).
- Native Semantik und eindeutige zugängliche Namen/Zustände für TalkBack, Tastatur und DPAD vorsehen; Fokus sichtbar führen. Texte, Schriftvergrößerung und IME dürfen weder Aktionen abschneiden noch Fokusziele unzugänglich machen.
- Loading, Empty, Unknown, Fehler mit Retry und Partial erkennbar unterscheiden. Keine Nulltreffer aus unbekannten Vorschauen ableiten. Zustands- und Fehlermeldungen bleiben bei betroffenen Aktionen erreichbar, nicht nur hinter einem Menü.
- Keine künstlichen Systemleisten, Entwicklungsmenüs, Dummy-Funktionen oder erfundenen Profilangaben in der nativen Oberfläche. Kontakte und externe Profilaufrufe nur ausdrücklich durch Nutzeraktion. Animationen funktional, unterbrechbar und bei reduzierter Bewegung abgeschwächt.

## Navigation und Bildschirmmuster

### Root-Navigation und Entdecken

- **Verbindlich:** Header, Inhalt, gegebenenfalls Decision Rail und Root-Navigation folgen einem gemeinsamen Scroll- und `WindowInsets`-Vertrag. Fixierte Flächen verdecken weder den letzten Listeneintrag noch aktive Meldungen; Edge-to-Edge, IME und Systemgesten nativ berücksichtigen.
- **Verbindlich:** Vier Root-Ziele `Entdecken`, `Suche`, `Gespeichert`, `Einstellungen` teilen dieselbe App-Bar und Bottom-Navigation. Such- und Einstellungsüberschriften verwenden ein gemeinsames Abschnittsmuster; Branding und Suche bleiben konsistent; Entdecken besitzt eine Schnellwahl statt eines Sammelmenüs. Standalone-Profile besitzen Back/Mehr, aber keine Root-Navigation.
- **Verbindlich:** Entdecken hat die Modi `Swipe`, `Raster`, `Karte` mit demselben Umschalter und unverändertem Platz; ein Moduswechsel startet keine neue Suche. Ausgeführter Suchlauf und neutraler lokaler Ausgangsbestand sind sichtbar getrennt; innerhalb jedes Kontexts teilen alle drei Modi die Mitgliedschaft. Grid-/Kartenprofilöffnung führt in reguläres Swipe mit genau dem gewählten Listing. Android-Back kehrt auch nach Entscheidungen und Undo zum Ursprung zurück; gültigen Grid-Schlüssel samt Offset beziehungsweise Kamera, Zoom, Ausrichtung und Listing-/Owner-Auswahl erhalten. Ungültige Anker kontrolliert ersetzen.
- **Verbindlich:** Alle/Jetzt/Heute im Entdecken-Kontext ist eine Schnellwahl, die unmittelbar einen Suchlauf auslösen darf; eine entsprechende Änderung im Suchentwurf führt ohne ausdrückliches `Suchen` keinen Suchlauf aus. Aktiver Run und neutraler lokaler Bestand sind im Header unterscheidbar; Verfügbarkeit steht in der Schnellwahl, Teilresultate und Fehler bleiben sichtbar. Keine Statistikzeile für Ort, Radius oder Ergebnisanzahl in Entdecken.
- **Verbindlich:** Header, Moduswahl und eingerückte Medien-/Inhaltsbereiche folgen einer gemeinsamen horizontalen Achse. Suchstatus als untergeordnete Metainformation, Titel kompakt, Suche und Schnellwahl als zusammengehörige Ziele mit vergleichbarer optischer Gewichtung und erkennbarem Dropdown. Header-Suchaktion und Root-Suche unterscheiden ihre Bedeutung durch zugängliche Namen; redundante Status-/Titelzeilen vermeiden, Run-/Fehlerinformation erhalten.
- **Verbindlich:** Modusumschalter in allen drei Modi gleich; gleich hohe und breite Segmente, ausgewogener Innenabstand, moderater Radius und stabile Geometrie auch bei Auswahl. Touchziele mindestens 48 dp. Keine doppelte Titelleiste oder Paddingkette.
- **Richtwert:** Header um 56 dp; Modusumschalter um 48 dp Gesamthöhe bei normaler Schrift, sichtbarer Indikator mit etwa 4 dp Innenabstand. Root-Navigation mit ruhiger, dem Hintergrund naher Fläche, dezenter aktiver Markierung und klarer Trennung zur Decision Rail. Beide Viererleisten entweder am selben Raster ausrichten oder durch eindeutig unterschiedliche Anordnung trennen.

### Grid

- **Verbindlich:** Gemeinsame Profilkartenbasis für Entdecken und gespeicherte Suchergebnisse; auf typischen Smartphones zwei Spalten. Entdecken zeigt das Bild mit kleinem belegtem Alter und kontrastgesichertem Statusherz (gefüllt LIKE, grau/ungefüllt NONE, durchgestrichen DISLIKE), ohne Titel, Ort, unteren Informationscontainer oder großen Bewertungsbutton. Statusherz ist nicht interaktiv. Bei fehlendem Medium lesbarer Fallback. Gespeicherte Suchergebnisse behalten Name/Alter und belegten Ort.
- **Verbindlich:** Grid, Swipe und Karte verwenden dieselbe **ausgeführte** Ergebnismenge. Rechts-/Linkswischen auf der Grid-Kachel setzt LIKE/DISLIKE, Tap öffnet das Profil; vertikaler Scroll bleibt unabhängig. Zugängliche Kartenaktionen setzen LIKE/DISLIKE/NONE ohne Geste, die letzte bestätigte Grid-Entscheidung ist im Grid per Undo widerrufbar. Dezentes Bewegungsfeedback ohne optimistische Statusänderung; kein exklusives Grid-Ausblenden, keine zweite fachliche Datenliste oder automatische Quellabfrage durch Moduswechsel.
- **Richtwert:** v3-Gridkarten sind annähernd quadratisch (Web-`aspect-ratio: 1.03`); der alte Richtwert Breite/Höhe 0,77 wird ersetzt. Abgerundete Ecken nach gemeinsamer Medienkartenrolle (12–16 dp). Bei großen Fenstern adaptive Begrenzung und bei Font Scale lesbare Identität vor starrer Bildquote.

### Suche

- **Verbindlich:** Globaler Filtertext, Ort/Radius, Verfügbarkeit, Altersbereich mit zwei Grenzwerten, Größenbereich, Rubrik, Treffpunkt, Kategorien/Merkmale, Services und weitere bestehende Optionen bleiben erreichbar. Gültige Quellenwerte sind keine aus dem Mockup abgeleiteten neuen Filter.
- **Verbindlich:** Top 5 der **abgeschlossenen** früheren Suchläufe zugänglich halten; Sortierung und Zählregeln kommen aus [PRODUCT](PRODUCT.md#parameter-und-top-5). Unterstützte lokale Filter (nur Foto/Video, Likes/Dislikes ausblenden, `ALL`/`AI`/`NATURAL` auf belegten Markern) bedienbar lassen. `NATURAL` bedeutet fehlende KI-Markierung, nicht nachgewiesene Natürlichkeit.
- **Verbindlich:** Filterentwurf, nicht persistierende Vorschau und ausgeführter Suchlauf bleiben unabhängig. Die letzte ausgeführte Trefferliste ist trotz geändertem Entwurf mit Profilöffnung und Rückkehr zugänglich. Gespeicherte Suchen verwenden denselben Editor und respektieren dessen gültigen Entwurfskontext.
- **Verbindlich:** Suchstart mit Loading, Abbrechen, Fehler, Retry und Teilresultat; erfolgreicher Run öffnet Entdecken. Fehler/Abbruch erhalten den letzten bestätigten Run. Vorschau-Count bei nicht bestätigtem Radius bzw. unzuverlässiger Quellzahl **unbekannt** statt null zeigen; Radius nicht automatisch aufweiten. Lokale Sichtbarkeitsfilter beeinflussen nicht den Quellen-Count.
- **Verbindlich:** Kompakter Filtereditor mit gemeinsamen [progressiven Abschnitten](#informationshierarchie--progressive-offenlegung), engeren Innen-/Gruppenabständen, kleiner lesbarer Typografie und kompakten einzeiligen Eingaben; nicht überlappende 48-dp-Ziele, adaptive Breiten, Schriftvergrößerung und IME erhalten. Kein oberer Entwurfs-/Run-Statusblock; letzter gültiger Lauf über eine ausdrückliche Aktion erreichbar. Kompakte schwebend wirkende, abgerundete Aktionsleiste mit `Reset` und `Suchen`, dessen Count eine belegte Quellvorschau, einen ausdrücklich letzten lokalen Count oder unbekannten Wert kennzeichnet. Sekundäre Aktionen öffnen letzte Ergebnisse bzw. speichern die aktuelle Suche als Standard; keine redundanten Erläuterungstexte. Bei kurzen Fenstern liegt die Leiste inline im Scrollfluss; alle Ziele bleiben zugänglich.
- **Verbindlich:** Filtertext findet Gruppen und Einzelwerte als Teiltreffer ohne Beachtung der Großschreibung. Nicht passende Optionen und Gruppen ausblenden, passende Abschnitte vorübergehend öffnen; normale Öffnungszustände beim Tabwechsel und nach Löschen des Filtertexts erhalten. Lange Auswahltexte umbrechen statt abschneiden.

### Gespeichert

- **Verbindlich:** Favoriten, Likes und Dislikes getrennt mit Counts, Sortierung und korrekter Personenzuordnung. Watchlist „Neu & geändert“ (36 h, zunächst drei Einträge, ausklappbar) und explizite Einzel-/Batchstatusprüfung samt Fortschritt, Abbruch und Teilfehlern zugänglich halten.
- **Verbindlich:** Gespeicherte Suchen bieten Übersicht, Editor, letzten Offline-Ergebnisbestand, Delta-/Neu-Indikator, Run und **ausdrückliche** Quittierung. Öffnen, Reload, Rückkehr oder Pagination bestätigen keine Neuigkeiten. Nicht unterstützte Definitionen erkennbar eingeschränkt, nicht still verändert.
- **Verbindlich:** Quellenstatus nur aufgrund belegter Beobachtung als aktiv/nicht verfügbar zeigen. Letzte Sichtung, Änderung und Statusprüfung begrifflich und zeitlich trennen; unbekannte bzw. historisch nicht belegte Inaktivität bleibt unbekannt. Lokales Neuladen ist keine Quellenstatusprüfung.
- **Richtwert:** Kompakte Zeilen mit Thumbnail, Name/Alter, Ort, Status, Zeitbezug, Anzeigenanzahl, Herz/Mehr; weder Status noch Count allein durch Farbe vermitteln.

### Karte und Einstellungen

- **Verbindlich (Karte):** Nur geografisch zuordenbare Listings der gemeinsamen Entdecken-Mitgliedschaft; kein eigener Favoritenfilter. Ungefähre Stadt-/PLZ-Bereiche unterscheidbar kennzeichnen; Marker/Cluster und gemeinsame Auswahl gleicher Orte mit Profilöffnung. Kein GPS, keine exakten versteckten Orte, keine Ersatzkoordinaten, kein Routing. Automatische lokale Standortaufbereitung und Kartenbefüllung ohne manuelle Beschaffungs-/Reload-Aktion, abbrechbar; gespeicherte Ortslagen, Teilresultate, Fehler/Style-Retry und Auswahl/Kamera bei gültigem Tab-/Profilwechsel erhalten. PLZ-/Stadtmarker durch Farbe und Größe unterscheiden; zugängliche lokale Auswahl auch bei Kartenfehler. Keine permanente Statistik oder Ortslagen-Erläuterung. GeoNames- und Kartenattribution sichtbar halten; optionales MapLibre-Logo ausblenden.
- **Verbindlich (Einstellungen):** Kompakte Einstellungsübersicht mit navigierbaren Untermenüs Suche, Entdecken und Diagnose und Fehlerbehebung. Unter Suche zwei unabhängige Schalter für globale Like-/Dislike-Ausblendungen, unter Entdecken Replay-Likes; lokale Suchfilter bleiben als suchbezogene Regeln erkennbar. Unter Diagnose und Fehlerbehebung echten lokalen DB-/Core-Status, INFO/DEBUG-Diagnose, bewussten HTTP-Capture-Modus und TXT/HAR-Aktionen abbilden. Gemeinsamer Header mit Back zur Übersicht; Android-Back gleichwertig, Untermenü bei Wiederherstellung erhalten. Capture Aus bleibt nach Neustart Standard, keine automatische Weitergabe oder fingierte Statusbestätigung. Fachsemantik und Aufzeichnung gemäß [PRODUCT](PRODUCT.md#einstellungen-und-lokale-diagnose), Maskierung gemäß [ARCHITECTURE](ARCHITECTURE.md#diagnoseinfrastruktur).
- **Richtwert:** Karte füllt den verfügbaren Bereich zwischen Modusumschalter und Root-Navigation ohne äußeren Kartencontainer; Auswahl, Fehler und lokale Profilauswahl als kompakte Overlay-Bedienelemente oberhalb der Attribution. Einstellungen in wenigen funktionsbezogenen Gruppen, nicht als Sammlung gleichwertiger großer Karten.

## Profile und Medien

**Verbindlich – Priorität:** (1) richtige Identität und fachlich gültige Entscheidung ohne Datenverlust; (2) eindeutige Gesten- und Scrollzuordnung; (3) großes Medium; (4) kompakte zugängliche Details; (5) Dekoration. Swipe und Standalone verwenden denselben bestehenden Profilinhalt mit getrennten Navigations- und Operationskontexten. Alle tatsächlich vorhandenen Felder bleiben zugänglich; fehlendes Alter/Status/Preise niemals schätzen.

### UX-Vertrag — zwei Zustände, ein Scrollfluss

**A – Start am Hero:**
- **Verbindlich:** Kompakter Entdecken-Header mit Schnellwahl, unmittelbar darunter Moduswahl und großer Swipe-Hero mit durchgehender, proportionserhaltender Medienfläche nach dem [Flächenvertrag](#messbarer-flächenvertrag-und-responsive-darstellung). Kein unteres Textoverlay oder Scrim; Name, Alter und belegter Ort/Status folgen im Detailfluss. Oben rechts ein kleines halbtransparentes kontrastgesichertes Lesezeichen vollständig innerhalb der sichtbaren Medienfläche, mit konstantem Innenabstand und separatem 48-dp-Ziel. Keine dauerhafte Galerie-Leiste, Storysegmente, Pfeile oder Gestentutorials. Bei mehreren Medien darf ein dezenter, nur bedarfsweise sichtbarer Positionsindikator die Orientierung unterstützen, sofern er weder Tapzonen noch Bildfläche oder Fokus beeinträchtigt; bei einem Medium entfällt er. Standalone verwendet ebenfalls keinen Identitäts-Overlay.
- **Verbindlich:** Decision Rail direkt über der Root-Navigation mit `Rückgängig`, `Nein`, `Später`, `Gefällt`; Nein/Gefällt primär und visuell gleichrangig, Rückgängig/Später sekundär mit kleineren sichtbaren Flächen bei unverändert mindestens 48 dp großen Zielen. Ruhige semantische Rot-/Grüntönung, klare Icons und optisch ausgerichtete Beschriftungen; Später als Zurückstellen (z. B. Uhr), nicht als Mediensteuerung darstellen. Favorit ist separat erreichbar. Die sichtbare Karte bleibt bis zur bestätigten Entscheidung oder eindeutigem Kontextwechsel bestehen. Keine zweite Decision Rail innerhalb des Profils.

**B – Details nach vertikalem Scrollen:**
- **Verbindlich:** **Derselbe vertikale Scrollfluss**; kein zweites Modal, keine zweite Profilroute, kein verschachtelter Detail-Scroller. Oberer Kontext scrollt aus oder kollabiert koordiniert. In beiden Kontexten folgen auf die Galerie unmittelbar die gültigen Kontaktaktionen und der gemeinsame, dezent akzentuierte Anzeigentitel mit belegtem Alter/Ort und Status statt „Übersicht“. Standalone übergibt die Identität an den kompakten Header außerhalb der sichtbaren Hero-/Identitätszeile. Keine doppelte Namensüberschrift, Scrollaufforderung oder technischen Metadaten-Pills.
- **Verbindlich:** Rail bleibt für das aktuell sichtbare Profil erreichbar. Details werden vertikal gescrollt, horizontale Profilentscheidung nur am Hero. Tap auf kompakte Identität oder ein zugänglicher „Zum Foto“-Befehl führt zum Hero zurück, ohne neue Navigation. Zurückscrollen erhält den gewählten Galerieindex.
- **Verbindlich:** Headerwechsel ohne Layoutsprung, ungewolltes Neuladen oder Scroll-Reset; aktive Fehler, Teilresultate und Ownerwarnungen bleiben sichtbar. Standalone verwendet denselben Inhalt und Galerie, aber Back statt Deck-Rail und **keine** Swipe-Entscheidung.

### Messbarer Flächenvertrag und responsive Darstellung

- **Verbindlich:** Entdecken verwendet eine kompakte Moduszeile unter dem Header mit integrierter Schnellwahl. Der Hero nutzt die frei werdende Fläche ohne Überdeckung der Bedienziele. Am ruhenden Hero-Start dürfen nachfolgende Kontakt-/Detailaktionen weder als abgeschnittene Fragmente sichtbar sein noch unter Rail/Navigation liegen. Die vollständigen Details folgen durch bewusstes vertikales Scrollen im selben Fluss; bei kurzen Fenstern bleibt die Inline-Rail erreichbar. Insets und reale gemessene Leistenhöhen nur einmal budgetieren.
- **Verbindlich:** Bild und Hero besitzen eine gemeinsame geclippte Außenkontur; keine separaten grauen Rahmenbalken oder künstlichen schwarzen Kanten. Medien proportional darstellen, ohne Streckung. Bei geeigneten Formaten eine flächenfüllende Darstellung mit maßvollem Crop; wenn wichtige Inhalte oder extreme Formate verloren gingen bzw. nicht zuverlässig beurteilbar sind, vollständige Fit-Darstellung mit bewusst ruhiger integrierter Restfläche. Vollständiges Medium bleibt ausdrücklich erreichbar; Videos proportional und ohne unbeabsichtigten Beschnitt. Kein erzwungener Crop nur zur Erfüllung eines Flächenziels. Favorit, Video, Retry und Übergangsbild nutzen dieselbe gemessene Geometrie.
- **Richtwert:** Bei normalem Portrait, Schriftgröße 1 und geeignetem Hochformatmedium etwa 68–72 % tatsächlich sichtbare Bildhöhe bezogen auf das gesamte Fenster inklusive Systemleisten anstreben; Ausgangsscreenshot etwa 57 %. Zusätzlich Hero-, Bild- und Chrome-Höhen relativ zur nativen Nutzfläche nach Systeminsets erfassen. Der Zielbereich ist kein Mindestwert bei anderem Seitenverhältnis, Warnungen, Accessibility oder kurzen Fenstern; priorisiert werden vollständige Information, sichere Bedienung und stabile Geometrie.
- **Verbindlich:** Bei 360 × 740, 390 × 844, 430 × 932 und 600 dp, hoher Schriftgröße, kurzen Höhen, Querformat und IME adaptiv reagieren. Zuerst unnötige sekundäre Controls/Abstände reduzieren; kein erzwungener angeschnittener Detail-Peek. Erst danach den Hero stärker verkleinern. Kein abgeschnittener Inhalt, zweite Scrollachse oder fehlende Aktion. Breitere Fenster begrenzen Inhalte, skalieren nicht den ganzen Screen.

### Informationshierarchie — progressive Offenlegung

**Verbindlich:** Tatsächlich belegte Informationen gemäß [PRODUCT](PRODUCT.md#profile-medien-und-kontakte) vollständig erreichbar halten, bei fehlenden optionalen Feldern leere Gruppen weglassen. Kürzere Bezeichnungen und progressive Abschnitte statt redundanter Karten, Pills oder erneut angezeigter Identität. Zustands-/Konfliktwarnungen nicht hinter einer eingeklappten Sektion verstecken.

| Priorität | Bereich | Sichtbarer Umfang |
| --- | --- | --- |
| 0 | Kontakte | Telefon und WhatsApp direkt nach der Galerie, bei gültiger Nummer farblich unterscheidbare Theme-Buttons; Originalprofil als explizite Profilaktion, Favorit getrennt |
| 1 | Anzeigentitel und Fakten | Akzentuierter tatsächlicher Name mit belegtem Alter, Stadt/PLZ und Verfügbarkeit; danach bei gültigem Suchkontext kompakter, standardmäßig geöffneter Abschnitt „Übereinstimmung mit deiner Suche“ mit Anzahl belegter, nicht erfüllter und unbekannter Kriteriengruppen, konkreten Werten und unterscheidbaren Alternativen; kompakte wichtige Fakten und Anzeigenanzahl soweit verlässlich; Öffnungszeiten und weitere Merkmale zunächst geschlossen |
| 2 | Über das Profil | Zunächst vollständig geschlossen, ohne Vorschau; „Mehr anzeigen“ öffnet den vollständigen Originaltext, erneut schließbar |
| 3 | Dienste | Zunächst geschlossen; eine Überschrift je tatsächlicher Quellgruppe, sämtliche Werte erreichbar; keine zweite Filterbelegliste oder geglättete Quellenunsicherheit |
| 4 | Hinweise und Profilaktionen | Relevante Owner-, Archivfehler- und Konflikthinweise bleiben sichtbar; keine redundanten Status-Pills oder technischen Rohwerte |
| 5 | Analyse / Identität | Kandidaten mit Evidenz, ausdrücklich bestätigte Operation, keine automatischen Merges |
| 6 | Meine Notiz | Personennotiz, Entwurf/Fehler/Konflikt schon im Abschnittskopf; Save/Retry/Conflict ohne Datenverlust |
| 7 | Meine Begegnungen / Manuelle Angebotspreise | Getrennte lokale Sektionen, bedarfsgesteuerter Editor, frühere Listen, bezahlte Beträge nur aus Begegnungen |
| 8 | Verlauf / verknüpfte Anzeigen | Echte Änderungen und Snapshots mit explizitem Vergleich, relevante Zeit-/Filterfunktionen und Original-URLs |

- **Verbindlich:** Profilbeschreibung, Öffnungszeiten, vollständige Merkmale und Dienste sowie Analyse, Notiz, Begegnungen, Angebotspreise und Verlauf starten eingeklappt. Profil und aufklappbare Suchfilter nutzen dieselbe abgerundete Abschnittsoberfläche mit kompaktem Kopf, Zustandsindikator und mindestens 48 dp großem Ziel; zugehörige Texte, Aktionen und Inline-Editoren liegen innerhalb der Oberfläche. Separate bestätigungspflichtige Editor- und Vergleichsdialoge bleiben erhalten. Fehlende optionale Werte und leere Gruppen ohne pauschale Erklärung weglassen; bei konkreter historischer Preisliste Gültigkeit und Herkunft kenntlich machen. Öffnen lädt nur erforderliche lokale Daten; keine erneute fachliche Mutation. Ungespeicherte Entwürfe, Commit-/Readfehler, Ownerwechsel und Konflikte müssen in den bestehenden nativen Editorverträgen verarbeitet werden. Personennotiz und Begegnungsnotiz bleiben getrennt; Angebotspreise nicht mit gezahlten Beträgen verwechseln.
- **Richtwert:** Gruppenabstände 12–16 dp, innerhalb 4–8 dp, wenige Divider. Scanbare Labels und Werte; bei langen Angaben oder Font Scale auf einspaltige Darstellung wechseln. „Mehr anzeigen“ und aufklappbare Bereiche halten ihren Scrollanker stabil.

### Gesten und Präsentation

**Verbindlich – Galerie, Video und Handlungsvorrang:**
- Medien in Quellreihenfolge; verfügbare lokale Archivdatei bevorzugen, dann zulässigen Remote-Fallback, bei Fehler Platzhalter mit Retry. Vollbild mit vollständiger Fit-Darstellung ist in Swipe im Detailfluss und in Standalone im Profilmenü ausdrücklich erreichbar, nicht als dauerhafte Galerie-Chrome.
- Seitliche Tapzonen wechseln Bilder, am Rand ohne Überlauf. Bei Video mittiges Play/Pause mit Vorrang vor Tapzonen; Wiedergabe eingebettet und stumm, Autoplay/Loop nur bei sichtbarem Medium und gültigem Lifecycle. Stop/Surface-/Tempfile-/Retry-Grenzen gemäß [ARCHITECTURE](ARCHITECTURE.md#persistentes-archiv-und-anzeige). Keine Medienarchivierung durch reines Anzeigen.
- Kindinteraktionen (Favorit, Video, Menü in Standalone, Zurück, Vollbild, Retry, Kontakt, Editor, Rail) gehen Gesten vor. Standalone besitzt Galerie-Taps, aber keine Entscheidungs-Swipe-Geste.

**Verbindlich – Entscheidungsgeste:**
- Horizontaler Swipe entscheidet **nur im Swipe-Hero oder auf einer Entdecken-Grid-Kachel** bei eindeutig dominanter horizontaler Achse und Distanz mindestens `max(72 dp, 25 % der Kartenbreite)`; Fling allein genügt nicht. Vertikal- und Diagonalscrollen löst keine Entscheidung aus.
- Mehrdeutiger Achsenverlauf, Long-Press vor Lock, Mehrfinger, Cancel, Back, Busy oder Owner-/Run-Wechsel verbieten den Commit. Nach Drag folgt kein Galerie-Tap oder Grid-Profil-Tap; Drag-Feedback bleibt rein präsentational. Unterschwellige Bewegung und Writefehler federn zurück; erst bestätigter fachlicher Commit erlaubt den Swipe-Exit beziehungsweise eine Änderung der Grid-Statusprojektion.
- Buttons und Geste verwenden dieselben fachlichen Entscheidungsoperationen. `Später` ist sessionlokal, `Favorit` von Like/Dislike unabhängig. Undo stellt den letzten **noch gültigen vollständigen** Präferenz-/Deckzustand und nach Möglichkeit Foto-, Scroll- und Abschnittsposition wieder her; bei ungültiger Referenz kontrollierter Hero-Fallback. Reset/Replay starten Traversierung neu, löschen keine Präferenzen. Standalone-Präferenz, Rücksetzung auf Neutral und Favoritenwechsel erzeugen keine Deck-Visits.
- Back, Moduswechsel und Rotation erhalten gültige Kontext-/Medienposition; neue Suche oder eindeutig neuer Owner startet am Hero. Bei kleinem Fenster/hoher Font Scale bleiben Rail-Aktionen bei Bedarf inline im Scrollfluss erreichbar. Aktualisierung, Reset, Replay und Profilaktionen sind in Swipe im Detailfluss erreichbar; kein Sammelmenü.

## Native Übertragung

**Verbindlich:**
- Die Referenz illustriert Form, Hierarchie und erkennbare Interaktionen der Ausgangsgestaltung, aber definiert weder das aktuelle native Soll noch einen zweiten Daten- oder Zustandsvertrag. Bereits vorhandene Compose-Komponenten und ViewModels passend gestalten, statt Web-State, DOM-Ereignisse oder eine neue Navigation nachzubauen.
- Der Referenzprototyp bildet den Funktionsumfang nicht abschließend ab. Bestehende und neu freigegebene Funktionen, Bildschirmbereiche und Interaktionszustände ohne visuelle Entsprechung konsistent aus der geltenden Designsprache, den gemeinsamen Komponenten und Interaktionsmustern ableiten. Eine fehlende Referenzdarstellung begründet weder den Ausschluss einer Funktion noch die Einführung einer abweichenden Designsprache.
- Den Referenzprototyp ausschließlich auf ausdrückliche Nutzeranweisung ändern oder erweitern. Bei erkennbarem langfristigem Referenznutzen darf der Agent eine begründete Anpassung vorschlagen. Eine vollständige Synchronisierung mit der nativen App ist nicht erforderlich.

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
2. 360 × 740, 390 × 844, 430 × 932 sowie 600 dp; Font Scale 1 / 1,3 / 2, System-/Navigationsinsets, Rotation, IME, lange Werte und kleine Höhen. Hero-Beginn, Medien-/Containergrenzen, tatsächliche Bildhöhe, freie Randflächen, Header-/Segment-/Rail-/Navigationsbounds, Touchziele, Detailanfang sowie Überdeckung und sichtbare Detailfragmente tatsächlich messen. Synthetische Hoch-, Quer- und Extremformate, Video, fehlende Medien, Mehrfachgalerie sowie ruhender Start und Scrollzustand prüfen.
3. Swipe/Grid/Karte identischer gültiger Run, Profilnavigation mit Rückkehr zum Ursprung; Suchentwurf/Vorschau/Run und letzter Run getrennt; gespeicherte Suche ohne implizite Ack-/Membership-Änderung, Top 5, lokale Filter.
4. Galerie-Tap/Video/Swipe/Scroll/Kind-Vorrang; Like, Dislike, Skip, Undo, Favorit, Profilzurück, Replay/Reset, Fehler/Busy/Cancel und gültige Owner-/Lifecycle-Wechsel.
5. Profilinhalt einschließlich Notiz, Identität, Snapshotvergleich, Begegnungen, Preislisten sowie gesicherte Entwürfe, Konflikte, Read-/Writefehler und begrenzte lokale Reads.
6. SavedPeople, Watchlist, Statusprüfung, MapLibre/Standortauflösung und lokale Diagnose ohne neue Quellzugriffe, fiktive Statusnachweise oder versehentlichen Export.

**Richtwerte und Freiraum:** Größe des Bildausschnitts, sekundäre Abstände, native Material-Details und adaptive Mehrspaltigkeit dürfen anhand von Geräte- und Accessibility-Messungen angepasst werden. Keine neuen Produktfunktionen aus der Bildvorlage ableiten. Die [Referenz](../design-referenz/README.md) mit `index.html`, `styles.css`, `app.js`, `demo-data.js` und lokalen Bildern zeigt ausschließlich die dunkle Gestaltung; **Demo-Orte, Zahlen, Suchergebnisse, Bilder und simulierte Editoraktionen sind nicht normativ**. Die native App bleibt Kotlin/Compose auf Rust-Core und SQLite – keine WebView oder zweite Daten-/Netzwerkimplementierung.

Prüf- und Abschlussbelege gehören zum jeweiligen PR und in [ROADMAP](ROADMAP.md#pflege); die dortige Merge-Regel ersetzt keine behauptete tatsächliche Geräteausführung. Diese Datei enthält bewusst keine Aufgabenstände oder Fertigmeldungen.
