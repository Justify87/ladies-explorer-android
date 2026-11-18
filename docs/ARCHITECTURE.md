# Architektur und native Datenflüsse

Die App ist ein lokales Android-Hobbyprojekt mit einer APK. Kotlin/Compose (Material 3) besitzt Darstellung, Navigation und Lebenszyklus; der eigene Rust-Core besitzt die fachliche Logik und SQLite-Persistenz. UniFFI/JNA stellt die typisierte Grenze dar. JNI-Bindings werden beim Gradle-Build erzeugt. Kein Python, Flask, Node.js, eigener HTTP-Server oder Cloud-Backend; die Herkunft der technischen Basis steht in [PROVENANCE.md](PROVENANCE.md).

## Datenmodell und Zuständigkeiten

- Person ist die kanonische Nutzeridentität. Mehrere Inserate und Quellprofile können zu einer Person gehören. Die Schlüssel `(source, external_profile_id)` und `(source, external_listing_id)` sind eindeutig und dürfen nicht stillschweigend einer anderen Person zugeordnet werden.
- PersonUserState hält Entscheidungen (LIKE/DISLIKE), Favoriten und Notizen auf Personenebene. Notizen, Begegnungen und manuelle Preislisten sind im Schema/Import vorhanden; daraus folgt keine vollständige Bearbeitungsoberfläche ([ROADMAP.md](ROADMAP.md)).
- Snapshots, Feldwerte, Medienbezüge und Änderungsereignisse historisieren Quellprofile. Identitätsevidenz allein löst keine automatische Zusammenführung aus.
- Rust-Module: `schema.rs` (Versionen und Konsistenz), `legacy_import.rs` (Import), `source/` (Suche/Extraktion), `history.rs`, `identity.rs`, `media.rs` (Historie/Identität/Archiv), `discovery.rs`, `saved_search.rs` (native Feeds/gespeicherte Suchen), `map.rs` und Diagnose-Module. Kotlin nutzt die Bindings über CoreStore und MainViewModel; Screens halten Darstellungszustand und delegieren fachliche Operationen.
- Quellverhalten: [SOURCE.md](SOURCE.md); Kartenvertrag: [MAP.md](MAP.md); Diagnose: [DIAGNOSTICS.md](DIAGNOSTICS.md).

## SQLite-Schema und Legacy-Zuordnung

| Former model | New SQLite table | Status |
| --- | --- | --- |
| Person | persons | Created, typed API |
| PersonUserState | person_user_state | Created, typed preference/favorite API |
| SourceProfile | source_profiles | Created, typed API with conflict checks |
| Listing | listings | Created, typed API with conflict checks |
| PersonIdentifier | person_identifiers | Created |
| ListingSnapshot / SnapshotValue | listing_snapshots / snapshot_values | Created |
| MediaAsset / MediaSource / SnapshotMedia | media_assets / media_sources / snapshot_media | Created |
| SearchRun / SearchResult / ListingObservation | search_runs / search_results / listing_observations | Created |
| SavedSearchRecord | saved_searches | String IDs, definition versions, notification/delta state |
| SavedSearchResultRecord | saved_search_results | Historical results, visibility and first/last seen |
| ChangeEvent | change_events | Created |
| IdentityEvidence / IdentityEvent | identity_evidence / identity_events | Created |
| Encounter / EncounterService | encounters / encounter_services | Created |
| PriceList / PriceItem | price_lists / price_items | Schema/import for manual entries only |
| AppSetting | app_settings | Created |
| Messaging / Baileys / chat threads | — | Explicitly excluded |
| Automated AI/OCR price extraction | — | Explicitly excluded |
| VisualAnalysis | — | Not part of the current core |

The Android schema is intentionally not a binary copy of the legacy database.
The legacy SQLAlchemy DB must **not** be opened as the Android SQLite store.
The implemented exporter/importer transforms legacy data into the new tables; see [DATA-IMPORT.md](DATA-IMPORT.md).

