# Architektur

Die App ist eine eigenständige lokale Android-Anwendung mit einer APK. Kotlin/Compose (Material 3) verantwortet Darstellung, Navigation, Interaktion und Lebenszyklus; der Rust-Core ist die fachliche Autorität für Quellverarbeitung, Identität, Mutation und SQLite. Die typisierte Grenze bildet beim Gradle-Build generiertes UniFFI/JNA. Kein Server, WebView-Port, Python-/Flask-/Node-Runtime, Cloud-Backend oder zweiter fachlicher Datenstore.

## Verbindliche Grenzen

Architekturgrenzen (einschließlich ARC-01–08) sind die fachliche Datenhoheit des Rust-Core und seiner SQLite-Persistenz, die typisierte generierte UniFFI-Grenze, Kotlin/Compose für UI und Lifecycle, die unten definierte Netzwerk-, Medien- und Sicherheitsgrenze sowie die Trennung von fachlichem Commit, UI-Admission und tatsächlichem Operationsende. Keine zweite fachliche Queue, Datenbank, Profilimplementierung oder Quellen-HTTP-Schicht. Generierte Bindings nicht manuell ändern. Verbindliche Integritäts- und fachliche Verträge bleiben erhalten.

Die folgenden Komponenten, Klassen und Datenflüsse beschreiben den gegenwärtigen Stand und sind innerhalb dieser Grenzen refaktorierbar. Keine erzwungene ViewModel-/Gradle-Modularisierung ohne Bedarf. Umgang mit Änderungen: [AGENTS](../AGENTS.md#architektur--und-designvertrag). UI-/UX-Ziele: [DESIGN](DESIGN.md); fachliche Anforderungen: [REQUIREMENTS](REQUIREMENTS.md).

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
| Persistenz | `schema.rs` verwaltet Schema und Migration; `legacy_import.rs` importiert native Austauschdaten. |
| Diagnose | `diagnostics.rs`, `http_capture.rs`, `DiagnosticEventQueue`, CoreStore und Diagnoseprovider. |

Codepfade: [Android](../app/src/main/java/de/justify87/ladiesexplorer), [Rust](../rust/ladies-core/src). Herkunft und Abhängigkeiten: [PROVENANCE](PROVENANCE.md). UI/UX: [DESIGN](DESIGN.md); Anforderungen: [REQUIREMENTS](REQUIREMENTS.md). Aufgaben und Fortschritt: ausschließlich [ROADMAP](ROADMAP.md).

## Domäne und Persistenz

Person ist die kanonische Identität. Mehrere Quellprofile und Inserate können zu einer Person gehören. `(source, external_profile_id)` und `(source, external_listing_id)` sind eindeutig; Zuordnungen werden nicht stillschweigend geändert. Nutzerentscheidungen, Favoriten und Personennotizen gehören der Person, nicht dem Inserat oder Suchlauf. Quellsnapshots und Beobachtungen sind von Nutzerzustand getrennt.

| Bereich | Native Tabellen |
| --- | --- |
| Identität/Nutzerzustand | `persons`, `person_user_state`, `source_profiles`, `listings`, `person_identifiers` |
| Inhalt/Historie | `listing_snapshots`, `snapshot_values`, `listing_observations`, `change_events` |
| Medien | `media_assets`, `media_sources`, `snapshot_media` |
| Suche | `search_runs`, `search_results` |
| Gespeicherte Suchen | `saved_searches`, `saved_search_results` |
| Identitätsevidenz | `identity_evidence`, `identity_events` |
| Weitere importierbare Fachdatensätze | `encounters`, `encounter_services`, `price_lists`, `price_items` – Schema/Import, keine daraus ableitbare vollständige Bearbeitungsoberfläche |
| Einstellungen | `app_settings` |

### SQLite-Vertrag

- App-private Datenbank `filesDir/ladies.sqlite`, `user_version = 2`, `application_id = 0x4C445850` (LDXP). Keine binäre Kopie der SQLAlchemy-Legacy-Datenbank. Import über das definierte native Austauschformat ([DATA-IMPORT](DATA-IMPORT.md)).
- Schema-Installation und Migration erfolgen vor Freigabe des Stores transaktional. Native v1 muss dem kanonischen Schema entsprechen; v2 wird einschließlich Tabellen, Indizes und Triggern validiert. Fremde IDs, unerwartete unversionierte Tabellen, Schemaabweichungen und unbekannte neuere Versionen liefern typisierte Fehler. Die exakt bekannte technische Demo mit `scaffold_notes` ist ein unterstützter Migrationsfall; echte Nutzerdaten werden nicht implizit gelöscht.
- `foreign_keys=ON` je Verbindung; Rust-Mutex schützt die Connection. Öffentliche Mutationen verwenden parametrisierte SQL-Anweisungen und Transaktionen. Person-INSERT erzeugt den Nutzerstatus per Trigger; Trigger verhindern unzulässige Personen-/Quellenzuordnung von Inseraten und Quellprofilen. Sichtungen aktualisieren die zugehörigen last_seen-Zeitpunkte entsprechend ihrer Quelle.
- `PersonCursor` paginiert über `last_seen_at` und `person_id`; `DiscoveryCursor` über Favorit, Inserat-`last_seen_at` und `listing_id`, jeweils absteigend und mit Seitengrößen 1–100. Such-, Saved-Search- und Saved-People-Fenster besitzen eigene fachliche Verträge. UI besitzt keine SQL- oder alternative Persistenzlogik.

### Historisierung und Identitätsoperation

Profilabrufe speichern Snapshot, PROFILE-Beobachtung und tatsächliche Feld-/Medienänderungen transaktional. Fingerprints berücksichtigen die Kategorie; identische Inhalte erzeugen Beobachtungen, keine falschen Änderungen. Rückkehr zu früherem Inhalt aktiviert dessen Snapshot und erzeugt den tatsächlichen Übergang vom bis dahin aktiven Snapshot.

Telefonnummer (normalisiert), Quellprofil-ID und 64-bit-dHash liefern Kandidatenevidenz. Bildhash-Abstand bis 8 kann Evidenz sein; unabhängige Bildpaare werden eindeutig gepaart, Gleichstand erzeugt keinen bevorzugten Kandidaten. Bereits fremd zugeordnete Quellprofil-IDs bleiben erhalten; der Konflikt wird dedupliziert als Evidenz erfasst. Keine automatische Zusammenführung.

Der gemeinsame explizite Identitätsvorgang bindet Listing, erwarteten Source-Owner, gerenderten Kontext und Kandidat. Rust validiert Besitzer und aktuelle Evidenz innerhalb der Transaktion. Fehlende Evidenz, manuell getrennte Inserate und ungeklärte widersprüchliche Präferenzen verhindern den Merge; betroffene ungespeicherte Notizen blockieren ihn ebenfalls. Nutzerzustand, Inserate, Quellprofile, Kennungen, Begegnungen und Historie bleiben erhalten. Historische Personen-IDs in `identity_events` bleiben nach einem Merge erhalten. Nach erfolgreichem Commit werden betroffene lokale Projektionen, Entwürfe, Dialoge und Swipe-Slots invalidiert bzw. neu gelesen. Keine Wiederholung der Mutation bei einem Readfehler.

## Quellzugriff und Medien

### Netzwerkgrenze

Alle app-internen HTTP(S)-Anfragen an ladies.de, APIs, Subdomains und zugehörige Medien-CDNs laufen ausschließlich über Rust. Kotlin hat keinen eigenen Quellen-HTTP-Client. MapLibre lädt eigene Kartenressourcen; bewusst ausgelöste externe Intents sind keine app-internen Quellanfragen.

[Quellenvertrag](SOURCE.md): Rust validiert Filter und URL-Ziele, paginiert und extrahiert Such-/Profildaten, behandelt Radiusnachweis, Teilresultate, Fehler und gespeicherte Deltas. Keine automatische Radiusaufweitung oder fiktive Klassifikation. Abbruchsteuerung bleibt beim RequestControl; ein gestarteter blockierender Request kann bis zum Timeout laufen.

### Persistentes Archiv und Anzeige

App-private Bilder liegen unter `ladies_data/media/images/`. SHA-256 dedupliziert Dateien; JPEG/PNG/WebP/GIF-Dimensionen und Pixelbudget werden vor vollständiger Dekodierung geprüft. Der 64-bit-dHash verwendet Legacy-kompatiblen Links-/Rechtsvergleich mit LANCZOS. Archivierung ist ein separater Vorgang nach dem Snapshot; Fehler/Cancel löschen keine gespeicherten Profildaten oder bereits archivierten Dateien. Medienrevision und lokale Existenz steuern Wiederverwendung. Videoreferenzen werden standardmäßig ohne permanentes Videoarchiv gespeichert; die eingebettete Wiedergabe nutzt eine vorhandene Archivdatei oder eine temporäre lokale Anzeigedatei. Ein opt-in-Archivierungspfad existiert im Rust-API.

Für Medien validiert Rust bekannte HTTPS-Hosts und URL-Formen, verbietet Redirects und begrenzt Ressourcen: Bilder 12 MiB, Videos 48 MiB, 24 Mio. Bildpixel, 80 Referenzen je Archivaufruf, 15 s Request- und 150 s Archivgesamtbudget. Details: [media.rs](../rust/ladies-core/src/media.rs).

- **Bilder:** Coil 3 nutzt lokale Archivdateien zuerst, dann Remote-Fallback über `coil-network-core → RustImageNetworkClient → CoreStore.loadDisplayImage → UniFFI → Rust`. Vier gleichzeitige blockierende Bildanzeigeabrufe sind über den IO-Dispatcher begrenzt. Coil besitzt URL-/Revisionsschlüssel, Memory-/Disk-Cache, Dekodierung und Zusammenführung identischer Cache-Anforderungen; UniFFI überträgt begrenzte kodierte Bytes. Fehler führen zu sichtbaren Platzhaltern. Reine Anzeige archiviert nicht und schreibt nicht in SQLite.
- **Videos:** `GalleryVideo` bindet Player, Medium und lokale Datei an den sichtbaren Galerieeintrag und RESUMED-Lifecycle. Archivdatei zuerst, sonst `CoreStore.loadDisplayVideo → UniFFI → media::display_video` mit Rust-Host-, Größen-, Timeout-, HAR- und Cancel-Vertrag. Eigener IO-Dispatcher mit Parallelität 1. Validierte MP4/WebM-Datei im App-Cache; kein neuer Archiv-/DB-Eintrag. libmpv erhält ausschließlich einen lokalen Pfad, erzwungener lavf-Container und `protocol_whitelist=file` als lavf-Option; `access-references=no`, `config=no`, `load-scripts=no`, `ytdl=no` und `autoload-files=no` sperren externe Referenzen, Konfiguration, Skripte, URL-Helfer und automatisch geladene Begleitdateien. Immer stumm, Autoplay/Loop bei sichtbarem Medium, mittiges Play/Pause; Hardwaredecoder soweit verfügbar. Player stoppt außerhalb der gültigen sichtbaren Projektion bzw. bei Lifecycle-/Busy-Wechsel. Surface-Freigabe nach nativer Zerstörung; erneuter Surface-Owner nur bei weiterhin gültigem Medium. Temp-Dateien werden auch bei Cancel bereinigt; verwaiste Dateien beim ersten Zugriff nach Prozessneustart entfernt.

## Oberfläche und Navigation

`ShellRoute` und `LadiesApp` besitzen die vier Root-Ziele Entdecken, Suche, Gespeichert und Einstellungen. Entdecken hat die lokalen Modi Swipe, Grid und Karte; ein Moduswechsel startet keine Suche. Standalone-Profile überlagern die Root-Navigation und kehren zum tatsächlichen Ursprung zurück. Status-/Import-Operationen und Back werden vom zuständigen Owner koordiniert. Beim Import ist Root-/Back-Navigation gesperrt; reine Status-Reads sperren sie nicht.

### Suche, Entdecken und Grid

`MainViewModel` hält Suchentwurf und ausgeführten Run getrennt. Der ausgeführte Run bleibt an seinen Filter-Snapshot gebunden. Rust liefert `activeSearchCards`; Grid liest diese aktive Ergebnismenge und nicht die bereits konsumierte Swipe-Queue. Ohne aktiven Run verwenden Swipe und Grid den sichtbar gekennzeichneten neutralen lokalen Bestand (Präferenz NONE). Im aktiven Run entscheidet das native Filterergebnis zusammen mit der sessionlokalen Traversierung und Replay-Likes-Policy über zulässige Swipe-Cards. Replay-Likes ist eine unabhängige Android-Einstellung; Favorit bleibt von LIKE/DISLIKE unabhängig.

`SwipeSession` besitzt Traversierung und Undo-Records, keinen zweiten fachlichen Store. Skip ist rein sessionlokal; Reset startet Traversierung neu, ohne gespeicherte Präferenzen zurückzusetzen. Geste und Rail verwenden dieselbe gerenderte Admission und native CAS-Präferenzoperation. Erst bestätigte Mutation/autoritative Queue-Projektion erlaubt den nächsten Kartenwechsel. Ein Fehler nach Commit wiederholt nur Reads. Standalone-Präferenz, Neutral-Rücksetzung und Favorit erzeugen keinen Deck-Visit. Grid-Herz führt erwartungsgeprüfte LIKE/NONE- bzw. LIKE-Mutation aus; rungebundenes Präsentations-Hide bei neuem LIKE hat Rollback bei Writefehler und Reset bei neuem Run. Es ändert nicht die fachliche native Ergebnismenge und verwendet keine Swipe-Historie.

`DiscoveryProjectionPublisher` hält lokale Entdecken-/Grid- und Saved-People-Projektionen aktuell; getrennte Fehlerpfade, gemeinsame Gültigkeitsprüfung. Präsenz- und Altersinformationen kommen aus vorhandener eindeutiger Quell-/Snapshot-Evidenz, ohne dekorative Profilanfragen. Karte verwendet den eigenen DB-weiten Datenvertrag ([MAP](MAP.md)).

### Gemeinsame Profilarchitektur

`NativeProfileProjection` liest Profil, kanonische Identität, Person, Medien, Historie und Kandidaten unter demselben SQLite-Guard. `ProfileReadModel` bindet diese Projektion, Herkunft/Fehler und lokale Revision. Swipe und Standalone teilen den lokalen Lese- und Präsentationsvertrag, behalten jedoch unterschiedliche Quell-/Archiv-Lebenszyklen:

- Standalone: `StandaloneProfileOperations` liest aktuelle Quelldaten, nutzt bei Fehler den aktiven lokalen Snapshot samt Medien und zeigt die Herkunft/Warnung. Erfolgreiche Profilabrufe können den separaten Archivvorgang auslösen; bei Offline-Fallback kein erneutes Archivieren aus dem Netz.
- Swipe: maximal aktuelles plus zwei folgende Profile, ohne zusätzliche Archivierung. Quellenabruf und lokaler Fallback folgen dem Current-/Prefetch-Vertrag unten.

`ProfileShell` und `ProfileFlow` bilden die gemeinsame Profiloberfläche; Swipe und Standalone behalten ihre jeweiligen Navigations-/Operationskontexte. Sichtbare Gestaltungs- und Informationsanforderungen: [DESIGN](DESIGN.md#profile-und-medien).

`ProfileHeroLayout` berechnet aus tatsächlichen Constraints, Insets und Leisten das adaptive Hero-Budget. Die Header-Identität wird anhand realer Lazy-Geometrie übergeben; die Decision Rail besitzt einen Menü-Fallback. Status und Busy sollen den Viewport nicht unkontrolliert verschieben. Galerie- und Hero-Ziele stehen in [DESIGN](DESIGN.md#profile-und-medien).

`ProfilePanels` und `ProfilePanelReads` (in `ProfilePanelState.kt`) sowie `ProfileFlowRows` steuern sichtbarkeits-/bedarfsgebundene lokale Reads. Facts und PersonDetail werden je gültigem Listing/Owner/Snapshot/Filter/Revision wiederverwendet; Analyse/Historie und Vergleich erzeugen keine zusätzlichen Quellenabrufe durch die Darstellung. Snapshotvergleich prüft Besitzer und Inseratzuordnung. Notiz-Reads/Retry haben einen gemeinsamen projektionsgebundenen Job; Entwürfe und Tickets sind ownergebunden im ViewModel und bleiben bei Navigation/Rotation erhalten (nicht über Prozessende). Speichern ist transaktionaler Vergleich von aktuellem Besitzer und erwartetem Text, schreibt nur private_note und updated_at. Konflikte explizit auflösen; Merge/Import schützen betroffene ungespeicherte Entwürfe.

### Gesten, Präsentation und Undo

`PhotoGestureResolver` klassifiziert den gemeinsamen Hero-Pointer-Stream nach gemessenen Grenzen und dominanter Achse. Native Clickables besitzen seitliche Galerie-Taps, Fokus und Semantik; interaktive Kindziele haben Vorrang. Horizontaler Lock wird übernommen, vertikales Scrollen der LazyColumn überlassen. Mehrfinger, Fokus-/Geometrie-/Kontextwechsel, Pause, Back, Cancel oder widerrufene Touch-Intention verhindern den Commit. `ProfileTouchGate` widerruft den gesamten Touch-Stream bis zum letzten Up; Semantik-/Tastaturaktionen bleiben nativ. Standalone nutzt Galerie-Taps ohne Swipe-Entscheidung.

`SwipeFlight` ist nur ein inert gerenderter visueller Übergang, keine optimistische Queue oder fachliche Mutation. Drag bleibt bis Admission/autoritativer Antwort sichtbar; ausgehendes Bild animiert im stabilen Viewport, neuer Profilfluss behält seine Geometrie. Kurzes begrenztes Warten auf Dekodierung und ehrlicher Cache-Fallback; keine künstlichen Snapshots für ungeeignete Medien oder Detailscrollzustände. Während des Flight sind entsprechende Eingaben kurz gesperrt, die fachliche Admission bleibt maßgeblich.

`ProfilePresentation` hält Medien-ID/Index, stabile Row-Keys/Offset und Sektionen getrennt vom fachlichen Read-Model. `SwipeSession` speichert höchstens 16 jüngere Präsentationsreferenzen zu Undo-fähigen Visits, gebunden an Run/Listing/Owner/Snapshot/Filter. Ältere oder unpassende Referenzen fallen kontrolliert zurück. Ein Undo-Receipt wird einmalig nach gültiger Anwendung oder eindeutig nicht passendem Kontext konsumiert; bei Readfehler bleibt es erhalten. Leeres/abweichendes autoritatives Deck verwirft unpassendes Receipt; Rail bleibt bei leerem Deck für gültiges Undo verfügbar.

`ProfileRestoreCoordinator` koordiniert den Wiederherstellungsauftrag, nicht die tatsächlichen Medien-/Scroll-/Sektionsowner. Row-Manifest dient als stabile Schlüsselgrundlage und Datenbedarfsquelle; bei Änderung von Snapshot/Filter wird der Schlüssel geprüft und Pixeloffset auf sicheren Zeilenanfang bzw. gültige Größe begrenzt. Medien-ID hat Vorrang vor begrenztem Index. Kein zusätzlicher Quellenabruf oder dauerhafter UI-Profilcache für Wiederherstellung. Reset hat eine eigenständige Präsentationsgeneration.

### Swipe-Read-Window und Prefetch

`DiscoveryScreen`, `SwipeProfileWindow` und `SwipeProfileReads` halten maximal Current+2 Slots, jeweils an Listing, erwarteten Owner und Ticket/Revision gebunden. Jeder Slot besitzt einen stabilen Effect und RequestControl. Ein Mutex serialisiert Profilreads; Promotion eines laufenden gültigen Prefetch erhält dessen Auftrag statt abzubrechen und neu zu starten. Busy erzeugt keinen neuen Slot. Pruning, Lifecycle-/Route-/Owner-/Revisionswiderruf entziehen alte Tickets. Verspätete native Commits aktualisieren betroffene lokale Projektionen unabhängig von UI-Annahme.

Lokaler archivierter Bestand liest lokal zuerst. Aktuelle erstmalige Freshness und spekulative Cache-first-Reads sind getrennte Policies. Fehler spekulativer Reads warten auf Promotion; lokaler Reprojektionsfehler löst keinen zweiten Source-Read aus. Coil-Warming ist pro Slot, Revision und tatsächlicher Hero-Pixelgröße begrenzt, mit Crop-Skalierung. Kein zweiter Mediencache, keine automatische Archivierung.

## Gespeicherte Personen und Suchen

### Personen und Status

Rust `saved_people_window` projiziert Favoriten, Likes und Dislikes anhand stabiler Personen-IDs. Recent (36 h) und Regular mit Counts entstehen für Favoriten/Datum unter demselben SQLite-Guard und derselben Zeitgrenze. Bei Nachladen wird ein kohärentes erweitertes Fenster neu gelesen. Kotlin hält nur Kapazität, Auswahl und Scrollzustand. Keyset-Cursor bindet Auswahl, Zeitgrenze und SQLite-total_changes-Revision. Namenssortierung mit NFD, Case-/Mark-Folding und ß/ss; keine vollständige CLDR-Kollation. Metadaten fallen deterministisch auf vorhandene Daten zurück. Relisting behält den fachlichen Ereigniszeitpunkt.

Suche, Profil und explizite Einzel-/Batchstatuschecks nutzen denselben Rust-Statusübergang. Status-only schreibt Statuszeit, Status und Beobachtung, bei bestätigtem Wechsel Ereignis; keine Inhalts-/Personenreihenfolge oder Medieningestion. Vor Commit werden Owner, URL, Advert-ID und Beobachtungsrevision validiert. Unbestätigte Antworten/Ausfälle überschreiben keinen bestätigten Status. `SavedPeopleState` hält Fenster und sequenziellen Statusablauf; Cancel bei Navigation/ON_STOP, bereits gespeicherte Teilresultate bleiben und werden lokal projiziert.

### Gespeicherte Suchen

`SavedSearchController` besitzt Liste, Template-Session, Editor, Dialoge, Ergebnisfenster und Operationen. Regulärer Suchentwurf und gespeicherte Definition nutzen denselben typisierten `SearchDraft` und Filtereditor, JSON nur an Core-Schnittstellen. Rust besitzt validierte Definition, Mitgliedschaft des letzten vollständigen Laufs, Delta/Membership-Historie und revisionierte Rename-/Ack-Operation. Unveränderte Definitionen bleiben byte-identisch. Lokale Profilmetadaten werden mit dem Ergebnisfenster kohärent projiziert. Keyset-Cursor bindet Such-ID und prozesslokale SQLite-total_changes-Revision (keine Profilrevision).

Sichtbare Fenster werden nach relevanten nativen Abschlüssen lokal aktualisiert, unsichtbare als dirty markiert; Änderungen während eines Reads werden zusammengefasst. List-/Window-/Actionfehler sind unabhängig; Retry liest die bestehende Fenstertiefe, keine fremden stale Aktionen. Öffnen, Reload, Pagination und Rückkehr aus Hintergrund quittieren nichts. Nur ausdrückliches „Gelesen“ bzw. „Als gelesen markieren“ mit gerenderter Revision ändert den Unread-Status. UI-Admission und Rust-CAS verhindern Ack eines neueren Fensters; Fehler erfordern neuen Read, niemals automatischen Write-Retry. Bereits zugelassene Ack-Operation kann nach ON_STOP drainieren.

## Operations- und Konsistenzmodell

Datenhoheit, Render-/Admission-Gültigkeit und tatsächliches Ende einer nativen Operation sind getrennte Verantwortlichkeiten.

- `RequestControl` wird pro nativer Operation verwendet. Cancel signalisiert Rust, invalidiert überholte UI-Publikation und verhindert weitere Arbeit, soweit kooperativ möglich. Vor Suchpersistenz und gespeicherten Deltas prüft Rust Cancel auch vor dem SQLite-Commit. Der laufende blockierende Transport kann bis Timeout dauern.
- `DrainingOperation` hält den nativen Owner bis zum tatsächlichen Abschluss, unabhängig von Navigation oder verworfener UI-Antwort. Suche bleibt bis Drain loading; konkurrierende Suche/Import wird gesperrt. Home-Status und Import teilen exklusiven Owner; Statusrefresh wird koalesziert, verspätete Statusantwort verdeckt keinen Importfehler.
- Rendered Tokens binden Kontext/Run-/Decksession oder Standalone-Session, Listing, erwarteten Owner, Snapshot-/Projektionsrevision und fachliche erwartete Werte. UI-Callbacks werden zur Admission gegen den aktuellen Zustand geprüft. Rust ist Commit-Autorität; erwartete Besitzer/Präferenz/Favorit/Notiz/Evidenz werden nativ geprüft. Kein Check-then-write-Ersatz in UI.
- Ein erfolgreich gespeicherter Write wird genau einmal als solcher behandelt. Writefehler und nachfolgender Readfehler unterscheiden sich; Retry nach Commit ist eine lokale Reprojektion. Kein stilles Wiederholen fachlicher Mutationen oder Quellenabruf allein zum Wiederherstellen der UI.
- Reprojektion betrifft die tatsächlich abhängigen lokalen Fenster (Profil/Notiz, Entdecken/Grid, SavedPeople, SavedSearch, Map, Home). Unabhängige Reads behalten ihre eigenen Erfolgs-/Fehlerzustände; sichtbare gültige Resultate werden publiziert, verdeckte Fenster nach Bedarf dirty. Alte Ergebnisse dürfen keine Aktionen für einen anderen Owner freigeben. Reine erfolgreiche Swipe-Entscheidung invalidiert nicht pauschal andere Personenprofile; tatsächliche Quell-/Identitätsänderungen oder Fehler widerrufen betroffene Slots.
- Archivierung und bestätigter Merge koordinieren Cancel/Drain und aktuellen Besitzer; native Transaktionen sichern die Integrität. Kein implizites Gelesen-Ack, kein fachlicher Write durch Layout oder Navigation.

Ergänzende Operationsinvarianten: Skip/Undo/Reset/Replay bleiben an den gültigen Deck-/Owner-Kontext gebunden; Skip-Undo schreibt nicht in SQLite, alte Steps werden nach Merge nicht auf neue Owner übertragen. Reset/Replay widerrufen alte Callback-/Step-Generationen. Gerenderte Kontakt-, Medien-, Menü- und Vergleichsaktionen dürfen nicht auf einen anderen Owner/Session übergehen. Notizentwürfe werden nicht still auf andere Owner umgedeutet oder rebased. Snapshotvergleich ist explizit und prüft das gültige Snapshot-Paar; alte Dialoge werden invalidiert. Es gibt keinen unbeschränkten SaveableState pro Profil. Bei Merge aktuelles Listing soweit zulässig erhalten, andernfalls sauberer Kontextwechsel; keine zusätzliche Swipe- oder Undo-Aktion.

## Diagnose, Karte und Sicherheitsgrenzen

- Einstellungen bedienen Import, DB-/Core-Status, Replay-Likes, Debug-Logging und Diagnoseexport. Rust zeichnet seine Operationen und optional begrenzte HTTP-Evidenz auf; Kotlin liefert eigene Diagnoseereignisse über den Core. TXT/HAR-Export über app-private Cache-Dateien und den Diagnoseprovider. Einzelheiten und Grenzen: [DIAGNOSTICS](DIAGNOSTICS.md).
- MapLibre besitzt Karten-View und Kartenressourcen; AndroidView-Factory/onRelease und Lifecycle-Observer koordinieren genau einmal den nativen View-Lifecycle einschließlich Speicherdruck. Kamera und Auswahl bleiben über SaveableStateHolder bei Tab-/Profilwechseln erhalten. Rust liefert die DB-weite Kartenprojektion und hält Koordinatencache in app_settings, zentrale Geocoding-Lease und Nominatim-Pause über Läufe hinweg. Offline-Überblick und Auftragsstatus sind getrennt; nach Abschluss/Abbruch liest die UI genau einmal den aktuellen Bestand statt doppelter Listenübertragung. Kein eigener Prozess, WebView, Server oder Gerätestandort. Provider-, Cache- und Bedienvertrag: [MAP](MAP.md).
- Private Datenbank, Medien, Exporte und temporäre Player-Dateien liegen im App-Sandboxbereich. Android-Backup ist deaktiviert. Manifest entfernt von SDKs deklarierte COARSE/FINE_LOCATION-Berechtigungen. Keine automatischen Kontakte, Messenger, Synchronisierung oder KI-/OCR-Preisextraktion. Externe Aktionen sind explizite Intents. Lizenzinformationen für native Bibliotheken stehen in [PROVENANCE](PROVENANCE.md) und [NOTICE](../NOTICE).

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

Die Prüfungen sind konservative Regressionen, keine vollständige Kotlin-Typ-/Datenflussanalyse, Sicherheits-Sandbox oder Aussage über beliebige private Inhalte und SDK-Laufzeitverhalten. APK-Regeln beziehen sich auf tatsächlich gebautes ARM64-Debug-APK. Build-/Testkommandos und Ausführung: [README](../README.md#prüfung--ci). Fachliche Unit- und Regressionstests liegen in Rust, Android JVM und den vorhandenen nativen Instrumentierungstests. Der Architekturvertrag enthält keine Aufgabenstände oder Abnahmechronik.
