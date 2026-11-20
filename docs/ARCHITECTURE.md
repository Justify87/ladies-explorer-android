# Architektur

Die App ist eine eigenständige lokale Android-Anwendung mit einer APK. Kotlin/Compose (Material 3) verantwortet Darstellung, Navigation, Interaktion und Lebenszyklus; der Rust-Core ist die fachliche Autorität für Quellverarbeitung, Identität, Mutation und SQLite. Die typisierte Grenze bildet beim Gradle-Build generiertes UniFFI/JNA. Kein Server, WebView-Port, Python-/Flask-/Node-Runtime, Cloud-Backend oder zweiter fachlicher Datenstore.

## Verbindliche Grenzen

**Verbindliche technische Invarianten (einschließlich ARC-01–08):**
- Rust-Core und seine SQLite-Persistenz besitzen fachliche Datenhoheit. Kotlin/Compose verantwortet UI und Lifecycle; die generierte, typisierte UniFFI-Grenze verbindet beide.
- Die unten beschriebenen Netzwerk-, Medien- und Sicherheitsgrenzen gelten für alle betroffenen Pfade.
- Fachlicher Commit, UI-Admission und tatsächliches Ende einer nativen Operation sind getrennt.
- Keine zweite fachliche Queue, Datenbank, Profilimplementierung oder Quellen-HTTP-Schicht. Generierte Bindings nicht manuell ändern; Integritäts- und Fachverträge erhalten.