## Migration und Konsistenz

- The native database uses `PRAGMA user_version = 2` and the distinct
  `PRAGMA application_id = 0x4C445850` (LDXP). The legacy web-app database
  is a separate format; its version 1 must never be opened as native SQLite.
- Migration runs transactionally on the **Rust side** before exposing the store.
- Fresh databases install the domain schema and migrate historical identity
  references to v2 in one transaction. Existing native v1 databases must
  match their canonical schema before the v1→v2 migration; every subsequent
  opening validates the canonical v2 tables, indexes and triggers.
- The obsolete `scaffold_notes` technical-demo table is dropped within the
  migration transaction; real people or history are never implicitly deleted.
- Unknown unversioned tables, foreign application IDs, noncanonical schemas and
  future versions cause typed errors. Only a fresh database, the exact
  technical `scaffold_notes` demo, or valid native v1 can be migrated.
- `PRAGMA foreign_keys=ON` is enabled for every new connection.
- The database is stored privately in `filesDir/ladies.sqlite`.
- Public write operations use SQLite parameters and transactions.
- A person INSERT creates its user-state row via a database trigger, regardless
  of whether Kotlin/UniFFI or the importer inserted it.
- SQLite triggers forbid linking a listing to a source profile belonging to a
  different person or source, including direct INSERT/UPDATE from an importer.
- Seeing a source profile or listing updates the canonical person's last-seen
  timestamp; listing events also update the attached source profile.
- The people feed exposes keyset pagination (`last_seen_at`, `person_id`) with
  explicit 1–100 page-size validation and deterministic tie-breaking.
- The SQLite connection is shared behind a Rust Mutex; Kotlin invokes the
  generated bindings from `Dispatchers.IO`.

## Profilhistorie und Identität

- Ein erfolgreicher Profilabruf speichert Snapshot, PROFILE-Beobachtung und mögliche Feld-/Medienänderungen transaktional. Inhalts-Fingerprints inkludieren die Kategorie; wiederholte identische Abrufe erzeugen Beobachtungen, aber keine falschen Änderungen. Die aktive Ansicht wird auch bei Rückkehr zu einem früheren Fingerprint aktualisiert und erzeugt dann echte Übergangsereignisse. Verglichen wird der aktive, nicht der zuletzt angelegte Snapshot.
- Normalisierte Telefonnummer, Quellprofil-ID und perceptual image hash liefern Identitätskandidaten. Ein Bildpaar mit dHash-Hamming-Distanz bis 8 kann Evidenz sein. Mehrere unabhängige Bildpaare werden maximal eindeutig gepaart, nicht mehrfach gezählt; bei gleichwertigen Kandidaten gibt es keinen bevorzugten Treffer. Keine automatische Zusammenführung.
- Eine Quellprofil-ID, die bereits einer anderen Person zugeordnet ist, bleibt dort; der neue Snapshot wird gespeichert und der Konflikt dedupliziert als Evidenz erfasst.
- Die UI erfordert explizite Bestätigung eines Kandidaten. Der Merge ist transaktional; er erhält Nutzerpräferenzen, Favoriten, Notizen, Inserate, Quellprofile, Kennungen, Begegnungen und Historie. Ohne Evidenz oder bei manuell getrennten Inseraten wird der Merge verweigert. Widersprüchliche Entscheidungen müssen vorher geklärt werden. Schema v2 bewahrt historische Personen-IDs in identity_events auch nach einem Merge; bereits nullifizierte IDs werden soweit vorhanden aus Ereignis-JSON wiederhergestellt.

## Medien

