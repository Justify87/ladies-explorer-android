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

Codepfade: [Android](../app/src/main/java/de/justify87/ladiesexplorer), [Rust](../rust/ladies-core/src). Herkunft und Lizenzen: [NOTICE](../NOTICE). UI/UX: [DESIGN](DESIGN.md); Anforderungen: [PRODUCT](PRODUCT.md). Offene Aufgaben: ausschließlich [ROADMAP](ROADMAP.md). Abgeschlossene Arbeiten und Prüfbelege verbleiben in Git und den zugehörigen PRs.

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
| Persönliche Begegnungen | `encounters`, `encounter_services`, `price_lists`, `price_items` – typisierte lokale CRUD-APIs |
| Einstellungen | `app_settings` |
| Standortdaten | `listing_locations` |

### SQLite-Vertrag

- **Ablage/Identität:** App-private Datenbank `filesDir/ladies.sqlite`; `user_version = 3` und `application_id = 0x4C445850` (LDXP). Eigenständiger Android-Datenbestand ohne nutzerseitige Übernahme gemäß [PRODUCT](PRODUCT.md#produktziel-und-grenzen).
- **Schemafreigabe:** Installation und interne Schemaentwicklung transaktional vor Store-Freigabe. Bestehende native v1/v2/v3 müssen dem jeweiligen kanonischen Schema entsprechen; interne Migration auf v3 einschließlich Tabellen, Indizes, Triggern und Fremdschlüsselprüfung vor Commit validieren.
- **Ungültige Schemata:** Fremde IDs, unerwartete unversionierte Tabellen, Schemaabweichungen oder unbekannte neuere Versionen erzeugen typisierte Fehler. Die exakt bekannte Demo mit `scaffold_notes` ist ein unterstützter technischer Migrationsfall; echte Nutzerdaten nicht implizit löschen.
- **Connection:** `foreign_keys=ON` je Verbindung; Rust-Mutex schützt die Connection. Öffentliche Mutationen nutzen parametrisierte SQL-Anweisungen und Transaktionen.
- **Owner-Integrität:** Person-INSERT erzeugt Nutzerstatus durch Trigger. Trigger verhindern unzulässige Personen-/Quellenzuordnung von Inseraten und Quellprofilen; Sichtungen aktualisieren die je Quelle zugehörigen `last_seen_at`-Zeitpunkte.
- **Keyset-Cursor:** `DiscoveryCursor` über Favorit/Inserat-`last_seen_at`/`listing_id`, absteigend und Seitengrößen 1–100. Suche, SavedSearch und SavedPeople besitzen eigene Fensterverträge.
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
4. Nach bestätigt erfolgreichem Commit betroffene lokale Projektionen, Entwürfe, Dialoge und Swipe-Slots invalidieren beziehungsweise neu lesen. Ein durch Lifecycle-Stop widerrufener, weiterhin geöffneter Detailkontext übernimmt den bestätigten neuen Owner beim Commit und liest bei Resume lokal; ein durch Back geschlossener Kontext bleibt geschlossen. Bei anschließendem Readfehler die Mutation **nicht** wiederholen.

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
- Reguläre Pagination verwendet die belegte Quellseitenzahl; ohne diese höchstens einen Seite-2-Probeabruf, um sie zu bestimmen. Abweichende `currentPage`, widersprüchliche Seiten-/Count-Metadaten, ausbleibender Fortschritt (keine neue Anzeigen-ID), fehlende erwartete Folgeseite oder erreichte interne Request-Schutzgrenze ergeben `partial`. Da jede weitere reguläre Seite mindestens eine neue Anzeigen-ID liefern muss, begrenzt ein belegter `advertCount` die Abrufe zusätzlich auf Count plus eine mögliche Übergangsseite. 1.024 Seiten sind ein defensiver interner Notstopp für unplausibel große, untrusted Metadaten, keine behauptete Quellgrenze oder konfigurierbare Nutzereinstellung. Erkannter Radiusübergang ist nur bei bestätigter Vollständigkeit ein reguläres Ende. Fachliche Folgen: [PRODUCT](PRODUCT.md#suche-und-filter).

**Filter und Vorschau:**
- Filterkatalog kanonisiert Gruppen, Aliase und Slugs; Orts-API-Schlüssel case-insensitiv mit lokalem Slug-Fallback.
- Preview nutzt denselben geprüften Seite-1-Abruf mit Fallback, 350 ms Debounce und keinerlei Persistenz. Eine eigene Lease bindet die sichtbare Filteransicht bzw. Editor-Session an den STARTED-Lifecycle; Stop/Dispose widerrufen nur ihre eigene Lease. Vor I/O und Publikation müssen Lease, Request-Generation und aktueller Quellentwurf übereinstimmen. Lokale Filter beeinflussen die Seite-1-Vorschau nicht. Der Einseitenmodus ist eine interne Transportentscheidung, kein Feld von `SearchRequest`.
- Häufige Filter kommen aus `search_runs`, ohne weiteren Store.
- Ein validierter `SavedSearchDefinition`-Wert in `app_settings` unter `search:default` hält den gewöhnlichen Suchstandard unabhängig von SavedSearch-Zeilen. Rust kanonisiert und validiert Query und lokale Regeln vor atomarem Upsert; Kotlin lädt den Wert vor Zulassung gewöhnlicher Such-/Entwurfsaktionen und hält den zuletzt bestätigten Standard für Reset. Speichern bindet den gerenderten Draft und die vorhandene Pane-/Aktionsgeneration, widerruft alte Aktionen und verändert weder den laufenden Entwurf noch Run oder SavedSearch.
- `SearchScreen` hält Filtertext und Abweichungen von den normalen Abschnittsvorgaben im vorhandenen pane-/editorsessiongebundenen Saveable-State. `SearchFilterEditor` projiziert daraus sichtbare Gruppen und Werte ohne Änderung des `SearchDraft`; Treffer öffnen Abschnitte nur temporär. Kein zusätzlicher Store, kein Einfluss auf Preview-Lease, Request- oder Run-Admission.

**Zulässiger Profilabruf:**
- Nur lokal bekannte Inserate, HTTPS, erlaubte Hosts/Präfixe `/sex-anzeigen/`, `/fkk-saunaclubs/`, `/stars/` und kanonische www-URL; keine frei eingegebene URL.
- Vorhandene HTML-ID gegen erwartete ID prüfen. Relative, protokollrelative und CSS-Medienreferenzen auflösen, Reihenfolge bewahren; A- vor F-Variante derselben Foto-ID, Revisionsparameter sowie 18+-/Sterne-Marker erhalten.
- Such-/API-/Profilstruktur und CDN-Varianten durch frühere lokale HARs und synthetische Regressionen belegt. Keine frische Sitzung/Alters-/Cloudflare-Freigabe daraus ableiten. Selektoren und Klassifikationen nur mit neuer Evidenz ändern; keine privaten HARs in Git.

**Statuschecks:** Statusantworten und Delta-Commit verwenden Owner-/Versionsprüfungen unter [Gespeicherte Personen und Suchen](#gespeicherte-personen-und-suchen). Statusbatch sequenziell, mit 350 ms Pause und Cancel bei Navigation/`ON_STOP` oder Aktion; keine Inhalts-/Archivoperation. Fachliche Statussemantik in [PRODUCT](PRODUCT.md#gespeicherte-personen).

### Persistentes Archiv und Anzeige

**Archiv und Snapshots:**
- App-private Bilder: `ladies_data/media/images/`. SHA-256-Deduplizierung; JPEG/PNG/WebP/GIF-Dimensionen und Pixelbudget vor voller Dekodierung prüfen. Der 64-bit-dHash verwendet Legacy-kompatiblen Links-/Rechtsvergleich mit LANCZOS.
- Archivierung folgt dem Snapshot als **separater** Vorgang. Der gerenderte Stopp bindet die sichtbare Standalone-Session und den Archivierungsversuch; pro Session läuft höchstens eine Archivierung gleichzeitig. Überholte Stopp-Callbacks erreichen keinen fremden Auftrag. Interne Back-/Lifecycle-/Merge-Cancel bleiben unabhängig; der native Auftrag besitzt seinen Slot bis zum Drain. Fehler oder Cancel entfernen keine gespeicherten Profildaten oder bereits archivierten Dateien. Medienrevision und lokale Existenz steuern Wiederverwendung.
- Videoreferenzen werden standardmäßig ohne dauerhaftes Videoarchiv gespeichert; Wiedergabe über vorhandene Archivdatei oder temporäre lokale Anzeigedatei. Ein opt-in-Archivierungspfad existiert im Rust-API.

**Medienabrufgrenzen:** Rust prüft bekannte HTTPS-Hosts und URL-Formen, verbietet Redirects und begrenzt Ressourcen auf Bilder 12 MiB, Videos 48 MiB, 24 Mio. Bildpixel, 80 Referenzen pro Archivaufruf, 15 s pro Request und 150 s Gesamtbudget fürs Archiv. [media.rs](../rust/ladies-core/src/media.rs).

- **Bilder:** Coil 3 nutzt lokale Archivdateien zuerst, dann Remote-Fallback über `coil-network-core → RustImageNetworkClient → CoreStore.loadDisplayImage → UniFFI → Rust`. Vier gleichzeitige blockierende Bildanzeigeabrufe sind über den IO-Dispatcher begrenzt. Coil besitzt URL-/Revisionsschlüssel, Memory-/Disk-Cache, Dekodierung und Zusammenführung identischer Cache-Anforderungen; UniFFI überträgt begrenzte kodierte Bytes. Fehler führen zu sichtbaren Platzhaltern. Reine Anzeige archiviert nicht und schreibt nicht in SQLite.
- **Videos:** `GalleryVideo` bindet Player, Medium und lokale Datei an den sichtbaren Galerieeintrag und RESUMED-Lifecycle. Archivdatei zuerst, sonst `CoreStore.loadDisplayVideo → UniFFI → media::display_video` mit Rust-Host-, Größen-, Timeout-, HAR- und Cancel-Vertrag. Eigener IO-Dispatcher mit Parallelität 1. Validierte MP4/WebM-Datei im App-Cache; kein neuer Archiv-/DB-Eintrag. libmpv erhält ausschließlich einen lokalen Pfad, erzwungener lavf-Container und `protocol_whitelist=file` als lavf-Option; `access-references=no`, `config=no`, `load-scripts=no`, `ytdl=no` und `autoload-files=no` sperren externe Referenzen, Konfiguration, Skripte, URL-Helfer und automatisch geladene Begleitdateien. Wiedergabeverhalten gemäß DESIGN; Hardwaredecoder soweit verfügbar. Player stoppt außerhalb der gültigen sichtbaren Projektion bzw. bei Lifecycle-/Busy-Wechsel. Surface-Freigabe nach nativer Zerstörung; erneuter Surface-Owner nur bei weiterhin gültigem Medium. `GalleryVideoFiles` nutzt validierte temporäre Dateien gemeinsam; Cachetreffer warten nicht auf den separaten Download-Mutex. Fensterpins und aktive Wiedergabeleases verhindern Verdrängung. Das Dateibudget beträgt 256 MiB, davon höchstens 208 MiB für Pins, mit 48 MiB Reserve für kalte Wiedergabe. Nur erfolgreich vorbereitete Videos behalten Fensterpins. Budgetbedingte Ablehnung der Vorbereitung lässt reguläre Wiedergabe über die Reserve zu. Cachetreffer werden auf Dateiexistenz, Größe und Containerkennung geprüft; ungültige Dateien sowie ein expliziter Retry nach Medienwiedergabefehler werden gezielt ersetzt. Eine aktive Lease wird nicht verletzt; ein Ersatz wartet begrenzt auf ihre Freigabe. Vorhandene Pins bleiben bis zur Ersetzung erhalten und werden innerhalb des Pinbudgets übernommen; bei größerem Ersatz darf die sichtbare Wiedergabe die Reserve ohne Pin nutzen. Der Vorbereitungsworker ist allein für Pin-Pruning zuständig und berücksichtigt Bestandsänderungen auch ohne neue Vorbereitungsarbeit. Ein Medienretry darf eine nicht abspielbare Archivdatei überspringen, löscht sie nicht und verbraucht den Refresh nach abgeschlossener Dateibeschaffung einmalig. Solange die reparierte Cache-Datei vorliegt, wird dieselbe abgelehnte Archivversion (Pfad, Größe, Änderungszeit) nicht erneut bevorzugt. Dateien außerhalb gültiger Fenster sind LRU-verdrängbar; fehlerhafte Dateien und verwaiste Dateien nach Prozessneustart werden bereinigt.

## Oberfläche und Navigation

`ShellRoute` und `LadiesApp` besitzen Root-Navigation gemäß DESIGN; Entdecken-Modi und Settings-Untermenüs sind lokale, separat wiederherstellbare Routen. Back aus einem Settings-Untermenü kehrt zuerst zur Einstellungsübersicht zurück. `ShellRoute` hält den Grid-/Kartenursprung für die reguläre Swipe-Profilöffnung; `SaveableStateHolder` erhält den Rückkehrzustand. Listing-/Owner- und Surface-Admission binden den Einstieg an die gültige Mitgliedschaft. Standalone-Profile überlagern die Root-Navigation und behalten den tatsächlichen Ursprung. Status und Back werden vom zuständigen Owner koordiniert; reine Status-Reads sperren Navigation nicht.

### Suche, Entdecken und Grid

**Run und Sichtbarkeit:**
- `MainViewModel` trennt Filter-Snapshot des **ausgeführten** Runs vom Entwurf.
- Normale Filteränderungen binden die sichtbare Filteransicht und deren Ansicht-/Pane-Generation; aufeinanderfolgende Eingaben ändern den aktuellen Entwurf ohne Snapshot-Sperre. Reset prüft zusätzlich den gerenderten Entwurf und widerruft die bisherigen UI-Aktionen. Beide erhalten den ausgeführten Run. Der lokale Wechsel zur Ergebnisansicht verwirft alte Filter-Callbacks.
- Der normale Suchstart bindet den gerenderten Entwurf einschließlich lokaler Sichtbarkeitsregeln und Ansichtsgeneration an den sichtbaren Filterkontext. Veraltete oder gesperrte Aktionen erreichen keinen Quellabruf. Normale Suche und Schnellwahl verwenden denselben nativen Such-/Drain-Pfad.
- Der Suchabbruch aus Filtern, Grid und Swipe bindet die gerenderte Ansichtsgeneration und die native Vorgangs-ID des laufenden Suchversuchs, bei Swipe zusätzlich die sichtbare Swipe-Session. Nur die aktuelle sichtbare, noch nicht abbrechende Suche darf den nativen Abbruch signalisieren. Der neue Suchstart widerruft alte Abbruch-Callbacks auch nach Fehler oder Abbruch; Lifecycle-Cleanup und native Drain-Grenze bleiben unabhängig.
- Die Grid-/Karten-Schnellwahl bindet die gerenderte Oberfläche, Ansichtsgeneration und den ausgeführten Run, ohne aktiven Run den gerenderten Entwurf. Veraltete Callbacks erreichen keinen neuen Quellabruf; der Beginn eines Suchlaufs widerruft alte Aktionen auch bei Fehler oder Abbruch. Alle Modi nutzen denselben Suchpfad.
- Rust liefert `activeSearchCards` in Positions-/ID-Reihenfolge mit aktuellen Owner-/Präferenz-/Favoritenzuordnungen. Grid liest diese aktive Ergebnismenge, **nicht** die bereits konsumierte Swipe-Queue. Die zwei globalen Ausblendungen gehören wie Replay-Likes zu den lokalen Android-Explorer-Einstellungen. `CoreStore` liest sie beim Start unabhängig vom Suchstandard; `GlobalResultVisibility.effective` kombiniert sie per ODER mit `SearchResponse.local` vor dem nativen Mitgliedschaftsread. Initialer Run, lokale Reprojektion und Run-bezogener Filtermatch nutzen dieselbe Kombination; der native `LocalVisibility`-Vertrag bleibt maßgeblich. Keine Änderung der gespeicherten Definitionen, Run-Filter, SavedPeople- oder SavedSearch-Projektionen. Ein zugelassener Einstellungswechsel widerruft alte Deck-/Aktionsgenerationen und löst die vorhandene lokale Reprojektion einschließlich Kartenmitgliedschaft aus; keine erneute Quellsuche. Beim Wechsel der globalen Regel ist der aktive Ergebnisbestand bis zur gültigen lokalen Publikation nicht sichtbar oder für Profilöffnungen zugelassen. Readfehler melden unbekannte lokale Treffer statt eines bestätigten Null-Counts.
- Neutraler Startbestand verwendet Präferenz `NONE`; `DiscoverUiState.localCards` hält das lokal geladene Mitgliedschaftsfenster unabhängig von der Deck-Traversierung. `discoveryMembership` ist der gemeinsame Zugriff für die drei Modi. Ein expliziter Grid-/Karteneinstieg fokussiert das gültige Listing im bestehenden `SwipeSession` ohne künstliche Entscheidung oder Reset. Im aktiven Run bestimmen natives Filterergebnis, sessionlokale Traversierung und Replay-Likes-Policy gemeinsam zulässige Swipe-Karten. Fachvertrag: [PRODUCT](PRODUCT.md#entdecken-und-entscheidungen).

**Entscheidung:**
- `SwipeSession` hält Traversierung/Undo-Records, keinen zweiten fachlichen Store.
- Geste und Rail teilen dieselbe gerenderte Admission und native CAS-Präferenzoperation; Queue-Projektion ist autoritativ.
- Grid-Geste und zugängliche Aktionen binden gerenderten Listing-/Owner-/Präferenz- und Run-Snapshot samt Aktionsgeneration und nutzen dieselbe native CAS-Präferenzoperation. Kein Grid-Hide oder Deck-Visit. Die stabile Listing-Kachel animiert native Lazy-Grid-Mitgliedschaftsänderungen; der gerenderte Action-Snapshot widerruft Gesten und Clickables unabhängig von der Medienkomposition. Run-/Sichtbarkeitswechsel und unbekannte Mitgliedschaft verwerfen alte Animationslayer statt alte Karten auszublenden; temporäre Lade-/Fehlerzeilen ersetzen den gültigen Scrollanker nicht. Ein einzelnes `GridUndoReceipt` bindet den bestätigten Vorher-/Nachher-Zustand an Run, Listing und Owner; Undo nutzt den vorhandenen CAS-Rückschreibvertrag ohne Deck-Traversierung. Grid verlassen, erfolgreicher neuer Run oder konkurrierende Mutationen widerrufen das Receipt. Fehlgeschlagenes Undo widerruft das Receipt und reconciliert den Bestand; kein automatisches Wiederholen.
- Fehler, Commit und Kontext: [Operationsmodell](#operations--und-konsistenzmodell) und PRODUCT.

**Unabhängige Projektionen:**
- `DiscoveryProjectionPublisher` aktualisiert lokale Entdecken-/Grid- und Saved-People-Projektionen mit getrennter Fehlerbehandlung und gemeinsamer Gültigkeitsprüfung.
- Lokale Grid-Pagination bindet manuellen und automatischen Callback an den gerenderten Cursor und die Aktionsgeneration. Nur die sichtbare Grid-Ansicht ohne aktiven Suchlauf oder konkurrierende Operation darf nachladen; die Admission widerruft alte Callbacks auch bei anschließendem Readfehler. Nach einem zugelassenen Swipe-Skip kann dieselbe lokale Lesefunktion ohne Grid-Callback nachladen. Die bestehende Projektionsepoch schützt die Publikation; kein automatischer Grid-Retry nach Readfehler.
- Der manuelle Grid-Refresh bindet den gerenderten ausgeführten Run (oder lokalen Startbestand) und die Aktionsgeneration. Nur die sichtbare, nicht beschäftigte Ansicht darf vor I/O alte Aktionen widerrufen und den gemeinsamen lokalen Projektionsread auslösen; laufende lokale Pagination darf über die bestehende Projektionsepoch ersetzt werden. Fehler bleiben explizit erneut ladbar; interne Reprojektionen bleiben unabhängig.
- Präsenz/Alter aus vorhandener eindeutiger Quellen-/Snapshot-Evidenz, keine dekorativen Profilanfragen.
- Karte projiziert die gemeinsame Listing-Mitgliedschaft: [Karte und Sicherheitsgrenzen](#karte-und-sicherheitsgrenzen).

### Gemeinsame Profilarchitektur

**Datenhoheit und Render-Modell:**
- `NativeProfileProjection` liest Profil, kanonische Identität, Person, Medien, Historie und Kandidaten unter **demselben SQLite-Guard**.
- `ProfileReadModel` bindet Projektion, Herkunft/Fehler und lokale Revision. Swipe und Standalone teilen den lokalen Lese-/Präsentationsvertrag, aber nicht denselben Quellen-/Archiv-Lebenszyklus.
- Standalone: `StandaloneProfileOperations` liest frische Quelldaten; der geöffnete Detailkontext darf keinen stillen Wechsel des ursprünglich gewählten Owners akzeptieren, außer nach explizit bestätigtem Identitätsmerge. Bei Fehler aktiven lokalen Snapshot samt Medien mit Herkunft/Warnung zeigen. Erfolgreiche Abrufe dürfen separaten Archivvorgang auslösen; Offline-Fallback löst keinen erneuten Netzarchivvorgang aus.
- Swipe: aktuelles plus höchstens fünf Folgeprofile ohne zusätzliche Archivierung; Current-/Prefetch-Vertrag unten. Explizite Profilaktualisierung bindet den vollständigen gerenderten Profil-Token einschließlich Revision und prüft ihn unmittelbar vor Aufnahme in den vorhandenen priorisierten Profilread erneut; überholte Anforderungen verfallen ohne zusätzlichen Quellenabruf. Standalone verwendet bei expliziter Aktualisierung den bestehenden Quellen-/Archivpfad innerhalb derselben Profil-Session; veraltete Tokens, laufende Reads und Archivierung sperren die erneute Aktion. Deckaktualisierung und Reset bleiben getrennte Operationen.

**Oberfläche und sichtbarkeitsgebundene Reads:**
- `ProfileShell`/`ProfileFlow` teilen Profiloberfläche, erhalten separate Navigations-/Operationskontexte. [DESIGN](DESIGN.md#profile-und-medien) bestimmt sichtbare Inhalte.
- `ProfileShell` misst im Swipe die Root-Navigation und die vollständige Discover-Kontextzeile einschließlich Abstand unabhängig von deren Platzierung. `ProfileHeroLayout` verwendet das daraus berechnete Hero-Body-Budget auch im Lesezustand. Der Hero füllt bei ausreichendem Platz den verbleibenden Viewport ohne künstlichen Detail-Peek. Die erste sichtbare Lazy-Zeile bestimmt den Lesezustand; die Root-Navigation wird dann nicht platziert und die Rail erhält ein kompaktes Budget. Zusätzliche Body-Höhe wird am Listenende gepuffert, damit die veränderte Viewportgröße den Anker nicht zurück in den Hero drückt. Die Medienvorbereitung erhält die vom Hero-Budget abgeleiteten Pixelmaße auch bei Restore außerhalb der Galerie. `ProfileFlow` setzt gemeinsame Kontakte und einen kompakten Identitätskopf nach der Galerie; Anzeigenanzahl aus der zugehörigen `PersonSummary` und optionale gültige Veröffentlichungsangabe aus dem Listing-Snapshot haben dort ihren primären Darstellungsort. Die UI formatiert belegte ISO-Datums-/Zeitwerte kompakt mit erhaltenem Quelloffset, ohne eine fehlende Zeitzone zu ergänzen; ungültige Werte bleiben ohne sichtbaren Zeitpunkt. Positive native Neuigkeits- und Videomerkmale stehen im selben Identitätskopf; fehlende positive Evidenz wird nicht als negativer Quellenstatus interpretiert. Der Quellenstatus ist keine Live-Verfügbarkeit. Beide Profilheader übernehmen die Identität, wenn der erste sichtbare Schlüssel hinter Galerie, Kontakten und Identitätszeile liegt. Swipe zeigt bei zu wenig Platz die Decision Rail inline im Scrollfluss. `ProfileShell` führt Statushinweise in beiden Kontexten über den Header und einen gemeinsamen Dialog mit eindeutig zugeordneten, kontextgebundenen Aktionen; Status-/Busy-Wechsel ändern weder Body-Budget noch Medienviewport.
- Die Detailidentität verwendet Stadt und PLZ aus der aktiven, demselben Listing zugeordneten Snapshot-Projektion. `parse_profile` akzeptiert versteckte Ortsmetadaten nur im Adresskontext; historische Rohwerte bleiben erhalten, werden aber nicht als sichtbare Ortsangabe projiziert. Keine zusätzliche Ortsauflösung oder Fremd-Owner-Fallbacks.
- Kontaktziele werden aus der Snapshot-Telefonnummer gemeinsam syntaktisch validiert und normalisiert. Der explizite Tap prüft den gültigen Profil-Token und Busy-Zustand; Dialer und WhatsApp verwenden ausschließlich die abgeleiteten Ziele.
- `ProfilePreparationWindow` liest Facts und PersonDetail vor der Profilpublikation; `ProfilePanels` übernimmt diese Ergebnisse einschließlich Fehlern und bietet expliziten Retry am betroffenen Inhalt. Die lebenden Panel-Fehler und ihre Retry-Ergebnisse sind maßgeblich; die Medienwarnung der Shell berücksichtigt ausschließlich fehlgeschlagene Medienvorbereitung. Notiz, Begegnungen, Preislisten und Snapshotvergleich behalten ihre projektionsgebundene Bedarfs-/Editorsemantik. Profilabschnitte verwenden einen flachen Rahmen mit Trennlinien und den gemeinsamen `ProgressiveSectionHeader` für nachrangige Nachweise, Originaltext und private Funktionen. Die Suchübereinstimmung zeigt die getrennten Gruppenzusammenfassungen direkt; vollständige Belege sind standardmäßig geschlossen. Der Suchfiltereditor behält seine eigene `ProgressiveSection`-Oberfläche. Mehrzeilige Profilbereiche behalten einzeln komponierte, durch Abschnittszuordnung zusammenhängende Lazy-Zeilen und ihre stabilen Schlüssel. Öffnen und Schließen ändert ausschließlich Präsentationszustand; Bedarfsreads bleiben an die gültige Projektion gebunden.
- `group_values` speichert Werte je tatsächlicher Quellüberschrift; `ProfileFacts.services` liefert diese Gruppen unverändert. `ProfileFlow` zeigt die strukturierten Facts direkt, mit adaptiven Merkmalszeilen und allen Werten je tatsächlicher Dienstgruppe. Die rein präsentationale Verdichtung fasst ausschließlich aufeinanderfolgende Wochentage mit exakt gleichen Wertlisten zusammen; andere Zeitgruppen und mehrteilige Werte bleiben erhalten. Die UI führt identische Werte bekannter Merkmalsaliasse unter erhaltenen Quellbezeichnungen zusammen. Ein im Identitätskopf belegter identischer Alterswert und native positive Dienstevidenz mit äquivalenter sichtbarer Angabe innerhalb zulässiger Dienstgruppen werden nicht erneut ausgegeben; negative und unbekannte Evidenz bleiben explizit. Die Darstellung ermittelt keine Evidenzstatus selbst. Strukturierte Abschnitte verwenden getrennte Lazy-Zeilen mit semantischen Schlüsseln für Überschriften, Merkmalszeilen, Dienstgruppen und Suchbelege. Bei einem Facts-Readfehler bleibt der logische Inhaltsanker bis zum expliziten Retry oder bewussten Weiterscrollen erhalten; ein erfolgreicher Retry stellt den gültigen Schlüssel wieder her oder verwendet den deterministischen Abschnittsfallback. `profileFacts` löst die UI-seitig zugelassene Run-ID mit ausgeführten lokalen Regeln oder die gespeicherte Such-ID anhand der nativen, abgeschlossenen Ergebnismitgliedschaft und des aktuellen Listing-/Owner-/Snapshot-Bezugs auf. Die gespeicherte Definition ist gültig, solange ihr Ergebnisbestand bei fachlicher Änderung invalidiert wird; fehlende historische Medienmarker bleiben unbekannt. Rust projiziert nur die wirksamen Gruppen und ihre Alternativen, numerische Bereiche, Rubrik und lokale Regeln anhand der vorhandenen Evidenz; lokale Kriterien verwenden `LocalVisibility::allows`. Kein Rückschluss auf Einzelentfernung aus dem Suchradius. Kotlin stellt die nativen Gruppenstatus dar und zählt Gruppen, nicht Optionen; lokale Sichtbarkeitsregeln werden getrennt von den übrigen Suchkriterien zusammengefasst und vollständig belegt. Facts und PersonDetail werden nur für gültiges Listing/Owner/Snapshot/Suchkontext/Revision wiederverwendet. Expliziter Retry benötigt den aktuellen Profil-Token und die gerenderte Read-Generation. Der Vergleichsdialog schließt nur seine eigene Vergleichsgeneration; internes Cleanup bleibt unabhängig. Analyse, Historie und Vergleich erzeugen **keine** zusätzlichen Quellrequests durch bloße Darstellung; Snapshotvergleich prüft Owner und Listingzuordnung.

**Notiz / Konflikt und Lifecycle:**
- Notiz-Reads/Retry besitzen einen gemeinsamen projektionsgebundenen Job. Entwürfe und Tickets sind im ViewModel ownergebunden, über Navigation/Rotation, **nicht** Prozessende erhalten.
- Speichern, Verwerfen und Konfliktauflösung binden neben dem Profil-Token den exakt gerenderten Notizentwurf einschließlich erwarteten und beobachteten Texts. Veraltete Aktionen werden vor Mutation verworfen; laufende Reads sperren Entwurfsaktionen, Readfehler erlauben weiterhin explizites Verwerfen. Fortlaufende Texteingaben binden keinen unveränderlichen Entwurf.
- Speichern vergleicht transaktional aktuellen Besitzer und erwarteten Text, schreibt ausschließlich `private_note` und `updated_at`.
- Notizkonflikte explizit auflösen; Identitäts-Merge blockiert bei betroffenen ungespeicherten Entwürfen.

**Persönliche lokale Datensätze:**
- `encounters.rs` besitzt transaktionale CRUD- und Owner-/Editor-CAS-Prüfungen einschließlich optionaler Inseratszuordnung, Grundpreis und vollständiger persönlicher Leistungszeilen. Kindzeilen werden innerhalb derselben Transaktion gelesen und bei Änderung ersetzt; Löschen nutzt den Fremdschlüssel-Cascade. Gesamtbetrag wird nicht berechnet.
- `prices.rs` verwaltet manuelle EUR-Preislisten und Positionen in den vorhandenen Tabellen. Jede Korrektur vergleicht Owner, vollständige Werte und Positions-IDs; neue Positionen werden vor dem Löschen der alten eingefügt, damit ein älterer Editor auch nach einer Rückkorrektur nicht wieder gültig wird. Löschen ist atomar mit Cascade. Die native Preisübersicht sortiert nach erfasstem Datum, internem Erstellungszeitpunkt und ID als stabilem Fallback. Neue Listen desselben Datums erhalten eine eindeutige Erstellungsreihenfolge über den vorhandenen `created_at`-Wert (bei gleichem oder rückläufigem Takt mindestens +1 ms); Korrekturen erhalten diese Reihenfolge. Bei historischen identischen Zeitstempeln ist die frühere Reihenfolge nicht rekonstruierbar; den letzten bekannten bezahlten Gesamtbetrag liest sie separat aus Begegnungen, nicht aus Preispositionen.
- `CoreStore` vermittelt typisierte native Zugriffe. `ProfileRecordEditor` teilt Admission, Read-/Retry- und Entwurfszustand für Begegnungen und Preislisten. Pro ViewModel ist höchstens ein lokaler Datensatzeditor aktiv (nicht über Prozessende). Der gemeinsame `ProfileFlow` fordert Reads nur für die geöffnete Sektion oder zur Wiederaufnahme des aktiven Entwurfs an; keine Vorausladung persönlicher Datensätze.
- Ein Entwurf behält den ursprünglichen CAS-Stand bei Kontextwiederaufnahme. Veraltete Aktionen, Mehrfachschreibvorgänge, Navigation und Merge bei offenem Editor werden nicht zugelassen. Back/Abbrechen erfordert explizites Verwerfen eines Bearbeitungsentwurfs.
- Schreibvorgänge nutzen den gemeinsamen Mutationsguard in `MainViewModel`, drainieren bestätigte native Operationen und aktualisieren bedarfsgebunden lokale Datensatzprojektionen, nicht Discovery-/SavedSearch-/Quellprojektionen. Ein bestätigter Begegnungs-Commit invalidiert zusätzlich die Preisübersicht wegen des abgeleiteten bezahlten Betrags, unabhängig von einem gerenderten Busy-Zwischenzustand. Ein anschließender Readfehler erlaubt Read-Retry, keinen Write-Retry. Personennotiz und andere Profilwerte bleiben getrennt.

### Gesten, Präsentation und Undo

**Gesten-Admission:**
- `PhotoGestureResolver` klassifiziert denselben Hero- bzw. Grid-Karten-Pointer-Stream anhand Android-Touch-Slop, gemessener Grenzen und dominanter Achse; [Schwellen/Interaktion](DESIGN.md#gesten-und-präsentation).
- Native Clickables besitzen Galerie-Taps, Fokus und Semantik; interaktive Kindziele haben Vorrang. Die gemessene Favoritenfläche ist als vorderstes natives Kind exklusiv; seitliche Galerieziele bleiben außerhalb dieser Fläche vollständig wirksam; Video-Fit verwendet die native Display-Aspect-Ratio. Horizontaler Lock übernimmt den Stream; vertikales Scrollen bleibt bei der LazyColumn.
- Mehrfinger, Fokus-/Geometrie-/Kontextwechsel, Pause, Back, Cancel oder widerrufene Touch-Intention verhindern den Commit.
- `ProfileTouchGate` widerruft gesamten Touch-Stream bis zum letzten Up; Semantik-/Tastaturaktionen bleiben nativ. Standalone: Galerie-Taps ohne Swipe-Entscheidung.

**Flight ist nur Präsentation:**
- `SwipeFlight` rendert einen **inerten** visuellen Übergang, keine optimistische Queue oder fachliche Mutation. Nur vollständig sichtbare, dekodierte Fotos liefern die tatsächliche Position, Größe, Crop/Fit-Entscheidung und den erfolgreich geladenen Painter; andernfalls entfällt der Bild-Flight. Der ausgehende Flight zeichnet denselben Painter ohne erneuten Medienrequest oder Quellenwechsel.
- Drag bis Admission und autoritativer Antwort sichtbar. Ausgehendes Bild im stabilen Viewport animieren, neuen Profilfluss mit stabiler Geometrie.
- Begrenztes Warten auf Dekodierung mit ehrlichem Cache-Fallback. Keine künstlichen Snapshots für ungeeignete Medien oder Detailscrollzustände.
- Während Flight betreffende Eingaben kurz sperren; fachliche Admission bleibt maßgeblich.

**Präsentationszustand und Undo:**
- `ProfilePresentation` hält Medien-ID/Index, stabile Row-Keys/Offset und Sektionen **getrennt** vom fachlichen Read-Model.
- `SwipeSession` speichert höchstens 16 jüngere Präsentationsreferenzen zu Undo-fähigen Visits, gebunden an Run/Listing/Owner/Snapshot/Filter. Ältere/unpassende Referenzen kontrolliert zurückfallen lassen.
- Undo-Receipt einmalig nach gültiger Anwendung oder eindeutig unpassendem Kontext konsumieren; bei Readfehler erhalten. Leeres/abweichendes autoritatives Deck verwirft unpassendes Receipt; Rail bei leerem Deck bleibt für gültiges Undo erreichbar.

**Restore und Invalidierung:**
- `ProfileRestoreCoordinator` koordiniert Auftrag, besitzt **nicht** Medien-/Scroll-/Sektionszustand.
- Row-Manifest ist stabile Schlüsselgrundlage und Datenbedarfsquelle. Bei Snapshot-/Filterwechsel Schlüssel prüfen und Pixeloffset auf sicheren Zeilenanfang beziehungsweise gültige Größe begrenzen; Medien-ID vor begrenztem Index.
- Kein zusätzlicher Quellenabruf oder dauerhafter UI-Profilcache fürs Restore. Reset erhält eigene Präsentationsgeneration.

### Profilvorbereitung und Swipe-Read-Window

**Fenster und Owner:**
- `swipeReadWindow` begrenzt die Vorbereitung auf Current+5; Standalone hält einen Slot. `SwipeProfileWindow` bindet native Reads an Listing, erwarteten Owner und Ticket. `ProfilePreparationWindow` hält die davon getrennte vorbereitete Präsentation.
- Ein Swipe-Worker wählt nach jedem Profilread, Panelread oder Medium den ersten noch nicht abgearbeiteten Slot der aktuellen Reihenfolge. Promotion übernimmt vorhandene Arbeit; Pruning, Lifecycle-/Route-/Owner-/Revisionswiderruf canceln oder verwerfen nicht mehr gültige Arbeit. Native Quellenreads behalten ihre bestehende Drain-/Cancel-Grenze.
- Aktive Suchläufe lösen den regulären Quellenversuch bereits im Folgeprofil aus. Local-only, bevorzugte lokale Recovery und lokale Startbestände erzwingen keinen Refresh. Spekulative Fehler erlauben einen Versuch bei Promotion; Fehler des aktuellen Profils warten auf expliziten Retry oder erneute Promotion. Lokaler Reprojektionsfehler löst keinen zweiten Quellenread aus.

**Bereitschaft und Medienbudget:**
- Ein Slot publiziert erst, wenn die autoritative Profilprojektion, Facts, PersonDetail und alle Medienreferenzen als erfolgreich vorbereitet oder explizit fehlgeschlagen/ressourcenbegrenzt vorliegen. Keine vorläufige Thumbnail-Galerie; kalte Slots zeigen einen stabilen Ladezustand. Bei unzureichender Bandbreite oder Budget gibt es keine Zusicherung, dass fünf Slots gleichzeitig vollständig erfolgreich sind.
- Coil liefert dekodierte Bilder in gemessener Hero-Pixelgröße mit Fit; das Fenster hält erfolgreich dekodierte Bitmaps mit höchstens 96 MiB zusätzlichem Referenzbudget. Hero rendert diese unmittelbar statt eines neuen Coil-Painters. Budget- und Nichtbitmapfälle sind aufgeschobene Vorbereitung, keine Medienfehler. Fehler und aufgeschobene Medien nutzen bei sichtbarer Auswahl den normalen Coil-/Video-Ladepfad, mit explizitem Retry nach endgültigem Anzeigefehler. Erfolgreiche sichtbare Wiederherstellung löscht die Vorbereitungswarnung nur für die passende veröffentlichte Projektion, ohne einen Painter zu ersetzen. Videos werden als validierte lokale Dateien mit Fensterpin vorbereitet, ohne Vorab-Player oder zusätzliche Archivierung.
- Die Präsentation behält das zuletzt veröffentlichte Listing/Owner bis zur kohärenten Reprojektion. Bei abweichendem vorbereitetem Filterkontext oder widerrufener Profilprojektion entfallen alte Suchübereinstimmung und fachliche Aktionsfreigabe, nicht statische Profilfelder, Galerie oder laufende Wiedergabe. Gleiche Snapshot-/Medienidentität und Viewport verwenden erfolgreiche beziehungsweise aufgeschobene Medien erneut; fehlgeschlagene Ergebnisse werden bei neuer Projektion nicht übernommen. Im selben Vorbereitungsdurchlauf sind alle Ergebnisse terminal, damit kein automatischer Retry-Loop entsteht. Externe Revision, Ownerwechsel und Pruning verwerfen auch die Präsentation. Spekulative Discovery-Reprojektion setzt keinen sichtbaren Loading-Zustand; die Deckgeneration ändert sich bei tatsächlicher Mitgliedschaftsänderung, verspätetem externem Commit oder den regulären Nutzeroperationen.
- Hero-Geometrie und Painter entstehen gemeinsam aus dekodiertem Bild; bei explizitem Retry nutzt der Hero aktuelle Medienquellen und umgeht für diesen Request Coil-Memory-/Disk-Cache-Reads bei erlaubten Writes. Erfolgreiche Painter bleiben stabil; ausgeschöpfte Ladeversuche werden bei neuer Quelle oder Publikation erneuert. Ein anderes Medium übernimmt keinen laufenden Drag-Rücklauf. Crop nur bei mindestens 85 % erhaltener dekodierter Bildfläche; stark abweichende Formate und Videos bleiben Fit. Video zeigt die native Oberfläche erst mit gültiger Displaygröße.
- Standalone und Swipe nutzen dieselbe Vorbereitung; Archivierung bleibt ein separater Standalone-Vorgang. Ein nachträglich verfügbarer lokaler Pfad für dasselbe Medium startet nicht den sichtbaren Painter oder Player erneut. Snapshotvergleich, Benutzerentwürfe und explizite Schreibaktionen behalten ihre eigenen Admission-Verträge.
- Verspätete native Commits aktualisieren abhängige lokale Projektionen unabhängig von UI-Admission; Publikation nur im gültigen Kontext gemäß [Operationsmodell](#operations--und-konsistenzmodell).

## Gespeicherte Personen und Suchen

### Personen und Status

- `saved_people_window` in Rust projiziert Favoriten, Likes und Dislikes nach stabiler Personen-ID. Gerenderte Aktionen binden Auswahl, Fenster-/Navigationsgeneration und vollständigen Row-Snapshot; nur atomare Rust-CAS-Operationen schreiben Präferenz/Favorit, für kartenlose Personen ohne erfundenes Listing.
- Recent (Produktzeitfenster) und Regular samt Favoriten-/Datum-Counts entstehen unter demselben SQLite-Guard und Zeitanker. Manuelle und automatische Pagination binden die gerenderte Regular- beziehungsweise Recent-Fensterinstanz, Auswahl und Aktionsgeneration. Nur das sichtbare, nicht beschäftigte Fenster mit Folgeseite darf vor I/O seine Kapazität erweitern. Nachladen liest ein kohärentes erweitertes Fenster; Kotlin hält nur Kapazität, Auswahl und Scrollzustand.
- Tabwahl, Sortierung und explizites Laden binden die gerenderte Auswahl und Ansichtsgeneration; veraltete Callbacks nach Navigation, Reprojektion oder Lifecycle-Wechsel erreichen keinen Read. Ein zugelassener Wechsel oder Reload widerruft alte Aktionen vor I/O und darf lokale Pagination über die vorhandene Projektionsepoch ersetzen. Interne Reprojektionen bleiben unabhängig.
- Das Saved-People-Fenster liest wachsende Präfixe; ein zusätzlicher Treffer signalisiert weitere Ergebnisse. Die ausgegebene Zeitgrenze kennzeichnet das gemeinsame Beobachtungsfenster; Auswahl und Aktionsgeneration bleiben an die gerenderte Fensterinstanz gebunden. Namenssortierung mit NFD, Case-/Mark-Folding und ß/ss (keine vollständige CLDR-Kollation). Metadaten fallen deterministisch auf vorhandene Daten zurück; Relisting behält fachlichen Ereigniszeitpunkt.

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
- Unveränderte Definitionen bleiben byte-identisch. Das obsolete Feld `max_pages` älterer nativer Definitionen wird beim Dekodieren ignoriert und in neuen Definitionen nicht serialisiert; ein alter Wert begrenzt keinen Quelllauf. Unveränderte Altbytes bleiben für Revision/CAS erhalten. `SearchDraft.from(query, local)` und Updates mit beiden typisierten Anteilen erhalten unterstützte lokale Filter bei reinen Quellenparameteränderungen.

**Ausführung und Delta-Commit:**
1. `saved_search.rs` erfasst **vor dem Quellabruf** Such-ID, rohe `filters_json`-Definitionsbytes und `definition_version`.
2. Nur ein vollständiges **radiusbestätigtes** Ergebnis darf die gespeicherte Mitgliedschaft aktualisieren. Teilresultate und unbestätigte Nulltreffer schreiben kein Delta.
3. **In derselben SQLite-Transaktion unmittelbar vor dem Delta-Commit** Existenz der Such-ID, byte-identische Definition und unveränderte Version erneut prüfen; Cancel nochmals vor Commit prüfen.
4. Geänderte/gelöschte Definition oder Cancel verhindern alte Deltas, **nicht** bereits bestätigte reguläre Suchcommits.

**Versuch/Fehler/Abbruch:**
- `last_attempt_at`/`last_error` für unvollständige Läufe und Quellenfehler nur mit **derselben ID, Version und Definitionsbytes** speichern; Cancel vorher prüfen. Fehler einer alten Definition dürfen nicht auf eine neue gelangen.
- Eine fehlgeschlagene Aktion veröffentlicht kein Ergebnis für einen fremden Kontext.

**Fenster, Revision und Quittierung:**
- Lokale Profilmetadaten und Suchübersicht zusammen mit dem Ergebnisfenster kohärent unter derselben Connection-Sperre projizieren. Nachladen liest ein größeres Präfix derselben Such-ID, geordnet nach Position und Result-Key; ein zusätzlicher Treffer signalisiert weitere Ergebnisse. Die Quittierung bindet die prozesslokale SQLite-`total_changes`-Revision, **nicht** die Profilrevision.
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
- Skip/Undo/Reset/Replay nur im gültigen Deck-/Owner-Kontext. Skip-Undo ohne SQLite-Write; alte Schritte nach Merge nicht auf neue Owner übertragen. Reset/Replay widerrufen alte Callback-/Step-Generationen. Replay-Likes und globale Ausblendungen binden die sichtbare Settings-/Lifecycle-Generation, Deck und erwarteten Einstellungswert; der Menüwechsel widerruft vorherige Aktionen. Beide Replay-Einstiegspunkte verwenden dieselbe bestehende Mutation und lokale Reprojektion.
- Kontakt-, Medien-, Menü- und Vergleichsaktionen bleiben an die gerenderte Owner-/Session gebunden. Notizentwürfe nicht still umdeuten oder rebasen; Snapshotvergleich prüft das gültige Paar, alte Dialoge invalidieren.
- Kein unbegrenzter SaveableState pro Profil. Nach Merge aktuelles Listing möglichst erhalten, sonst sauberer Kontextwechsel; kein zusätzlicher Swipe-/Undo-Schritt.
- Layout und Navigation erzeugen weder implizites Gelesen-Ack noch einen fachlichen Write.

## Diagnoseinfrastruktur

### Logs und Zeitmessung

- Rust-Tracing je `LadiesCore`, **kein** global an erste DB gebundener Dispatch; Kermit über typisierte UniFFI-Schweregrade DEBUG/INFO/WARN/ERROR in dieselbe app-private `filesDir/diagnostics/ladies-debug.log`. Android-Fehler enthalten Ereignis, Exception-Typ/Kausalkette und begrenzte App-Stackpositionen, kontrollierte Fehlerkategorien statt externer Freitexte. Android-Ereignisse tragen ihren ursprünglichen Zeitstempel; DEBUG-Umschaltungen werden serialisiert. DEBUG ist opt-in; WARN/ERROR bleiben auch bei INFO aktiv. Die begrenzte Android-Queue reserviert Kapazität für kritische Ereignisse; verworfene Ereignisse und fehlgeschlagene Brückenaufrufe werden gezählt und beim Export gemeldet. Export wartet abseits der UI auf die Queue-Barriere; neue Ereignisse können nach dem Snapshot eintreffen.
- Synchronisierte Log-Senke circa 1,5 MB, Rotationsrest circa 700 KB. Monotonic `Instant` beziehungsweise `elapsedRealtimeNanos`.
- INFO enthält Gesamtdauern, DEBUG Teilphasen; Coil-EventListener erfasst Anfrageabschluss, Cachequelle, Decoderphase, Maße, Fehler und Cancel für alle Bildansichten. Profilvorbereitung protokolliert Listing/Snapshot, Rolle, Medienkennung, Budget und Ergebnis sowie aggregierte Publikations- und Wiederherstellungsereignisse bei INFO. Video-Cache und libmpv melden Cache-, Datei-, Init-, Surface- und Wiedergabeereignisse. Der libmpv-LogObserver ist an denselben Player-Lifecycle gebunden; native Warnungen/Fehler und bei DEBUG weitere native Meldungen werden auf kontrollierte Subsysteme und Fehlerkategorien reduziert. Coil erfasst auch bei Decode-Fehlern die Fetch-Datenquelle und den UI-Kontext (soweit gesetzt). Android und Rust korrelieren Medien per ersten 16 Hexzeichen von SHA-256 der URL; lokale Quellen haben eine getrennte Pfadkennung, keine Klartextpfade in neuen Medienereignissen. Native Medien-Cancellation wird anhand des tatsächlich zurückgegebenen Abbruchfehlers klassifiziert, nicht anhand eines nachträglich gesetzten Flags. Keine HTTP-Antwortbodies im allgemeinen Log. Geeignete vollständige Textantworten nur bei ausdrücklich aktivierter HTTP-Textaufzeichnung.

### HTTP-Aufzeichnung und Ausfallsicherheit

- Separate persistierte JSONL-Sitzung unter `filesDir/diagnostics/ladies-http.jsonl`. Textbody max. 2 MiB (bei Überschreitung vollständig weglassen), Sitzung max. 25 MiB; bei Limit oder Schreibfehler stoppen.
- Unvollständiges letztes Ereignis beim Neustart entfernen **und melden**. Beschädigtes mittleres Ereignis blockiert Export.
- Neustart deaktiviert Capture; Löschen entfernt Sitzung.
- Recorder in vorhandenen Rust-HTTP-Lesepfaden, ohne weitere Requests oder Kotlin-HTTP-Bridge.
- Quellenredirects separat ohne Body, Medienredirects verboten. Standortaufbereitung ohne HTTP; MapLibre und externe Intents außerhalb.
- Coil-Cachetreffer sind keine Requests; Medienbytes nicht im HAR. Statuschecks protokollieren Listing-ID, bestätigten Status und HTTP-Code.

### HAR, Maskierung und Export

- HAR 1.2 verwendet reale aggregierte Zeiten und `_diagnostics` für Auslassungen, Fehler und finale URL.
- Cookies/Auth-/Token-Header, sensitive Queryparameter, URL-Credentials sowie Fehler-URLs, Redirect/Referer maskieren. Auch maskierte Textantworten können sensibel sein.
- Export per SAF; FileProvider nur für unveränderliche app-private Cache-Snapshots, Original nicht zugänglich. Snapshots älter als 48 h beim nächsten Export löschen.
- Capture-Modi: [PRODUCT](PRODUCT.md#einstellungen-und-lokale-diagnose). Analyse: [README](../README.md#lokale-diagnose).

## Karte und Sicherheitsgrenzen

### Projektion, Karte und Lifecycle

- MapLibre verantwortet Karten-View und Ressourcen. AndroidView-Factory/`onRelease` und Lifecycle-Observer koordinieren **einmal** den nativen View-Lifecycle einschließlich Speicherdruck.
- Kamera einschließlich Zoom und Ausrichtung sowie Listing-/Owner-Auswahl bleiben im `SaveableStateHolder` über Tab-/Profilwechsel innerhalb des Runs erhalten. Ein neuer Run hat eigenen Kamerazustand; ohne gespeicherte Kamera die erste nichtleere abgeschlossene oder abgebrochene Projektion bei gemessener View-Größe einpassen; Teilbestände sofort darstellen, explizite Kamerainteraktionen haben Vorrang. Keine leere Startkamera persistieren.
- Rust liefert die Kartenprojektion für die exakt übergebenen Listing-IDs und optional den ausgeführten Run, ohne unabhängige Personendefinition; Kotlin prüft Mitgliedschaft, Owner und Favoriten. Eine Run-Stadt darf nur mit der PLZ eines übereinstimmenden aktuellen Snapshot-Orts kombiniert werden; widersprüchliche Ortsbelege bleiben getrennt. Versteckte Adressfelder sind kein Standortinput.
- Automatische Standortaufbereitung läuft unabhängig von der sichtbaren Entdecken-Ansicht; bereits vorbereitete Ortslagen werden vor der restlichen Aufbereitung publiziert. Neue reguläre Suchkarten und Profilbeobachtungen werden im jeweiligen SQLite-Commit vorbereitet; bestehende Mitgliedschaften werden bedarfsgesteuert unter zentraler Lease in Transaktionen von höchstens 64 Listings nachgepflegt. Cancel vor jedem Batch, vor jedem Write und vor Commit; abgeschlossene Batches bleiben bestehen. Kein neuer Quelllauf beim Moduswechsel.
- Kartenprojektion bleibt ein lokaler Read. `MapMembership` und Epoch binden asynchrone Ergebnisse an Run und exakte Mitgliedschaft; Scopewechsel widerruft laufende Aufbereitung und stale Resultate. `MainViewModel` drainiert die native Operation vor einer Folgeaufbereitung. `ON_STOP` cancelt; `ON_START` kann automatisch fortsetzen. Nur gerenderter Cancel bindet Kartenstatus und Surface-Generation; kein manueller Kartenbestandsrefresh oder Standortauflösungs-Callback. Erneute Profilbeobachtungen invalidieren auch bei gleicher Mitgliedschaft den lokalen Kartenread. Eine während des Drains vorgemerkte Aktualisierung bleibt bei explizitem Cancel als lokaler Read erhalten; derselbe abgebrochene Backfill startet dabei nicht erneut. Kartenstil-Retry bleibt getrennt von Datenreads. Bestätigte lokale Teilergebnisse und Kartenfehler getrennt behandeln.
- Kein eigener Prozess, WebView, Server oder Gerätestandort.

### Standortdaten und Herkunft

- Schema v3 ergänzt `listing_locations` mit Listing-FK, normalisiertem Stadt-/PLZ-Evidenzschlüssel, Koordinaten, Genauigkeit, Herkunft und Datensatzversion. Migration erhält vorhandene Tabellen und `app_settings`-Cache. Standortdaten werden nie einer Personenrepräsentation oder einem anderen Listing zugeordnet; nach Ortswechsel wird nur der passende neue Evidenzschlüssel projiziert. Gültige Standortbelege werden nicht erneut aufgelöst. Negativbelege gelten für die verwendete lokale Datensatzversion.
- Offline-Datensatz: GeoNames Deutschland, aus der fest versionierten [Mirror-Quelle](https://github.com/symerio/postal-codes-data/blob/3a94317a6192e7e576c078538dfe665048d42edf/data/geonames/DE.txt), kompakte TSV-Auswahl in `rust/ladies-core/data/de-postal.tsv`; Herkunft und Lizenz in [NOTICE](../NOTICE). Einmaliger lokaler Index im Rust-Core, zuerst belegter PLZ-Bereich, dann eindeutiger Stadtbereich. Koordinatenmittel sind ausdrücklich ungefähre Ortslagen; mehrdeutige Städtenamen und bekannte widersprüchliche PLZ-/Stadtpaare nicht als PLZ auflösen. Gültige Legacy-Koordinaten mit passender Genauigkeit aus `map:postal:DE:…` / `map:city:DE:…` übernehmen; alte Negativ-/Fehlerwerte verhindern keinen lokalen Treffer.
- Keine externen Geocoding-Aufrufe. Die bisherigen Zippopotam-/Nominatim-Anfragen mit Timeout, serieller Provider-Pause und HTTP-Fehlern entfallen; öffentliche [Nominatim-Policy](https://operations.osmfoundation.org/policies/nominatim/) rät von regelmäßigen App-/Bulk-Anfragen ab. Lokal fehlt damit die Möglichkeit konkreter Adressauflösung; keine privaten oder versteckten Adressen, kein künstlicher Präzisionsgewinn. Die Datenquelle ist geografische Referenz, kein Nachweis einer konkreten Profilposition.
- Kartenstil weiterhin `https://tiles.openfreemap.org/styles/liberty`. Eine GeoJSON-Source mit einem Punkt je aktuellem Listing erhält Listing-/Owner-ID, Originalkoordinaten, Genauigkeit und Selektion; MapLibre bildet native Cluster bis Zoom 15 und liefert deren exakten Count und Expansion-Zoom. Nicht geclusterte, exakt gleichliegende Listings haben einen stabilen Repräsentanten mit Gruppenanzahl und gröbster belegter Genauigkeit; alle Listingpunkte bleiben für die native Clusterzahl erhalten. Tap prüft aktuelle Listing-/Owner- und Ortsidentität sowie Gruppenanzahl gegen die Projektion. Keine stale Auswahl oder Verwendung freigegebener Views. Offline-Projektion über `mapOverview`, abbrechbare Aufbereitung über `prepareMapLocations`; Profilöffnung über `loadProfile` mit Offline-Fallback.
- Native Symbol-Layer verwenden lokale Zählsprites ohne Abhängigkeit von Karten-Glyphen. Sichtbare nicht geclusterte Einzelmarker können über den vorhandenen Coil-Loader kleine runde Thumbnails erhalten; bei nicht verfügbarer Referenz oder Decode bleibt der Fallback sichtbar. Ein lokaler Pfad aus der bestehenden Run-Mitgliedschaft wird nur bei identischer Listing-ID, Owner-ID und Thumbnail-URL bevorzugt; der vorhandene Medienmodell-Fallback bleibt erhalten. Maximal 64 gleichzeitig ausgewählte Style-Thumbnail-Sprites, höchstens vier parallele Decode-Anforderungen; Listing-/Owner-/URL-/Lokalpfad-Revisionsschlüssel, sichtbare native Layer, Camera-Idle/Style-Idle, Lifecycle und Cancel begrenzen Arbeit und Veröffentlichung. Nicht mehr benötigte Sprites und Aufträge freigeben; keine zusätzliche HTTP-, Archiv- oder Persistenzpipeline. Native Zählsprites bei Nichtverwendung zur Freigabe zulassen.
- Nur optionales MapLibre-Logo ausblenden. Kachel-/Stil-Attribution und GeoNames-Attribution bleiben sichtbar; ihr gemessener unterer Platzbedarf begrenzt den Dockbereich, ohne die native Karteninstanz zu wechseln.

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
- Karte: Listing-/Owner-Zuordnung, Favoriten, Offline-Daten, positive/negative Cache-Evidenz, PLZ-/Stadt-Fallback, Migration und Cancel/Lease beim Neustart.
- Architekturvertrag enthält keine Aufgabenstände oder Abnahmechronik.