**Refaktorierbarer Ist-Stand:** Die folgenden Komponenten, Klassen und Datenflüsse beschreiben die gegenwärtige Implementierung, keine erzwungene ViewModel-/Gradle-Modularisierung. Verbindliche Grenzen bleiben unverändert. Umgang mit Änderungen: [AGENTS](../AGENTS.md#architektur--und-designvertrag); fachliches Soll: [PRODUCT](PRODUCT.md); UI-/UX-Soll: [DESIGN](DESIGN.md).

## Systemübersicht

```text
Android · MainActivity / LadiesApplication
    Compose · LadiesApp / Screens / ProfileFlow
        MainViewModel · UI-State, Admission, Operationen
        CoreStore · typisierte Cancellable-IO-Fassade
            generierte UniFFI-Bindings / JNA
            LadiesCore · Rust
                SQLite · App-private DB
                Medienarchiv · App-private Dateien
                HTTP-Client · ladies.de / APIs / Medien-CDNs

Coil 3 ── RustImageNetworkClient ── CoreStore ── Rust-HTTP
GalleryVideo ── CoreStore ── Rust-HTTP ── lokale Datei ── libmpv
MapLibre ── eigene Kartenressourcen
Externe Intents ── Dialer / WhatsApp / Originalprofil
```

| Bestandteil | Zuständigkeit und Codeanker |
| --- | --- |
| App-Lebenszyklus | `LadiesApplication` hält CoreStore, stellt Coil bereit; `MainActivity` und `LadiesApp` binden Navigation und Lifecycle. |
| UI | `ui/` enthält Compose-Screens, lokale Präsentationszustände, Gesten, Komponenten und typisierte Controller. Keine direkte SQLite- oder Quellen-HTTP-Verarbeitung. |
| Operationen | `MainViewModel`, `StandaloneProfileOperations`, `SavedSearchController`, `DrainingOperation` koordinieren fachliche Aktionen, Admission, native Ausführung und lokale Projektionen. |
| FFI | `core/CoreStore.kt` hält die private LadiesCore-Instanz; IO-Aufrufe und Diagnose; UI nutzt die Fassade sowie generierte Records und RequestControl, niemals die Engine direkt. |
| Domäne | `rust/ladies-core/src/`: `lib.rs` API/Engine; `source/` Suche/Transport/Parser/Speicherung; `discovery.rs`, `saved_search.rs`, `profile_detail.rs`, `identity.rs`, `history.rs`, `media.rs`, `media_url.rs`, `map.rs`, `operation.rs`. |
| Persistenz | `schema.rs` verwaltet natives Schema und interne Schemaentwicklung. |
| Diagnose | `diagnostics.rs`, `http_capture.rs`, `DiagnosticEventQueue`, CoreStore und Diagnoseprovider. |

Codepfade: [Android](../app/src/main/java/de/justify87/ladiesexplorer), [Rust](../rust/ladies-core/src). Herkunft und Lizenzen: [NOTICE](../NOTICE). UI/UX: [DESIGN](DESIGN.md); Anforderungen: [PRODUCT](PRODUCT.md). Aufgaben und Fortschritt: ausschließlich [ROADMAP](ROADMAP.md).

## Domäne und Persistenz

### Identität und Quellenhoheit

Person ist die kanonische Identität gemäß [PRODUCT](PRODUCT.md#personen-identität-und-historie).
- `(source, external_profile_id)` und `(source, external_listing_id)` sind eindeutig; Zuordnungen nicht stillschweigend verändern.
- Quellsnapshots, Beobachtungen und Nutzerzustand getrennt persistieren.

| Bereich | Native Tabellen |
| --- | --- |
| Identität/Nutzerzustand | `persons`, `person_user_state`, `source_profiles`, `listings`, `person_identifiers` |
| Inhalt/Historie | `listing_snapshots`, `snapshot_values`, `listing_observations`, `change_events` |
| Medien | `media_assets`, `media_sources`, `snapshot_media` |
| Suche | `search_runs`, `search_results` |
| Gespeicherte Suchen | `saved_searches`, `saved_search_results` |
| Identitätsevidenz | `identity_evidence`, `identity_events` |
| Weitere Fachdatensätze | `encounters`, `encounter_services`, `price_lists`, `price_items` – Schema, keine daraus ableitbare vollständige Bearbeitungsoberfläche |
| Einstellungen | `app_settings` |

### SQLite-Vertrag

- **Ablage/Identität:** App-private Datenbank `filesDir/ladies.sqlite`; `user_version = 2` und `application_id = 0x4C445850` (LDXP). Eigenständiger Android-Datenbestand ohne nutzerseitige Übernahme gemäß [PRODUCT](PRODUCT.md#produktziel-und-grenzen).
- **Schemafreigabe:** Installation und interne Schemaentwicklung transaktional vor Store-Freigabe. Native v1 muss dem kanonischen Schema entsprechen; v2 einschließlich Tabellen, Indizes und Triggern validieren.
- **Ungültige Schemata:** Fremde IDs, unerwartete unversionierte Tabellen, Schemaabweichungen oder unbekannte neuere Versionen erzeugen typisierte Fehler. Die exakt bekannte Demo mit `scaffold_notes` ist ein unterstützter technischer Migrationsfall; echte Nutzerdaten nicht implizit löschen.
- **Connection:** `foreign_keys=ON` je Verbindung; Rust-Mutex schützt die Connection. Öffentliche Mutationen nutzen parametrisierte SQL-Anweisungen und Transaktionen.
- **Owner-Integrität:** Person-INSERT erzeugt Nutzerstatus durch Trigger. Trigger verhindern unzulässige Personen-/Quellenzuordnung von Inseraten und Quellprofilen; Sichtungen aktualisieren die je Quelle zugehörigen `last_seen`-Zeitpunkte.
- **Keyset-Cursor:** `PersonCursor` über `last_seen_at`/`person_id`, `DiscoveryCursor` über Favorit/Inserat-`last_seen_at`/`listing_id`; jeweils absteigend und Seitengrößen 1–100. Suche, SavedSearch und SavedPeople besitzen eigene Fensterverträge.
- UI enthält weder direkte SQL-Zugriffe noch eine alternative Persistenz.

### Abzulösender Legacy-Import (Ist-Abweichung)

**Ist-Abweichung vom [Produktsoll](PRODUCT.md#produktziel-und-grenzen), keine Nutzungsempfehlung:**
- Vorhandener Pfad: Einstellungen/Dateiauswahl → `MainViewModel` → `CoreStore.importLegacyBundle` → UniFFI → `legacy_import.rs`. `tools/export-legacy.sh` und bestehende CI-/Fixture-Tests referenzieren diesen Altpfad.
- Suche und Import sind gegenseitig exklusiv. Home-Status und Import teilen einen Owner; Statusrefresh wird koalesziert, ohne Importfehler zu verdecken.
- Während des Imports bleiben Root/Back gesperrt; betroffene ungespeicherte Notizentwürfe sind geschützt.

**Bis zur tatsächlichen Entfernung verbindliche Schutzregeln:**
- Nur in leere DB importieren, vollständig transaktional; native-v1-Tabellen und -Spalten strikt prüfen.
- Fremdschlüssel, Owner und Medien-SHA-256 prüfen. Ungültiges Archiv oder fehlende Dateien ohne Überschreiben und ohne Teilimport ablehnen.
- Historische Definitionsformate in `saved_search.rs` strikt dekodieren; fachliche Sperre gemäß [PRODUCT](PRODUCT.md#gespeicherte-suchen).

Kein Auftrag zur Erweiterung oder Nutzerdatenmigration. Entfernung und native synthetische Tests: [DC02](ROADMAP.md#unabhängige-technische-bereinigung).

### Historisierung und Identitätsoperation

**Snapshots und Übergänge:**
- Profilabrufe speichern Snapshot, PROFILE-Beobachtung und tatsächliche Feld-/Medienänderungen in **einer Transaktion**.
- Fingerprints berücksichtigen die Kategorie; identische Inhalte erzeugen Beobachtungen, keine künstlichen Änderungen.
- Rückkehr zu früherem Inhalt aktiviert dessen Snapshot und dokumentiert den echten Übergang vom vorher aktiven Snapshot.

**Evidenz und Kandidaten:**
- Normalisierte Telefonnummer, Quellprofil-ID und 64-bit-dHash liefern Kandidatenevidenz.
- Bildhash-Abstand bis 8 kann Evidenz sein; unabhängige Bildpaare eindeutig paaren, Gleichstand nicht bevorzugen.
- Fremd zugeordnete Quellprofil-IDs bleiben erhalten; Konflikt dedupliziert als Evidenz protokollieren. Keine automatische Zusammenführung.

**Expliziter Merge / native Commit-Autorität:**
1. Gemeinsamer Identitätsvorgang bindet Listing, erwarteten Source-Owner, gerenderten Kontext und Kandidat. UI-Admission schützt betroffene ungespeicherte Entwürfe.
2. Rust validiert Owner und aktuelle Evidenz in **derselben Transaktion**. Widersprüchliche explizite LIKE-/DISLIKE- oder `repeat_interest`-Werte verhindern den Merge atomar; weitere Fachbedingungen: [PRODUCT](PRODUCT.md#personen-identität-und-historie).
3. `NONE` beziehungsweise `UNKNOWN` übernehmen den bekannten Wert; Favoriten werden per OR vereinigt und nichtleere gespeicherte Notizen zusammengeführt. Nutzerzustand, Inserate, Quellprofile, Kennungen, Begegnungen und Historie bleiben erhalten; historische Personen-IDs in `identity_events` bestehen fort.
4. Nach bestätigt erfolgreichem Commit betroffene lokale Projektionen, Entwürfe, Dialoge und Swipe-Slots invalidieren beziehungsweise neu lesen. Bei anschließendem Readfehler die Mutation **nicht** wiederholen.

## Quellzugriff und Medien

### Netzwerkgrenze

Alle app-internen HTTP(S)-Anfragen an ladies.de, APIs, Subdomains und zugehörige Medien-CDNs laufen ausschließlich über Rust. Kotlin hat keinen eigenen Quellen-HTTP-Client. MapLibre lädt eigene Kartenressourcen; bewusst ausgelöste externe Intents sind keine app-internen Quellanfragen.

### Externe Datenquelle und Parservertrag

**Transport und Admission:** `source/filter.rs`, `network.rs`, `parse.rs` und `storage.rs` übernehmen Filter-/URL-Kanonisierung, HTTP, Parsing und transaktionale Speicherung.
- Cookie-Jar, HTTPS, eigener Redirect-Handler mit maximal fünf Redirects und Host-Allowlist; 25 s pro Request, 8 MiB Antwortlimit und 350 ms Pagination-Pause.
- `RequestControl` prüft Cancel vor/in der Suchtransaktion und vor Commit: davor Rollback; bestätigte Commits bleiben bestehen. Blockierende Requests können bis Timeout laufen.

**Parser und Vollständigkeit:**
- HTML-Seite 1 liefert Karten und SearchConfig. JSON-API-Folgeseiten liefern `content`, `pagination.totalPages`, `advertCount` und `distanceSearched` (String). Nach Anzeigen-ID deduplizieren.
- Bei unbekannter HTML-Leerstruktur API-Seite-1-Recovery **nur** bei Karten und exakt bestätigtem Radius. Fehlende Metadaten können Seite 2 erfordern.
- Explizites Nullsignal und behandelter erster HTTP-404-Fall: `partial=false`, `radius_verified=false`; kein bestätigter Delta-Nulltreffer.
- Fehlende Radiusangabe regulärer Seiten: `partial=true`, `radius_verified=false`, `distance_searched=NULL`.
- Radiusabweichung auf Seite 1: Fehler; später: Teilresultat.
- Erkannter Radiusübergang ist nur bei bestätigter Vollständigkeit ein reguläres Ende. Sonst `partial`, auch bei Count-Abweichung, Seitenlimit oder fehlender erwarteter Folgeseite. Fachliche Folgen: [PRODUCT](PRODUCT.md#suche-und-filter).

**Filter und Vorschau:**
- Filterkatalog kanonisiert Gruppen, Aliase und Slugs; Orts-API-Schlüssel case-insensitiv mit lokalem Slug-Fallback.
- Preview nutzt denselben geprüften Seite-1-Abruf mit Fallback, 350 ms Debounce, Cancel und Generation; keinerlei Persistenz.
- Häufige Filter kommen aus `search_runs`, ohne weiteren Store.

**Zulässiger Profilabruf:**
- Nur lokal bekannte Inserate, HTTPS, erlaubte Hosts/Präfixe `/sex-anzeigen/`, `/fkk-saunaclubs/`, `/stars/` und kanonische www-URL; keine frei eingegebene URL.
- Vorhandene HTML-ID gegen erwartete ID prüfen. Relative, protokollrelative und CSS-Medienreferenzen auflösen, Reihenfolge bewahren; A- vor F-Variante derselben Foto-ID, Revisionsparameter sowie 18+-/Sterne-Marker erhalten.
- Such-/API-/Profilstruktur und CDN-Varianten durch frühere lokale HARs und synthetische Regressionen belegt. Keine frische Sitzung/Alters-/Cloudflare-Freigabe daraus ableiten. Selektoren und Klassifikationen nur mit neuer Evidenz ändern; keine privaten HARs in Git.

**Statuschecks:** Statusantworten und Delta-Commit verwenden Owner-/Versionsprüfungen unter [Gespeicherte Personen und Suchen](#gespeicherte-personen-und-suchen). Statusbatch sequenziell, mit 350 ms Pause und Cancel bei Navigation/`ON_STOP` oder Aktion; keine Inhalts-/Archivoperation. Fachliche Statussemantik in [PRODUCT](PRODUCT.md#gespeicherte-personen).

### Persistentes Archiv und Anzeige

**Archiv und Snapshots:**
- App-private Bilder: `ladies_data/media/images/`. SHA-256-Deduplizierung; JPEG/PNG/WebP/GIF-Dimensionen und Pixelbudget vor voller Dekodierung prüfen. Der 64-bit-dHash verwendet Legacy-kompatiblen Links-/Rechtsvergleich mit LANCZOS.
- Archivierung folgt dem Snapshot als **separater** Vorgang. Fehler oder Cancel entfernen keine gespeicherten Profildaten oder bereits archivierten Dateien. Medienrevision und lokale Existenz steuern Wiederverwendung.
- Videoreferenzen werden standardmäßig ohne dauerhaftes Videoarchiv gespeichert; Wiedergabe über vorhandene Archivdatei oder temporäre lokale Anzeigedatei. Ein opt-in-Archivierungspfad existiert im Rust-API.

**Medienabrufgrenzen:** Rust prüft bekannte HTTPS-Hosts und URL-Formen, verbietet Redirects und begrenzt Ressourcen auf Bilder 12 MiB, Videos 48 MiB, 24 Mio. Bildpixel, 80 Referenzen pro Archivaufruf, 15 s pro Request und 150 s Gesamtbudget fürs Archiv. [media.rs](../rust/ladies-core/src/media.rs).

- **Bilder:** Coil 3 nutzt lokale Archivdateien zuerst, dann Remote-Fallback über `coil-network-core → RustImageNetworkClient → CoreStore.loadDisplayImage → UniFFI → Rust`. Vier gleichzeitige blockierende Bildanzeigeabrufe sind über den IO-Dispatcher begrenzt. Coil besitzt URL-/Revisionsschlüssel, Memory-/Disk-Cache, Dekodierung und Zusammenführung identischer Cache-Anforderungen; UniFFI überträgt begrenzte kodierte Bytes. Fehler führen zu sichtbaren Platzhaltern. Reine Anzeige archiviert nicht und schreibt nicht in SQLite.
- **Videos:** `GalleryVideo` bindet Player, Medium und lokale Datei an den sichtbaren Galerieeintrag und RESUMED-Lifecycle. Archivdatei zuerst, sonst `CoreStore.loadDisplayVideo → UniFFI → media::display_video` mit Rust-Host-, Größen-, Timeout-, HAR- und Cancel-Vertrag. Eigener IO-Dispatcher mit Parallelität 1. Validierte MP4/WebM-Datei im App-Cache; kein neuer Archiv-/DB-Eintrag. libmpv erhält ausschließlich einen lokalen Pfad, erzwungener lavf-Container und `protocol_whitelist=file` als lavf-Option; `access-references=no`, `config=no`, `load-scripts=no`, `ytdl=no` und `autoload-files=no` sperren externe Referenzen, Konfiguration, Skripte, URL-Helfer und automatisch geladene Begleitdateien. Wiedergabeverhalten gemäß DESIGN; Hardwaredecoder soweit verfügbar. Player stoppt außerhalb der gültigen sichtbaren Projektion bzw. bei Lifecycle-/Busy-Wechsel. Surface-Freigabe nach nativer Zerstörung; erneuter Surface-Owner nur bei weiterhin gültigem Medium. Temp-Dateien werden auch bei Cancel bereinigt; verwaiste Dateien beim ersten Zugriff nach Prozessneustart entfernt.

## Oberfläche und Navigation

`ShellRoute` und `LadiesApp` besitzen Root-Navigation gemäß DESIGN; Entdecken-Modi sind lokale Zustände. Standalone-Profile überlagern die Root-Navigation und behalten den tatsächlichen Ursprung. Status und Back werden vom zuständigen Owner koordiniert; reine Status-Reads sperren Navigation nicht.

### Suche, Entdecken und Grid

`MainViewModel` hält den Filter-Snapshot des ausgeführten Runs getrennt vom Entwurf. Rust liefert `activeSearchCards` in Positions-/ID-Reihenfolge mit aktuellen Owner-/Präferenz-/Favoritenzuordnungen; Grid liest diese aktive Ergebnismenge und nicht die bereits konsumierte Swipe-Queue. Der neutrale Startbestand verwendet Präferenz NONE. Im aktiven Run entscheidet das native Filterergebnis zusammen mit sessionlokaler Traversierung und Replay-Likes-Policy über zulässige Swipe-Cards. Fachliches Verhalten: [PRODUCT](PRODUCT.md#entdecken-und-entscheidungen).

`SwipeSession` besitzt Traversierung und Undo-Records, keinen zweiten fachlichen Store. Geste und Rail verwenden dieselbe gerenderte Admission und native CAS-Präferenzoperation; die Queue-Projektion ist autoritativ. Grid-Herz führt erwartungsgeprüfte LIKE/NONE- bzw. LIKE-Mutation aus; das rungebundene Präsentations-Hide wird bei neuem Run zurückgesetzt, ändert nicht die native Ergebnismenge und verwendet keine Swipe-Historie. Fachliche Entscheidungs- und Fehlersemantik gemäß PRODUCT und [Operationsmodell](#operations--und-konsistenzmodell).

`DiscoveryProjectionPublisher` hält lokale Entdecken-/Grid- und Saved-People-Projektionen aktuell; getrennte Fehlerpfade, gemeinsame Gültigkeitsprüfung. Präsenz- und Altersinformationen kommen aus vorhandener eindeutiger Quell-/Snapshot-Evidenz, ohne dekorative Profilanfragen. Karte besitzt eigene DB-weite Projektion (siehe [Karte und Sicherheitsgrenzen](#karte-und-sicherheitsgrenzen)).

### Gemeinsame Profilarchitektur

`NativeProfileProjection` liest Profil, kanonische Identität, Person, Medien, Historie und Kandidaten unter demselben SQLite-Guard. `ProfileReadModel` bindet diese Projektion, Herkunft/Fehler und lokale Revision. Swipe und Standalone teilen den lokalen Lese- und Präsentationsvertrag, behalten jedoch unterschiedliche Quell-/Archiv-Lebenszyklen:

- Standalone: `StandaloneProfileOperations` liest aktuelle Quelldaten, nutzt bei Fehler den aktiven lokalen Snapshot samt Medien und zeigt die Herkunft/Warnung. Erfolgreiche Profilabrufe können den separaten Archivvorgang auslösen; bei Offline-Fallback kein erneutes Archivieren aus dem Netz.
- Swipe: maximal aktuelles plus zwei folgende Profile, ohne zusätzliche Archivierung. Quellenabruf und lokaler Fallback folgen dem Current-/Prefetch-Vertrag unten.

`ProfileShell` und `ProfileFlow` bilden die gemeinsame Profiloberfläche; Swipe und Standalone behalten ihre jeweiligen Navigations-/Operationskontexte. Sichtbare Gestaltungs- und Informationsanforderungen: [DESIGN](DESIGN.md#profile-und-medien).

`ProfileHeroLayout` berechnet aus tatsächlichen Constraints, Insets und Leisten das adaptive Hero-Budget. Die Header-Identität wird anhand realer Lazy-Geometrie übergeben; die Decision Rail besitzt einen Menü-Fallback. Status und Busy sollen den Viewport nicht unkontrolliert verschieben. Galerie- und Hero-Ziele stehen in [DESIGN](DESIGN.md#profile-und-medien).

`ProfilePanels` und `ProfilePanelReads` (in `ProfilePanelState.kt`) sowie `ProfileFlowRows` steuern sichtbarkeits-/bedarfsgebundene lokale Reads. Facts und PersonDetail werden je gültigem Listing/Owner/Snapshot/Filter/Revision wiederverwendet; Analyse/Historie und Vergleich erzeugen keine zusätzlichen Quellenabrufe durch die Darstellung. Snapshotvergleich prüft Besitzer und Inseratzuordnung. Notiz-Reads/Retry haben einen gemeinsamen projektionsgebundenen Job; Entwürfe und Tickets sind ownergebunden im ViewModel und bleiben bei Navigation/Rotation erhalten (nicht über Prozessende). Speichern ist transaktionaler Vergleich von aktuellem Besitzer und erwartetem Text, schreibt nur private_note und updated_at. Konflikte explizit auflösen; Merge schützt betroffene ungespeicherte Entwürfe.

### Gesten, Präsentation und Undo

`PhotoGestureResolver` klassifiziert den gemeinsamen Hero-Pointer-Stream nach Android-Touch-Slop, gemessenen Grenzen und dominanter Achse; Schwellen und sichtbares Gestenverhalten siehe [DESIGN](DESIGN.md#gesten-und-präsentation). Native Clickables besitzen seitliche Galerie-Taps, Fokus und Semantik; interaktive Kindziele haben Vorrang. Horizontaler Lock wird übernommen, vertikales Scrollen der LazyColumn überlassen. Mehrfinger, Fokus-/Geometrie-/Kontextwechsel, Pause, Back, Cancel oder widerrufene Touch-Intention verhindern den Commit. `ProfileTouchGate` widerruft den gesamten Touch-Stream bis zum letzten Up; Semantik-/Tastaturaktionen bleiben nativ. Standalone nutzt Galerie-Taps ohne Swipe-Entscheidung.

`SwipeFlight` ist nur ein inert gerenderter visueller Übergang, keine optimistische Queue oder fachliche Mutation. Drag bleibt bis Admission/autoritativer Antwort sichtbar; ausgehendes Bild animiert im stabilen Viewport, neuer Profilfluss behält seine Geometrie. Kurzes begrenztes Warten auf Dekodierung und ehrlicher Cache-Fallback; keine künstlichen Snapshots für ungeeignete Medien oder Detailscrollzustände. Während des Flight sind entsprechende Eingaben kurz gesperrt, die fachliche Admission bleibt maßgeblich.

`ProfilePresentation` hält Medien-ID/Index, stabile Row-Keys/Offset und Sektionen getrennt vom fachlichen Read-Model. `SwipeSession` speichert höchstens 16 jüngere Präsentationsreferenzen zu Undo-fähigen Visits, gebunden an Run/Listing/Owner/Snapshot/Filter. Ältere oder unpassende Referenzen fallen kontrolliert zurück. Ein Undo-Receipt wird einmalig nach gültiger Anwendung oder eindeutig nicht passendem Kontext konsumiert; bei Readfehler bleibt es erhalten. Leeres/abweichendes autoritatives Deck verwirft unpassendes Receipt; Rail bleibt bei leerem Deck für gültiges Undo verfügbar.

`ProfileRestoreCoordinator` koordiniert den Wiederherstellungsauftrag, nicht die tatsächlichen Medien-/Scroll-/Sektionsowner. Row-Manifest dient als stabile Schlüsselgrundlage und Datenbedarfsquelle; bei Änderung von Snapshot/Filter wird der Schlüssel geprüft und Pixeloffset auf sicheren Zeilenanfang bzw. gültige Größe begrenzt. Medien-ID hat Vorrang vor begrenztem Index. Kein zusätzlicher Quellenabruf oder dauerhafter UI-Profilcache für Wiederherstellung. Reset hat eine eigenständige Präsentationsgeneration.

### Swipe-Read-Window und Prefetch

`DiscoveryScreen`, `SwipeProfileWindow` und `SwipeProfileReads` halten maximal Current+2 Slots, jeweils an Listing, erwarteten Owner und Ticket/Revision gebunden. Jeder Slot besitzt einen stabilen Effect und RequestControl. Ein Mutex serialisiert Profilreads; Promotion eines laufenden gültigen Prefetch erhält dessen Auftrag statt abzubrechen und neu zu starten. Busy erzeugt keinen neuen Slot. Pruning, Lifecycle-/Route-/Owner-/Revisionswiderruf entziehen alte Tickets. Verspätete native Commits aktualisieren betroffene lokale Projektionen unabhängig von UI-Annahme.

Lokaler archivierter Bestand liest lokal zuerst. Aktuelle erstmalige Freshness und spekulative Cache-first-Reads sind getrennte Policies. Fehler spekulativer Reads warten auf Promotion; lokaler Reprojektionsfehler löst keinen zweiten Source-Read aus. Coil-Warming ist pro Slot, Revision und tatsächlicher Hero-Pixelgröße begrenzt, mit Crop-Skalierung. Kein zweiter Mediencache, keine automatische Archivierung.

## Gespeicherte Personen und Suchen

### Personen und Status

- `saved_people_window` in Rust projiziert Favoriten, Likes und Dislikes nach stabiler Personen-ID.
- Recent (Produktzeitfenster) und Regular samt Favoriten-/Datum-Counts entstehen unter demselben SQLite-Guard und Zeitanker. Nachladen liest ein kohärentes erweitertes Fenster; Kotlin hält nur Kapazität, Auswahl und Scrollzustand.
- Keyset-Cursor bindet Auswahl, Zeitgrenze und SQLite-`total_changes`-Revision. Namenssortierung mit NFD, Case-/Mark-Folding und ß/ss (keine vollständige CLDR-Kollation). Metadaten fallen deterministisch auf vorhandene Daten zurück; Relisting behält fachlichen Ereigniszeitpunkt.

**Statusoperation:**
1. Suche, Profil und explizite Einzel-/Batchchecks nutzen denselben Rust-Statusübergang.
2. Explizite Checks benötigen **vor I/O und erneut beim Commit** gültige gespeicherte LADIES-Mitgliedschaft (Favorit oder Präferenz ungleich `NONE`).
3. Rust prüft vor Commit transaktional Owner, URL, Advert-ID und Beobachtungsrevision.
4. Nur bestätigte Beobachtungen – einschließlich gültiger Suchkarten als positiver `ACTIVE`-Beleg – aktualisieren Status und Statuszeit; Status-only schreibt Beobachtung und bei bestätigtem Wechsel ein Ereignis, aber keine Inhalts-/Personenreihenfolge oder Medieningestion.
5. `SavedPeopleState` hält Fenster und sequenziellen Statusablauf. Navigation/`ON_STOP` cancelt; bereits gespeicherte Teilergebnisse bleiben und werden lokal reprojiziert.

Fachliche Fälle/Fehler: [PRODUCT](PRODUCT.md#gespeicherte-personen).

### Gespeicherte Suchen

**Zuständigkeiten:** `SavedSearchController` besitzt Liste, Template-Session, Editor, Dialoge, Ergebnisfenster und Operationen. Rust besitzt validierte Definitionen, Mitgliedschaft des letzten vollständigen Laufs, Delta-/Membership-Historie sowie revisionierte Rename-/Ack-Operationen.
- Regulärer Entwurf und gespeicherte Definition verwenden denselben typisierten `SearchDraft`/Filtereditor; JSON nur an Core-Schnittstellen.
- Unveränderte Definitionen bleiben byte-identisch. `SearchDraft.from(query, local)` und Updates mit beiden typisierten Anteilen erhalten unterstützte lokale Filter bei reinen Quellenparameteränderungen.

**Ausführung und Delta-Commit:**
1. `saved_search.rs` erfasst **vor dem Quellabruf** Such-ID, rohe `filters_json`-Definitionsbytes und `definition_version`.
2. Nur ein vollständiges **radiusbestätigtes** Ergebnis darf die gespeicherte Mitgliedschaft aktualisieren. Teilresultate und unbestätigte Nulltreffer schreiben kein Delta.
3. **In derselben SQLite-Transaktion unmittelbar vor dem Delta-Commit** Existenz der Such-ID, byte-identische Definition und unveränderte Version erneut prüfen; Cancel nochmals vor Commit prüfen.
4. Geänderte/gelöschte Definition oder Cancel verhindern alte Deltas, **nicht** bereits bestätigte reguläre Suchcommits.

**Versuch/Fehler/Abbruch:**
- `last_attempt_at`/`last_error` für unvollständige Läufe und Quellenfehler nur mit **derselben ID, Version und Definitionsbytes** speichern; Cancel vorher prüfen. Fehler einer alten Definition dürfen nicht auf eine neue gelangen.
- Eine fehlgeschlagene Aktion veröffentlicht kein Ergebnis für einen fremden Kontext.

**Fenster, Revision und Quittierung:**
- Lokale Profilmetadaten zusammen mit dem Ergebnisfenster kohärent projizieren. Keyset-Cursor bindet Such-ID und prozesslokale SQLite-`total_changes`-Revision, **nicht** die Profilrevision.
- Nach relevantem nativen Abschluss sichtbare Fenster lokal aktualisieren, unsichtbare als dirty markieren; Änderungen während eines Reads zusammenfassen.
- List-/Window-/Actionfehler unabhängig halten. Retry liest die bestehende Fenstertiefe; keine stale Aktionen gegen fremden Kontext.
- Explizites Ack nach [PRODUCT](PRODUCT.md#gespeicherte-suchen) bindet die **gerenderte Revision**. UI-Admission und Rust-CAS verhindern ein Ack gegen ein neueres Fenster; bei Fehler neu lesen, nie den Write automatisch wiederholen.
- Bereits zugelassene Ack-Operation darf nach `ON_STOP` bis zum tatsächlichen Abschluss drainieren. Weitere gemeinsame Grenzen: [Operationsmodell](#operations--und-konsistenzmodell).

## Operations- und Konsistenzmodell

Datenhoheit, Render-/Admission-Gültigkeit und tatsächliches Ende einer nativen Operation sind getrennte Verantwortlichkeiten.

- `RequestControl` wird pro nativer Operation verwendet. Cancel signalisiert Rust, invalidiert überholte UI-Publikation und verhindert weitere Arbeit, soweit kooperativ möglich. Vor Suchpersistenz und gespeicherten Deltas prüft Rust Cancel auch vor dem SQLite-Commit. Der laufende blockierende Transport kann bis Timeout dauern.
- `DrainingOperation` hält den nativen Owner bis zum tatsächlichen Abschluss, unabhängig von Navigation oder verworfener UI-Antwort. Suche bleibt bis Drain loading.
- Rendered Tokens binden Kontext/Run-/Decksession oder Standalone-Session, Listing, erwarteten Owner, Snapshot-/Projektionsrevision und fachliche erwartete Werte. UI-Callbacks werden zur Admission gegen den aktuellen Zustand geprüft. Rust ist Commit-Autorität; erwartete Besitzer/Präferenz/Favorit/Notiz/Evidenz werden nativ geprüft. Kein Check-then-write-Ersatz in UI.
- Ein erfolgreich gespeicherter Write wird genau einmal als solcher behandelt. Writefehler und nachfolgender Readfehler unterscheiden sich; Retry nach Commit ist eine lokale Reprojektion. Kein stilles Wiederholen fachlicher Mutationen oder Quellenabruf allein zum Wiederherstellen der UI.
- Reprojektion betrifft die tatsächlich abhängigen lokalen Fenster (Profil/Notiz, Entdecken/Grid, SavedPeople, SavedSearch, Map, Home). Unabhängige Reads behalten ihre eigenen Erfolgs-/Fehlerzustände; sichtbare gültige Resultate werden publiziert, verdeckte Fenster nach Bedarf dirty. Alte Ergebnisse dürfen keine Aktionen für einen anderen Owner freigeben. Reine erfolgreiche Swipe-Entscheidung invalidiert nicht pauschal andere Personenprofile; tatsächliche Quell-/Identitätsänderungen oder Fehler widerrufen betroffene Slots.
- Archivierung und bestätigter Merge koordinieren Cancel/Drain und aktuellen Besitzer; native Transaktionen sichern die Integrität. Kein implizites Gelesen-Ack, kein fachlicher Write durch Layout oder Navigation.

Weitere Kontextinvarianten: Skip/Undo/Reset/Replay bleiben an den gültigen Deck-/Owner-Kontext gebunden; Skip-Undo schreibt nicht in SQLite, alte Steps werden nach Merge nicht auf neue Owner übertragen. Reset/Replay widerrufen alte Callback-/Step-Generationen. Gerenderte Kontakt-, Medien-, Menü- und Vergleichsaktionen dürfen nicht auf einen anderen Owner/Session übergehen. Notizentwürfe werden nicht still auf andere Owner umgedeutet oder rebased. Snapshotvergleich ist explizit und prüft das gültige Snapshot-Paar; alte Dialoge werden invalidiert. Es gibt keinen unbeschränkten SaveableState pro Profil. Bei Merge aktuelles Listing soweit zulässig erhalten, andernfalls sauberer Kontextwechsel; keine zusätzliche Swipe- oder Undo-Aktion.

## Diagnoseinfrastruktur

Rust-Tracing pro LadiesCore (kein global an erste DB gebundener Dispatch), Kermit über UniFFI in dieselbe app-private `filesDir/diagnostics/ladies-debug.log`, synchronisierte Senke ca. 1,5 MB, Rotation Rest ca. 700 KB. Monotonic Instant bzw. elapsedRealtimeNanos; INFO Gesamtdauern, DEBUG Teilphasen; keine HTTP-Antwortbodies im allgemeinen Log. Vollständige geeignete Textantworten ausschließlich in ausdrücklich aktivierter HTTP-Textaufzeichnung. Eine separate persistierte JSONL-Sitzung `filesDir/diagnostics/ladies-http.jsonl`, max. 2 MiB Textbody (ganz auslassen bei Überschreitung), 25 MiB Sitzung, Stopp bei Limit/Schreibfehler. Unvollständiges letztes Ereignis beim Neustart entfernen und melden; beschädigtes mittleres Ereignis verhindert Export. Neustart deaktiviert Capture, Löschen entfernt Sitzung.

Recorder in bestehenden Rust-HTTP-Lesepfaden; keine weiteren Requests oder Kotlin-HTTP-Bridge. Quellenredirects separat ohne Body; Medienredirects verboten; Geocoder folgt intern, nur finale URL/aggregierte Headerzeit sichtbar. MapLibre und externe Intents außerhalb. Coil-Cachetreffer keine Requests, keine Medienbytes im HAR. HAR 1.2 mit echten aggregierten Zeiten und _diagnostics für Auslassungen, Fehler und finale URL. Maskierung Cookies/Auth-/Token-Header, sensitive Queryparameter, URL-Credentials, Fehler-URLs, Redirect/Referer; Textantworten weiter sensibel. SAF zum Speichern, FileProvider ausschließlich unveränderliche app-private Cache-Snapshots, Original unzugänglich, >48 h alte Snapshots beim nächsten Export entfernen. Statuschecks loggen Listing-ID, bestätigten Status, HTTP-Code. Modi in [PRODUCT](PRODUCT.md#einstellungen-und-lokale-diagnose), Auswertung in [README](../README.md#lokale-diagnose).

## Karte und Sicherheitsgrenzen

- MapLibre besitzt Karten-View und Kartenressourcen; AndroidView-Factory/onRelease und Lifecycle-Observer koordinieren genau einmal den nativen View-Lifecycle einschließlich Speicherdruck. Kamera und Auswahl bleiben über SaveableStateHolder bei Tab-/Profilwechseln erhalten. Rust liefert die DB-weite Kartenprojektion und hält Koordinatencache in `app_settings` (`map:postal:DE:…` / `map:city:DE:…`, keine separate Schema-Migration), zentrale Geocoding-Lease und Nominatim-Pause über Läufe hinweg. Offline-Überblick und Auftragsstatus sind getrennt; nach Abschluss/Abbruch liest die UI genau einmal den aktuellen Bestand statt doppelter Listenübertragung. Kein eigener Prozess, WebView, Server oder Gerätestandort. 
- Kartenstil `https://tiles.openfreemap.org/styles/liberty`; PLZ `https://api.zippopotam.us/DE/<plz>`, bei fehlender PLZ später Stadt-Fallback `https://nominatim.openstreetmap.org/search` (DE, max. ein Ergebnis, >=1,1 s Abstand auch über Aufträge hinweg, [Provider-Policy](https://operations.osmfoundation.org/policies/nominatim/)). Neun Sekunden HTTP-Timeout je Standort; bestätigte Nichttreffer sieben Tage negativ cachen, Transport-/Format-/Koordinatenfehler nicht. Kartenüberblick offline über mapOverview; `resolveMapLocations`-Aufträge gemäß [PRODUCT](PRODUCT.md#karte), GeoJSON/Cluster, keine exakten hidden_location-Daten an Provider. Kachel-/Stil-Attribution beachten. Profilöffnung über loadProfile mit Offline-Fallback.
- Private Datenbank, Medien, Exporte und temporäre Player-Dateien liegen im App-Sandboxbereich. Android-Backup ist deaktiviert. Manifest entfernt von SDKs deklarierte COARSE/FINE_LOCATION-Berechtigungen. Produktgrenzen gemäß PRODUCT. Externe Aktionen sind explizite Intents. Lizenzinformationen für native Bibliotheken stehen in [NOTICE](../NOTICE).

## Automatisierte Architekturverträge

`tools/check_architecture.py` und seine Regressionstests sichern die folgenden Grenzen; APK-Prüfungen verwenden Android SDK apkanalyzer, AAPT2 und ZIP-Verzeichnis. Kompilierte JVM-Signaturprüfungen ergänzen statische Quellregeln.

| Regel | Prüfbarer Vertrag |
| --- | --- |
| ARC-01 | App-eigener Quellen-HTTP nur im Rust-Core; genau ein Coil-Netzwerkadapter über CoreStore; MapLibre-Kartenressourcen und explizite externe Intents zulässig. |
| ARC-02 | Keine direkten SQLite-/Room-/SQL-APIs oder entsprechenden direkten Abhängigkeiten in Android-Produktionscode. Rust-Tests sichern die fachlichen Persistenzverträge. |
| ARC-03 | Private LadiesCore-Engine nur in CoreStore; kompilierte nicht-private API-Signaturen (inkl. inferierter/generischer und suspend-Rückgaben) dürfen keinen Engine-Typ offenlegen. UI darf generierte Datentypen und RequestControl sowie CoreStore verwenden. |
| ARC-04 | Generierte UniFFI-Engine-Quellen und native Engine-Binaries nicht eingecheckt; ARM64-Engine/JNA tatsächlich im Debug-APK. |
| ARC-05 | Core-Infrastruktur importiert/referenziert nicht die UI oder Compose-UI. |
| ARC-06 | Keine alternative app-eigene HTTP-/JNI-/JNA-Fachbrücke oder zusätzliche Server-/Runtime-Implementierung; legitime vorhandene SDKs bleiben. |
| ARC-07 | GalleryVideo als lokaler libmpv-Adapter, Rust-Download, lokaler loadfile-Pfad, file-Protokollrestriktion, keine Konfiguration/Skripte/externen Referenzen/automatischen Begleitdateien; vorhandene JVM-/Rust-Vertragstests bleiben. |
| ARC-08 | Bekannte private Export-/DB-/Archivpfade nicht eingecheckt. Gebautes Debug-APK: Backup aus, Internet und MapLibre-Netz-/WLAN-Status zulässig, keine Geräte-Standortberechtigungen (SDK-Deklarationen per Manifest-Merge entfernt), begründete Exports (Launcher, Debug-Compose-Hosts, DUMP-geschützter AndroidX ProfileInstaller), einziger URI-freigebender Diagnoseprovider mit einzigem Cache-Unterverzeichnis; Referenz aus kompiliertem APK über Ressourcentabelle aufgelöst, sämtliche XML-Konfigurationen geprüft. |

Die Prüfungen sind konservative Regressionen, keine vollständige Kotlin-Typ-/Datenflussanalyse, Sicherheits-Sandbox oder Aussage über beliebige private Inhalte und SDK-Laufzeitverhalten. APK-Regeln beziehen sich auf tatsächlich gebautes ARM64-Debug-APK. Build-/Testkommandos und Ausführung: [README](../README.md#prüfung--ci). Fachliche Unit- und Regressionstests liegen in Rust, Android JVM und den vorhandenen nativen Instrumentierungstests. Spezifische Profil-/Projektionsregressionen nach Betroffenheit: 0/1/200+ Cards, 0/1/12/80 Medien, partial/unverified, Offline/Timeout, Gesten und betroffene Aktionen, Cancel/Late Write, Identitäts-/Notizkonflikte, SavedSearch-Ack und unabhängige Reprojektion; keine zusätzlichen Quellenrequests durch Präsentation. Kartenregressionen: Identitäts-Deduplizierung, Favoriten, Offline-Cache, PLZ/Stadt-Fallback und Cancel/Lease bei erneutem Start. Der Architekturvertrag enthält keine Aufgabenstände oder Abnahmechronik.