- App-private Bilder unter `ladies_data/media/images/`, SHA-256-Deduplizierung; JPEG/PNG/WebP/GIF-Dimensionen, 64-bit-dHash (Legacy-kompatibler Links-/Rechtsvergleich mit LANCZOS). Dimensionen und Pixelbudget werden vor vollständiger Dekodierung begrenzt.
- Medienarchivierung findet nach dem Snapshot separat statt. Fehler und Abbruch löschen keine Profildaten oder schon gespeicherten Dateien. Rückgabe: heruntergeladen, wiederverwendet, fehlgeschlagen, zurückgestellt. Bereits archivierte Medien werden ohne erneutes Laden wiederverwendet.
- Videos bleiben standardmäßig Remote-Referenzen; opt-in-Archivierung existiert im Rust-API, nicht automatisch als vollständige UI-Funktion. Medien-URLs folgen dem gemeinsamen Parser-/Downloader-Vertrag; nur bekannte HTTPS-Medienhosts, keine Redirects. Grenzen und Anforderungs-/Gesamtzeitbudgets stehen in [media.rs](../rust/ladies-core/src/media.rs) (derzeit 12 MiB Bild, 48 MiB Video, 80 Referenzen je Aufruf).
- Coil 3 lädt Vorschaubilder und Galerie lifecycle-gebunden, bevorzugt lokale Archivdateien und nutzt Remote-URLs als Fallback. Videos und WhatsApp werden extern geöffnet; kein eingebauter Messenger oder Videoplayer.

## Native Oberfläche

- Entdecken: ein aktuelles Inserat pro Person, LIKE/DISLIKE per Swipe oder Buttons, „Später“ nur für die aktuelle Sitzung; Dislikes werden nicht erneut angeboten. Favoriten sind unabhängig von der Swipe-Präferenz. Beide Feeds paginieren getrennt per stabilen SQLite-Cursorn; übersprungene Personen werden vor dem Paging ausgeschlossen.
- Suche: Ort, Radius, Rubrik, Alter, Größe, Seitenlimit, Rust-Filterkatalog; LazyColumn und sichtbarer Hinweis für unvollständige Suche. Gespeicherte Suchen: siehe [SOURCE.md](SOURCE.md).
- Profil: Galerie, Merkmale, Services, Zeiten, Telefon, externer WhatsApp-Aufruf, Verlauf, explizite Identitätsbestätigung. Zunächst aktuelle Quelldaten; bei einem Abruffehler stellt Rust den aktiven lokalen Snapshot inkl. Medienreferenzen wieder her. UI zeigt den Fehler an und archiviert nicht erneut aus dem Netz. Nie gespeicherte Profile zeigen den Abruffehler.
- Einstellungen: Import, DB-Status und lokale Diagnose. Karte: [MAP.md](MAP.md).

## Operationen, Konsistenz und Tests

- UniFFI RequestControl wird pro Auftrag verwendet. Abbrechen/Navigation/Lebenszyklusende unterbinden weitere Arbeit; laufende HTTP-Anfragen können bis zum Timeout brauchen. Vor Suchpersistenz bzw. gespeicherten Ergebnis-Deltas wird der Abbruchcontroller auch vor dem SQLite-Commit geprüft.
- Das ViewModel verwirft überholte Such-, Vorlagen-, Paging- und Profilantworten. Archivierung wird vor bestätigtem Merge abgebrochen; beide Operationen nutzen innerhalb ihrer Transaktion den aktuellen Personenbesitz des Inserats.
- Tests decken Datenbankidentität, Migration, Fremdschlüssel, Idempotenz, Paging, Nutzerentscheidungen, Snapshot-Übergänge/Rollback, Normalisierung, eindeutige dHash-Evidenz, Merge-Erhalt, Medienlimits und Wiederverwendung ab. Implementierung und Testquellen liegen im Rust-Core; die zentralen Build-/CI-Befehle stehen in [README](../README.md). Ein grüner Offline-Build belegt keine reale Android- oder Quellkompatibilität.

Historische Referenz für das Datenmodell: Justify87/ladies-webapp @ `9c514af6ce3659089807fefd4ed5040599975a5a` (models.py, Repositories persons/search/person_identity/saved_searches). Die früheren Meilensteinberichte bleiben in Git erhalten.
