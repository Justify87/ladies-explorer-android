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

### Native Tabellen

| Bereich | Native Tabellen |
| --- | --- |
| Identität/Nutzerzustand | `persons`, `person_user_state`, `source_profiles`, `listings`, `person_identifiers` |
| Inhalt/Historie | `listing_snapshots`, `snapshot_values`, `listing_observations`, `change_events` |
| Medien | `media_assets`, `media_sources`, `snapshot_media` |
| Suche | `search_runs`, `search_results` |
| Gespeicherte Suchen | `saved_searches`, `saved_search_results` |
| Identitätsevidenz | `identity_evidence`, `identity_events` |
| Persönliche Begegnungen | `encounters`, `encounter_services` – typisierte gemeinsame CRUD-API; `price_lists`, `price_items` – Schema |
| Einstellungen | `app_settings` |

### SQLite-Vertrag

- **Ablage/Identität:** App-private Datenbank `filesDir/ladies.sqlite`; `user_version = 2` und `application_id = 0x4C445850` (LDXP). Eigenständiger Android-Datenbestand ohne nutzerseitige Übernahme gemäß [PRODUCT](PRODUCT.md#produktziel-und-grenzen).
- **Schemafreigabe:** Installation und interne Schemaentwicklung transaktional vor Store-Freigabe. Native v1 muss dem kanonischen Schema entsprechen; v2 einschließlich Tabellen, Indizes und Triggern validieren.
- **Ungültige Schemata:** Fremde IDs, unerwartete unversionierte Tabellen, Schemaabweichungen oder unbekannte neuere Versionen erzeugen typisierte Fehler. Die exakt bekannte Demo mit `scaffold_notes` ist ein unterstützter technischer Migrationsfall; echte Nutzerdaten nicht implizit löschen.
- **Connection:** `foreign_keys=ON` je Verbindung; Rust-Mutex schützt die Connection. Öffentliche Mutationen nutzen parametrisierte SQL-Anweisungen und Transaktionen.
- **Owner-Integrität:** Person-INSERT erzeugt Nutzerstatus durch Trigger. Trigger verhindern unzulässige Personen-/Quellenzuordnung von Inseraten und Quellprofilen; Sichtungen aktualisieren die je Quelle zugehörigen `last_seen_at`-Zeitpunkte.
- **Keyset-Cursor:** `PersonCursor` über `last_seen_at`/`person_id`, `DiscoveryCursor` über Favorit/Inserat-`last_seen_at`/`listing_id`; jeweils absteigend und Seitengrößen 1–100. Suche, SavedSearch und SavedPeople besitzen eigene Fensterverträge.
- UI enthält weder direkte SQL-Zugriffe noch eine alternative Persistenz.

### Historisierung und Identitätsoperation

**Snapshots und Übergänge:**
- Profilabrufe speichern Snapshot, PROFILE-Beobachtung und tatsächliche Feld-/Medienänderungen in **einer Transaktion**.
- Fingerprints berücksichtigen die Kategorie; identische Inhalte erzeugen Beobachtungen, keine künstlichen Änderungen.
- Rückkehr zu früherem Inhalt aktiviert dessen Snapshot und dokumentiert den echten Übergang vom vorher aktiven Snapshot.

**Evidenz und Kandidaten:**
- Normalisierte Telefonnummer, Quellprofil-ID und 64-bit-dHash liefern Kandidatenevidenz. Internationale 00-Vorwahlen werden als Landesvorwahl normalisiert. Historische Telefonschlüssel bleiben erhalten; bei vorhandenem Rohwert wird die Evidenz mit der aktuellen Normalisierung bestätigt. Ein abweichender historischer Schlüssel ohne Rohwert liefert keine zusätzliche Evidenz.
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
- Explizites Nullsignal beziehungsweise behandelter erster HTTP-404-Fall: `partial=false`, `radius_verified=false`. Weder daraus noch aus unbestätigter Radiusangabe einen bestätigten Delta-Nulltreffer ableiten.
- Fehlende Radiusangabe regulärer Seiten: `partial=true`, `radius_verified=false`, `distance_searched=NULL`.
- Radiusabweichung auf Seite 1: Fehler; später: Teilresultat.
- Erkannter Radiusübergang ist nur bei bestätigter Vollständigkeit ein reguläres Ende. Sonst `partial`, auch bei Count-Abweichung, Seitenlimit oder fehlender erwarteter Folgeseite. Fachliche Folgen: [PRODUCT](PRODUCT.md#suche-und-filter).

**Filter und Vorschau:**
- Filterkatalog kanonisiert Gruppen, Aliase und Slugs; Orts-API-Schlüssel case-insensitiv mit lokalem Slug-Fallback.
- Preview nutzt denselben geprüften Seite-1-Abruf mit Fallback, 350 ms Debounce und keinerlei Persistenz. Eine eigene Lease bindet die sichtbare Filteransicht bzw. Editor-Session an den STARTED-Lifecycle; Stop/Dispose widerrufen nur ihre eigene Lease. Vor I/O und Publikation müssen Lease, Request-Generation und aktueller Quellentwurf übereinstimmen. Lokale Filter und Seitenlimit beeinflussen die Seite-1-Vorschau nicht.
- Häufige Filter kommen aus `search_runs`, ohne weiteren Store.

**Zulässiger Profilabruf:**
- Nur lokal bekannte Inserate, HTTPS, erlaubte Hosts/Präfixe `/sex-anzeigen/`, `/fkk-saunaclubs/`, `/stars/` und kanonische www-URL; keine frei eingegebene URL.
- Vorhandene HTML-ID gegen erwartete ID prüfen. Relative, protokollrelative und CSS-Medienreferenzen auflösen, Reihenfolge bewahren; A- vor F-Variante derselben Foto-ID, Revisionsparameter sowie 18+-/Sterne-Marker erhalten.
- Such-/API-/Profilstruktur und CDN-Varianten durch frühere lokale HARs und synthetische Regressionen belegt. Keine frische Sitzung/Alters-/Cloudflare-Freigabe daraus ableiten. Selektoren und Klassifikationen nur mit neuer Evidenz ändern; keine privaten HARs in Git.

**Statuschecks:** Statusantworten und Delta-Commit verwenden Owner-/Versionsprüfungen unter [Gespeicherte Personen und Suchen](#gespeicherte-personen-und-suchen). Statusbatch sequenziell, mit 350 ms Pause und Cancel bei Navigation/`ON_STOP` oder Aktion; keine Inhalts-/Archivoperation. Fachliche Statussemantik in [PRODUCT](PRODUCT.md#gespeicherte-personen).

### Persistentes Archiv und Anzeige

**Archiv und Snapshots:**
- App-private Bilder: `ladies_data/media/images/`. SHA-256-Deduplizierung; JPEG/PNG/WebP/GIF-Dimensionen und Pixelbudget vor voller Dekodierung prüfen. Der 64-bit-dHash verwendet Legacy-kompatiblen Links-/Rechtsvergleich mit LANCZOS.
- Archivierung folgt dem Snapshot als **separater** Vorgang. Der gerenderte Stopp bindet die sichtbare Standalone-Session; pro Session startet höchstens eine Archivierung. Überholte Stopp-Callbacks erreichen keinen fremden Auftrag. Interne Back-/Lifecycle-/Merge-Cancel bleiben unabhängig; der native Auftrag besitzt seinen Slot bis zum Drain. Fehler oder Cancel entfernen keine gespeicherten Profildaten oder bereits archivierten Dateien. Medienrevision und lokale Existenz steuern Wiederverwendung.
- Videoreferenzen werden standardmäßig ohne dauerhaftes Videoarchiv gespeichert; Wiedergabe über vorhandene Archivdatei oder temporäre lokale Anzeigedatei. Ein opt-in-Archivierungspfad existiert im Rust-API.

**Medienabrufgrenzen:** Rust prüft bekannte HTTPS-Hosts und URL-Formen, verbietet Redirects und begrenzt Ressourcen auf Bilder 12 MiB, Videos 48 MiB, 24 Mio. Bildpixel, 80 Referenzen pro Archivaufruf, 15 s pro Request und 150 s Gesamtbudget fürs Archiv. [media.rs](../rust/ladies-core/src/media.rs).

- **Bilder:** Coil 3 nutzt lokale Archivdateien zuerst, dann Remote-Fallback über `coil-network-core → RustImageNetworkClient → CoreStore.loadDisplayImage → UniFFI → Rust`. Vier gleichzeitige blockierende Bildanzeigeabrufe sind über den IO-Dispatcher begrenzt. Coil besitzt URL-/Revisionsschlüssel, Memory-/Disk-Cache, Dekodierung und Zusammenführung identischer Cache-Anforderungen; UniFFI überträgt begrenzte kodierte Bytes. Fehler führen zu sichtbaren Platzhaltern. Reine Anzeige archiviert nicht und schreibt nicht in SQLite.
- **Videos:** `GalleryVideo` bindet Player, Medium und lokale Datei an den sichtbaren Galerieeintrag und RESUMED-Lifecycle. Archivdatei zuerst, sonst `CoreStore.loadDisplayVideo → UniFFI → media::display_video` mit Rust-Host-, Größen-, Timeout-, HAR- und Cancel-Vertrag. Eigener IO-Dispatcher mit Parallelität 1. Validierte MP4/WebM-Datei im App-Cache; kein neuer Archiv-/DB-Eintrag. libmpv erhält ausschließlich einen lokalen Pfad, erzwungener lavf-Container und `protocol_whitelist=file` als lavf-Option; `access-references=no`, `config=no`, `load-scripts=no`, `ytdl=no` und `autoload-files=no` sperren externe Referenzen, Konfiguration, Skripte, URL-Helfer und automatisch geladene Begleitdateien. Wiedergabeverhalten gemäß DESIGN; Hardwaredecoder soweit verfügbar. Player stoppt außerhalb der gültigen sichtbaren Projektion bzw. bei Lifecycle-/Busy-Wechsel. Surface-Freigabe nach nativer Zerstörung; erneuter Surface-Owner nur bei weiterhin gültigem Medium. Temp-Dateien werden auch bei Cancel bereinigt; verwaiste Dateien beim ersten Zugriff nach Prozessneustart entfernt.

## Oberfläche und Navigation

`ShellRoute` und `LadiesApp` besitzen Root-Navigation gemäß DESIGN; Entdecken-Modi sind lokale Zustände. Standalone-Profile überlagern die Root-Navigation und behalten den tatsächlichen Ursprung. Status und Back werden vom zuständigen Owner koordiniert; reine Status-Reads sperren Navigation nicht.

### Suche, Entdecken und Grid

**Run und Sichtbarkeit:**
- `MainViewModel` trennt Filter-Snapshot des **ausgeführten** Runs vom Entwurf.
- Normale Filteränderungen binden die sichtbare Filteransicht und deren Ansicht-/Pane-Generation; aufeinanderfolgende Eingaben ändern den aktuellen Entwurf ohne Snapshot-Sperre. Reset prüft zusätzlich den gerenderten Entwurf und widerruft die bisherigen UI-Aktionen. Beide erhalten den ausgeführten Run. Der lokale Wechsel zur Ergebnisansicht verwirft alte Filter-Callbacks.
- Der normale Suchstart bindet den gerenderten Entwurf einschließlich lokaler Sichtbarkeitsregeln und Ansichtsgeneration an den sichtbaren Filterkontext. Veraltete oder gesperrte Aktionen erreichen keinen Quellabruf. Normale Suche und Schnellwahl verwenden denselben nativen Such-/Drain-Pfad.
- Der Suchabbruch aus Filtern, Grid und Swipe bindet die gerenderte Ansichtsgeneration und die native Vorgangs-ID des laufenden Suchversuchs, bei Swipe zusätzlich die sichtbare Swipe-Session. Nur die aktuelle sichtbare, noch nicht abbrechende Suche darf den nativen Abbruch signalisieren. Der neue Suchstart widerruft alte Abbruch-Callbacks auch nach Fehler oder Abbruch; Lifecycle-Cleanup und native Drain-Grenze bleiben unabhängig.
- Die Grid-Schnellwahl bindet die gerenderte Ansichtsgeneration und den ausgeführten Run, ohne aktiven Run den gerenderten Entwurf. Veraltete Callbacks erreichen keinen neuen Quellabruf; der Beginn eines Suchlaufs widerruft alte Grid-Aktionen auch bei Fehler oder Abbruch. Swipe und Grid nutzen weiterhin denselben Suchpfad.
- Rust liefert `activeSearchCards` in Positions-/ID-Reihenfolge mit aktuellen Owner-/Präferenz-/Favoritenzuordnungen. Grid liest diese aktive Ergebnismenge, **nicht** die bereits konsumierte Swipe-Queue.
- Neutraler Startbestand verwendet Präferenz `NONE`. Im aktiven Run bestimmen natives Filterergebnis, sessionlokale Traversierung und Replay-Likes-Policy gemeinsam zulässige Swipe-Karten. Fachvertrag: [PRODUCT](PRODUCT.md#entdecken-und-entscheidungen).

**Entscheidung und Grid-Hide:**
- `SwipeSession` hält Traversierung/Undo-Records, keinen zweiten fachlichen Store.
- Geste und Rail teilen dieselbe gerenderte Admission und native CAS-Präferenzoperation; Queue-Projektion ist autoritativ.
- Grid-Herz bindet den gerenderten Listing-/Owner-/Präferenz- und Run-Snapshot samt Präsentationsgeneration und führt eine erwartungsgeprüfte LIKE/NONE- beziehungsweise LIKE-Mutation aus. Das rungebundene temporäre Hide wird bei neuem Run zurückgesetzt, verändert weder native Ergebnismenge noch Swipe-Historie.
- Fehler, Commit und Kontext: [Operationsmodell](#operations--und-konsistenzmodell) und PRODUCT.

**Unabhängige Projektionen:**
- `DiscoveryProjectionPublisher` aktualisiert lokale Entdecken-/Grid- und Saved-People-Projektionen mit getrennter Fehlerbehandlung und gemeinsamer Gültigkeitsprüfung.
- Lokale Grid-Pagination bindet manuellen und automatischen Callback an den gerenderten Cursor und die Aktionsgeneration. Nur die sichtbare Grid-Ansicht ohne aktiven Suchlauf oder konkurrierende Operation darf nachladen; die Admission widerruft alte Callbacks auch bei anschließendem Readfehler. Nach einem zugelassenen Swipe-Skip kann dieselbe lokale Lesefunktion ohne Grid-Callback nachladen. Die bestehende Projektionsepoch schützt die Publikation; kein automatischer Grid-Retry nach Readfehler.
- Der manuelle Grid-Refresh bindet den gerenderten ausgeführten Run (oder lokalen Startbestand) und die Aktionsgeneration. Nur die sichtbare, nicht beschäftigte Ansicht darf vor I/O alte Aktionen widerrufen und den gemeinsamen lokalen Projektionsread auslösen; laufende lokale Pagination darf über die bestehende Projektionsepoch ersetzt werden. Fehler bleiben explizit erneut ladbar; interne Reprojektionen bleiben unabhängig.
- Präsenz/Alter aus vorhandener eindeutiger Quellen-/Snapshot-Evidenz, keine dekorativen Profilanfragen.
- Karte hat eigene DB-weite Projektion: [Karte und Sicherheitsgrenzen](#karte-und-sicherheitsgrenzen).

### Gemeinsame Profilarchitektur

**Datenhoheit und Render-Modell:**
- `NativeProfileProjection` liest Profil, kanonische Identität, Person, Medien, Historie und Kandidaten unter **demselben SQLite-Guard**.
- `ProfileReadModel` bindet Projektion, Herkunft/Fehler und lokale Revision. Swipe und Standalone teilen den lokalen Lese-/Präsentationsvertrag, aber nicht denselben Quellen-/Archiv-Lebenszyklus.
- Standalone: `StandaloneProfileOperations` liest frische Quelldaten; der geöffnete Detailkontext darf keinen stillen Wechsel des ursprünglich gewählten Owners akzeptieren, außer nach explizit bestätigtem Identitätsmerge. Bei Fehler aktiven lokalen Snapshot samt Medien mit Herkunft/Warnung zeigen. Erfolgreiche Abrufe dürfen separaten Archivvorgang auslösen; Offline-Fallback löst keinen erneuten Netzarchivvorgang aus.
- Swipe: höchstens aktuelles plus zwei Folgeprofile ohne zusätzliche Archivierung; Current-/Prefetch-Vertrag unten.

**Oberfläche und sichtbarkeitsgebundene Reads:**
- `ProfileShell`/`ProfileFlow` teilen Profiloberfläche, erhalten separate Navigations-/Operationskontexte. [DESIGN](DESIGN.md#profile-und-medien) bestimmt sichtbare Inhalte.
- `ProfileHeroLayout` berechnet adaptives Budget aus gemessenen Constraints, Insets und Leisten. Headeridentität folgt realer Lazy-Geometrie, Decision Rail hat Menü-Fallback; Status/Busy dürfen Viewport nicht unkontrolliert verschieben.
- `ProfilePanels`, `ProfilePanelReads` in `ProfilePanelState.kt` und `ProfileFlowRows` steuern lokale Reads nach Sichtbarkeit/Bedarf.
- Facts und PersonDetail werden nur für gültiges Listing/Owner/Snapshot/Filter/Revision wiederverwendet. Expliziter Retry benötigt den aktuellen Profil-Token und die gerenderte Read-Generation. Der Vergleichsdialog schließt nur seine eigene Vergleichsgeneration; internes Cleanup bleibt unabhängig. Analyse, Historie und Vergleich erzeugen **keine** zusätzlichen Quellrequests durch bloße Darstellung; Snapshotvergleich prüft Owner und Listingzuordnung.

**Notiz / Konflikt und Lifecycle:**
- Notiz-Reads/Retry besitzen einen gemeinsamen projektionsgebundenen Job. Entwürfe und Tickets sind im ViewModel ownergebunden, über Navigation/Rotation, **nicht** Prozessende erhalten.
- Speichern, Verwerfen und Konfliktauflösung binden neben dem Profil-Token den exakt gerenderten Notizentwurf einschließlich erwarteten und beobachteten Texts. Veraltete Aktionen werden vor Mutation verworfen; laufende Reads sperren Entwurfsaktionen, Readfehler erlauben weiterhin explizites Verwerfen. Fortlaufende Texteingaben binden keinen unveränderlichen Entwurf.
- Speichern vergleicht transaktional aktuellen Besitzer und erwarteten Text, schreibt ausschließlich `private_note` und `updated_at`.
- Notizkonflikte explizit auflösen; Identitäts-Merge blockiert bei betroffenen ungespeicherten Entwürfen.

**Persönliche lokale Datensätze:**
- `encounters.rs` besitzt transaktionale CRUD- und Owner-/Editor-CAS-Prüfungen einschließlich optionaler Inseratszuordnung, Grundpreis und vollständiger persönlicher Leistungszeilen. Kindzeilen werden innerhalb derselben Transaktion gelesen und bei Änderung ersetzt; Löschen nutzt den Fremdschlüssel-Cascade. Gesamtbetrag wird nicht berechnet.
- `prices.rs` verwaltet manuelle EUR-Preislisten und Positionen in den vorhandenen Tabellen. Jede Korrektur vergleicht Owner, vollständige Werte und Positions-IDs; neue Positionen werden vor dem Löschen der alten eingefügt, damit ein älterer Editor auch nach einer Rückkorrektur nicht wieder gültig wird. Löschen ist atomar mit Cascade. Die native Preisübersicht sortiert nach Erfassungsdatum und ID; den letzten bekannten bezahlten Gesamtbetrag liest sie separat aus Begegnungen, nicht aus Preispositionen.
- `CoreStore` vermittelt typisierte native Zugriffe. `ProfileRecordEditor` teilt Admission, Read-/Retry- und Entwurfszustand für Begegnungen und Preislisten. Pro ViewModel ist höchstens ein lokaler Datensatzeditor aktiv (nicht über Prozessende). Der gemeinsame `ProfileFlow` fordert Reads nur für die geöffnete Sektion oder zur Wiederaufnahme des aktiven Entwurfs an; keine Vorausladung persönlicher Datensätze.
- Ein Entwurf behält den ursprünglichen CAS-Stand bei Kontextwiederaufnahme. Veraltete Aktionen, Mehrfachschreibvorgänge, Navigation und Merge bei offenem Editor werden nicht zugelassen. Back/Abbrechen erfordert explizites Verwerfen eines Bearbeitungsentwurfs.
- Schreibvorgänge nutzen den gemeinsamen Mutationsguard in `MainViewModel`, drainieren bestätigte native Operationen und aktualisieren bedarfsgebunden lokale Datensatzprojektionen, nicht Discovery-/SavedSearch-/Quellprojektionen. Ein anschließender Readfehler erlaubt Read-Retry, keinen Write-Retry. Personennotiz und andere Profilwerte bleiben getrennt.

## Gespeicherte Personen und Suchen

### Personen und Status

- `saved_people_window` in Rust projiziert Favoriten, Likes und Dislikes nach stabiler Personen-ID. Gerenderte Aktionen binden Auswahl, Fenster-/Navigationsgeneration und vollständigen Row-Snapshot; nur atomare Rust-CAS-Operationen schreiben Präferenz/Favorit, für kartenlose Personen ohne erfundenes Listing.
- Recent (Produktzeitfenster) und Regular samt Favoriten-/Datum-Counts entstehen unter demselben SQLite-Guard und Zeitanker. Manuelle und automatische Pagination binden die gerenderte Regular- beziehungsweise Recent-Fensterinstanz, Auswahl und Aktionsgeneration. Nur das sichtbare, nicht beschäftigte Fenster mit Folgeseite darf vor I/O seine Kapazität erweitern. Nachladen liest ein kohärentes erweitertes Fenster; Kotlin hält nur Kapazität, Auswahl und Scrollzustand.
- Tabwahl, Sortierung und explizites Laden binden die gerenderte Auswahl und Ansichtsgeneration; veraltete Callbacks nach Navigation, Reprojektion oder Lifecycle-Wechsel erreichen keinen Read. Ein zugelassener Wechsel oder Reload widerruft alte Aktionen vor I/O und darf lokale Pagination über die vorhandene Projektionsepoch ersetzen. Interne Reprojektionen bleiben unabhängig.
- Keyset-Cursor bindet Auswahl, Zeitgrenze und SQLite-`total_changes`-Revision. Namenssortierung mit NFD, Case-/Mark-Folding und ß/ss (keine vollständige CLDR-Kollation). Metadaten fallen deterministisch auf vorhandene Daten zurück; Relisting behält fachlichen Ereigniszeitpunkt.

**Statusoperation:**
1. Suche, Profil und explizite Einzel-/Batchchecks nutzen denselben Rust-Statusübergang. Die UI lässt Einzelchecks nur für die weiterhin gerenderte Saved-Person-Zeile und Batchchecks nur für die gerenderte Auswahl/Fensterinstanz zu; Tab-, Fenster- und Lifecycle-Wechsel widerrufen verzögerte Aktionen vor I/O.
2. Explizite Checks benötigen **vor I/O und erneut beim Commit** gültige gespeicherte LADIES-Mitgliedschaft (Favorit oder Präferenz ungleich `NONE`).
3. Rust prüft vor Commit transaktional Owner, URL, Advert-ID und Beobachtungsrevision.
4. Nur bestätigte Beobachtungen – einschließlich gültiger Suchkarten als positiver `ACTIVE`-Beleg – aktualisieren Status und Statuszeit; Status-only schreibt Beobachtung und bei bestätigtem Wechsel ein Ereignis, aber keine Inhalts-/Personenreihenfolge oder Medieningestion.
5. `SavedPeopleState` hält Fenster und sequenziellen Statusablauf. Der gerenderte Abbruch bindet die Statusversuchs-ID und Ansichtsgeneration; nur der sichtbare, laufende und noch nicht abbrechende Versuch ist zulässig. Navigation/`ON_STOP` cancelt unabhängig davon; bereits gespeicherte Teilergebnisse bleiben und werden lokal reprojiziert.

Fachliche Fälle/Fehler: [PRODUCT](PRODUCT.md#gespeicherte-personen).

### Gespeicherte Suchen

**Zuständigkeiten:** `SavedSearchController` besitzt Liste, Template-Session, Editor, Dialoge, Ergebnisfenster und Operationen. Rust besitzt validierte Definitionen, Mitgliedschaft des letzten vollständigen Laufs, Delta-/Membership-Historie sowie revisionierte Rename-/Ack-Operationen.
- Einstieg in die gespeicherte Suchübersicht und das Anlegen einer Definition binden die gerenderte Pane-/Aktionsgeneration; Zeilenaktionen (Öffnen, Prüfen, Ändern, Umbenennen, Löschen) zusätzlich die aktuelle Zeileninstanz. Editor-Eingaben/Abbruch binden die Editor-Session, Speichern zusätzlich den exakt gerenderten Editorstand und die Eingabevalidierung. Dialog-Eingaben/Abbruch binden die Dialoggeneration, Bestätigungen zusätzlich die Dialoginstanz. Veraltete Aktionen nach Reload, Pane-/Dialog-/Lifecycle-Wechsel erreichen keine fremde Ansicht, Quellen- oder Schreiboperation; verspätete Editor-Reads und deren Fehler dürfen keinen fremden Kontext überschreiben. Rust bleibt für Definition/Version/Delta-Commit autoritativ.
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
- Nach relevantem nativen Abschluss sichtbare Ergebnisfenster und die sichtbare Suchübersicht lokal aktualisieren; unsichtbare Projektionen als dirty markieren. Die Übersicht im Editor erst bei Rückkehr neu lesen; ein kohärenter Ergebnisread aktualisiert auch die Übersicht. Änderungen während eines Reads zusammenfassen.
- List-/Window-/Actionfehler unabhängig halten. Manuelle und automatische Pagination sowie expliziter Retry/Reload binden die gerenderte Fensterinstanz und Aktionsgeneration; UI-Admission verlangt sichtbare, nicht beschäftigte und nicht dirty Ergebnisansicht. Pagination benötigt zusätzlich ein geladenes Fenster mit weiteren Treffern. Retry liest die bestehende Fenstertiefe, auch nach einem fehlgeschlagenen Read.
- Explizites Ack nach [PRODUCT](PRODUCT.md#gespeicherte-suchen) bindet die **gerenderte Revision, Listen- oder Ergebnisansicht und deren Aktionsgeneration**; Ergebnis-Acks binden zusätzlich die konkrete Fensterinstanz. UI-Admission verwirft überholte Klicks nach Navigation, Read, Dialog und Lifecycle-Wechsel; Rust-CAS verhindert ein Ack gegen eine inzwischen neuere Revision. Bei Fehler neu lesen, nie den Write automatisch wiederholen.
- Bereits zugelassene Ack-Operation darf nach `ON_STOP` bis zum tatsächlichen Abschluss drainieren. Weitere gemeinsame Grenzen: [Operationsmodell](#operations--und-konsistenzmodell).

## Operations- und Konsistenzmodell

Datenhoheit, Gültigkeit gerenderter Aktionen/UI-Admission und tatsächliches Operationsende sind getrennt. **Rust ist Commit-Autorität.**

| Phase | Vertrag |
| --- | --- |
| Admission | Gerenderte Tokens binden Kontext, Run-/Deck- oder Standalone-Session, Listing, erwarteten Owner, Snapshot-/Projektionsrevision und erwartete Fachwerte. UI-Callbacks gegen aktuellen Zustand prüfen. |
| Native Operation | `RequestControl` je Operation; Cancel signalisiert Rust und entzieht überholter UI die Publikationsberechtigung. Kooperativ weitere Arbeit stoppen, vor Suchpersistenz und gespeicherten Deltas auch vor SQLite-Commit prüfen. Blockierender Transport darf bis Timeout laufen. |
| Drain | `DrainingOperation` hält nativen Owner bis **tatsächlich** abgeschlossen, auch bei Navigation oder verworfener Antwort. Suche bleibt bis Drain loading. |
| Commit | Rust prüft erwartete Besitzer, Präferenz, Favorit, Notiz und Evidenz nativ. UI-seitiges Check-then-write ersetzt keinen CAS-/Transaktionsschutz. |
| Publikation | Nur gültige Resultate an ihren aktuellen Owner publizieren; sichtbare abhängige Fenster lokal reprojizieren, unsichtbare dirty markieren. Unabhängige Reads behalten eigene Erfolgs-/Fehlerzustände. |

**Nach Commit und bei Fehler:**
- Bestätigten Write genau einmal als solchen behandeln. Writefehler und anschließenden Readfehler unterscheiden; nach Commit ausschließlich **lokal** reprojizieren.
- Keine fachliche Mutation oder Quellenanfrage stillschweigend erneut auslösen, nur um einen UI-Read zu reparieren.
- Abhängige lokale Fenster sind Profil/Notiz, Entdecken/Grid, SavedPeople, SavedSearch, Karte und Home. Alte Resultate dürfen nie Aktionen für einen anderen Owner freigeben.
- Ein reiner erfolgreicher Swipe invalidiert nicht pauschal andere Personenprofile. Tatsächliche Quell-/Identitätsänderungen oder Fehler widerrufen betroffene Slots. Statusprüfungen können Profilstatus und Beobachtungszeit ändern, auch ohne Änderung der Mitgliedschaft; deshalb betroffene Swipe-Projektionen lokal invalidieren. SavedSearch-Reprojektion erfolgt im gemeinsamen Mutationspfad, bei Coroutine-Cancel separat, da dieser die normale Reconciliation umgehen kann. Karten-Recovery bei fehlgeschlagenem Status-/Target-Write und Detail-/Feed-Recovery unabhängig halten; ein Fehler darf nicht die übrigen nötigen lokalen Reads unterdrücken.

**Kontext- und Lifecycle-Invarianten:**
- Standalone-Back bindet die sichtbare Profil-Session, auch während Loading oder Mutation. UI-Retry bindet zusätzlich Listing, dargestellte lokale/Online-Berechtigung und Retry-Versuch; der Beginn widerruft alte Retry-Callbacks. Automatische Wiederherstellung nach Lifecycle-Wechsel bleibt lokal, unabhängig von UI-Retry. Native Drain-Grenze bleibt erhalten.
- Archivierung und bestätigter Merge koordinieren Cancel/Drain und aktuellen Besitzer; Integrität durch native Transaktionen.
- Skip/Undo/Reset/Replay nur im gültigen Deck-/Owner-Kontext. Skip-Undo ohne SQLite-Write; alte Schritte nach Merge nicht auf neue Owner übertragen. Reset/Replay widerrufen alte Callback-/Step-Generationen.
- Kontakt-, Medien-, Menü- und Vergleichsaktionen bleiben an die gerenderte Owner-/Session gebunden. Notizentwürfe nicht still umdeuten oder rebasen; Snapshotvergleich prüft das gültige Paar, alte Dialoge invalidieren.
- Kein unbegrenzter SaveableState pro Profil. Nach Merge aktuelles Listing möglichst erhalten, sonst sauberer Kontextwechsel; kein zusätzlicher Swipe-/Undo-Schritt.
- Layout und Navigation erzeugen weder implizites Gelesen-Ack noch einen fachlichen Write.

## Diagnoseinfrastruktur

### Logs und Zeitmessung

- Rust-Tracing je `LadiesCore`, **kein** global an erste DB gebundener Dispatch; Kermit über UniFFI in dieselbe app-private `filesDir/diagnostics/ladies-debug.log`.
- Synchronisierte Log-Senke circa 1,5 MB, Rotationsrest circa 700 KB. Monotonic `Instant` beziehungsweise `elapsedRealtimeNanos`.
- INFO enthält Gesamtdauern, DEBUG Teilphasen; keine HTTP-Antwortbodies im allgemeinen Log. Geeignete vollständige Textantworten nur bei ausdrücklich aktivierter HTTP-Textaufzeichnung.

### HTTP-Aufzeichnung und Ausfallsicherheit

- Separate persistierte JSONL-Sitzung unter `filesDir/diagnostics/ladies-http.jsonl`. Textbody max. 2 MiB (bei Überschreitung vollständig weglassen), Sitzung max. 25 MiB; bei Limit oder Schreibfehler stoppen.
- Unvollständiges letztes Ereignis beim Neustart entfernen **und melden**. Beschädigtes mittleres Ereignis blockiert Export.
- Neustart deaktiviert Capture; Löschen entfernt Sitzung.
- Recorder in vorhandenen Rust-HTTP-Lesepfaden, ohne weitere Requests oder Kotlin-HTTP-Bridge.
- Quellenredirects separat ohne Body, Medienredirects verboten; Geocoder folgt intern, sichtbar sind finale URL und aggregierte Headerzeit. MapLibre und externe Intents außerhalb.
- Coil-Cachetreffer sind keine Requests; Medienbytes nicht im HAR. Statuschecks protokollieren Listing-ID, bestätigten Status und HTTP-Code.

### HAR, Maskierung und Export

- HAR 1.2 verwendet reale aggregierte Zeiten und `_diagnostics` für Auslassungen, Fehler und finale URL.
- Cookies/Auth-/Token-Header, sensitive Queryparameter, URL-Credentials sowie Fehler-URLs, Redirect/Referer maskieren. Auch maskierte Textantworten können sensibel sein.
- Export per SAF; FileProvider nur für unveränderliche app-private Cache-Snapshots, Original nicht zugänglich. Snapshots älter als 48 h beim nächsten Export löschen.
- Capture-Modi: [PRODUCT](PRODUCT.md#einstellungen-und-lokale-diagnose). Analyse: [README](../README.md#lokale-diagnose).

## Karte und Sicherheitsgrenzen

### Projektion, Karte und Lifecycle

- MapLibre verantwortet Karten-View und Ressourcen. AndroidView-Factory/`onRelease` und Lifecycle-Observer koordinieren **einmal** den nativen View-Lifecycle einschließlich Speicherdruck.
- Kamera und Auswahl bleiben im `SaveableStateHolder` über Tab-/Profilwechsel erhalten.
- Rust liefert die DB-weite Kartenprojektion und hält Koordinatencache in `app_settings` (`map:postal:DE:…` / `map:city:DE:…`, ohne separate Schema-Migration), zentrale Geocoding-Lease und Nominatim-Pause über Läufe hinweg.
- Gerenderte Kartensteuerung (Aktualisieren, Standortauflösung, Abbruch, Favoritenfilter) bindet Kartenstatus, Kartengeneration und sichtbare Surface-/Lifecycle-Generation. Überholte Callbacks erreichen weder eine neue Standortauflösung noch einen fremden Abbruch oder Filterwechsel. Interne Projektionsaktualisierung und interner Abbruch bleiben unabhängig von der UI-Admission.
- Offline-Überblick und Auftragsstatus getrennt. Nach Abschluss/Abbruch liest UI **einmal** aktuellen Bestand statt doppelter Listenübertragung. Kartenprojektion nach nativer Quellen-/Mutationsoperation und lokaler Read beim Eintritt in die Karte sind nicht pauschal austauschbar: zwischenzeitliche native Commits können den DB-weiten Bestand ändern. Der Ist-Stand nutzt getrennte Aktualisierungsanlässe mit Epoch-Schutz; eine Zusammenlegung erfordert belegte äquivalente Aktualität und Fehler-Recovery.
- Kein eigener Prozess, WebView, Server oder Gerätestandort.

### Standortauflösung und Provider

| Zweck | Anbieter / Bedingung |
| --- | --- |
| Kartenstil | `https://tiles.openfreemap.org/styles/liberty` |
| Deutsche PLZ | `https://api.zippopotam.us/DE/<plz>` |
| Fehlende PLZ / späterer Stadt-Fallback | `https://nominatim.openstreetmap.org/search`, DE, maximal ein Ergebnis und mindestens 1,1 s Abstand auch über mehrere Aufträge; [Provider-Policy](https://operations.osmfoundation.org/policies/nominatim/) |

- Neun Sekunden HTTP-Timeout pro Standort.
- Bestätigte Nichttreffer sieben Tage negativ cachen; Transport-, Format- oder Koordinatenfehler **nicht**.
- Offline-Überblick über `mapOverview`; `resolveMapLocations`-Aufträge gemäß [PRODUCT](PRODUCT.md#karte).
- GeoJSON/Cluster; keine exakten `hidden_location`-Daten an Provider. Kachel-/Stil-Attribution beachten.
- Profilöffnung über `loadProfile` mit Offline-Fallback.

### Private Daten und Android-Grenzen

- DB, Medien, Exporte und temporäre Player-Dateien liegen im App-Sandboxbereich; Android-Backup deaktiviert.
- Manifest entfernt von SDKs deklarierte Berechtigungen `android.permission.ACCESS_COARSE_LOCATION` und `android.permission.ACCESS_FINE_LOCATION`.
- Externe Aktionen ausschließlich über explizite Intents; fachliche Grenzen: [PRODUCT](PRODUCT.md).
- Native Lizenzhinweise: [NOTICE](../NOTICE).

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

**Prüfgrenzen:**
- Konservative Regressionen, **keine** vollständige Kotlin-Typ-/Datenflussanalyse, Sicherheits-Sandbox oder Aussage über beliebige private Inhalte und SDK-Laufzeitverhalten.
- APK-Regeln gelten für das **tatsächlich gebaute ARM64-Debug-APK**. Build-/Testkommandos: [README](../README.md#prüfung--ci). Fachliche Unit-/Regressionstests in Rust, Android JVM und vorhandenen nativen Instrumentierungstests.

**Gezielte Regression nach Betroffenheit:**
- Profil/Projektion: 0/1/200+ Cards, 0/1/12/80 Medien, partial/unverified, Offline/Timeout, Gesten/Aktionen, Cancel/Late Write, Identitäts-/Notizkonflikte, SavedSearch-Ack und unabhängige Reprojektion. Darstellung darf keine zusätzlichen Quellrequests auslösen.
- Karte: Identitäts-Deduplizierung, Favoriten, Offline-Cache, PLZ-/Stadt-Fallback und Cancel/Lease beim Neustart.
- Architekturvertrag enthält keine Aufgabenstände oder Abnahmechronik.
