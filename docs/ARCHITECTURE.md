# Architektur und native Datenflüsse

Die App ist ein lokales Android-Hobbyprojekt mit einer APK. Kotlin/Compose (Material 3) besitzt Darstellung, Navigation und Lebenszyklus; der eigene Rust-Core besitzt die fachliche Logik und SQLite-Persistenz. UniFFI/JNA stellt die typisierte Grenze dar. Kotlin-Bindings werden von UniFFI beim Gradle-Build erzeugt. Kein Python, Flask, Node.js, eigener HTTP-Server oder Cloud-Backend; die Herkunft der technischen Basis steht in [PROVENANCE.md](PROVENANCE.md).

## Datenmodell und Zuständigkeiten

- Person ist die kanonische Nutzeridentität. Mehrere Inserate und Quellprofile können zu einer Person gehören. Die Schlüssel `(source, external_profile_id)` und `(source, external_listing_id)` sind eindeutig und dürfen nicht stillschweigend einer anderen Person zugeordnet werden.
- PersonUserState hält Entscheidungen (LIKE/DISLIKE), Favoriten und Notizen auf Personenebene. Notizen, Begegnungen und manuelle Preislisten sind im Schema/Import vorhanden; daraus folgt keine vollständige Bearbeitungsoberfläche ([ROADMAP.md](ROADMAP.md)).
- Snapshots, Feldwerte, Medienbezüge und Änderungsereignisse historisieren Quellprofile. Identitätsevidenz allein löst keine automatische Zusammenführung aus.
- Rust-Module: `schema.rs` (Versionen und Konsistenz), `legacy_import.rs` (Import), `source/` (Suche/Extraktion), `history.rs`, `identity.rs`, `media.rs` (Historie/Identität/Archiv), `discovery.rs`, `saved_search.rs` (native Feeds/gespeicherte Suchen), `map.rs` und Diagnose-Module. Kotlin nutzt die Bindings über CoreStore und MainViewModel; Screens halten Darstellungszustand und delegieren fachliche Operationen.
- Quellverhalten: [SOURCE.md](SOURCE.md); Kartenvertrag: [MAP.md](MAP.md); Diagnose: [DIAGNOSTICS.md](DIAGNOSTICS.md).

## SQLite-Schema und Legacy-Zuordnung

| Legacy-Modell | Native SQLite-Tabelle | Status |
| --- | --- | --- |
| Person | persons | Vorhanden, typisierte API |
| PersonUserState | person_user_state | Vorhanden, typisierte Präferenz-/Favoriten-API |
| SourceProfile | source_profiles | Vorhanden, typisierte API mit Konfliktprüfung |
| Listing | listings | Vorhanden, typisierte API mit Konfliktprüfung |
| PersonIdentifier | person_identifiers | Vorhanden |
| ListingSnapshot / SnapshotValue | listing_snapshots / snapshot_values | Vorhanden |
| MediaAsset / MediaSource / SnapshotMedia | media_assets / media_sources / snapshot_media | Vorhanden |
| SearchRun / SearchResult / ListingObservation | search_runs / search_results / listing_observations | Vorhanden |
| SavedSearchRecord | saved_searches | String-IDs, Definitionsversionen, Benachrichtigungs-/Delta-Status |
| SavedSearchResultRecord | saved_search_results | Historische Ergebnisse, Sichtbarkeit, first/last seen |
| ChangeEvent | change_events | Vorhanden |
| IdentityEvidence / IdentityEvent | identity_evidence / identity_events | Vorhanden |
| Encounter / EncounterService | encounters / encounter_services | Vorhanden |
| PriceList / PriceItem | price_lists / price_items | Schema/Import nur manueller Einträge |
| AppSetting | app_settings | Vorhanden |
| Messaging / Baileys / chat threads | — | Ausgeschlossen |
| Automated AI/OCR price extraction | — | Ausgeschlossen |
| VisualAnalysis | — | Nicht im aktuellen Core |

Das Android-Schema ist keine binäre Kopie der Legacy-Datenbank. Die alte SQLAlchemy-DB darf **nicht** direkt als native SQLite-Datenbank geöffnet werden. Der implementierte Exporter/Importer transformiert die Daten; siehe [DATA-IMPORT.md](DATA-IMPORT.md).

## Migration und Konsistenz

- Native DB: `PRAGMA user_version = 2`, eigene `application_id = 0x4C445850` (LDXP); Legacy-Web-App v1 ist ein anderes Format. Migration vor Freigabe des Stores in einer Rust-Transaktion. Neue Datenbanken installieren das Domänenschema und migrieren historische Identitätsreferenzen auf v2 in einer Transaktion.
- Native v1 muss vor dem Upgrade dem kanonischen Schema entsprechen; jede spätere Öffnung prüft v2-Tabellen, Indizes und Trigger. Fremde IDs, unbekannte unversionierte Tabellen, Schemaabweichungen und zukünftige Versionen erzeugen typisierte Fehler. Nur eine frische DB, die exakt bekannte technische Demo mit `scaffold_notes` oder gültige native v1 werden migriert. Die Demo-Tabelle wird transaktional entfernt; echte Personen/Historie werden nicht implizit gelöscht.
- `PRAGMA foreign_keys=ON` je Verbindung. App-private DB unter `filesDir/ladies.sqlite`; öffentliche Schreiboperationen verwenden SQLite-Parameter und Transaktionen.
- Ein Personen-INSERT erzeugt den Nutzerstatus per DB-Trigger auch beim direkten Import. Trigger verhindern bei INSERT/UPDATE Verknüpfungen eines Inserats mit einer fremden Person oder Quelle. Sichtungen von Quellprofil/Inserat aktualisieren last_seen der Person; Inseratereignisse auch das zugehörige Quellprofil.
- Die allgemeine Personenabfrage (`PersonCursor` in lib.rs) nutzt Keyset-Pagination über `last_seen_at` und `person_id`. Der davon getrennte Discovery-/Favoritenfeed nutzt `DiscoveryCursor` (`favorite`, Inserat-`last_seen_at`, `listing_id`), entsprechend seiner Sortierung absteigend. Beide validieren die Seitengröße 1–100. Die SQLite-Verbindung liegt hinter einem Rust-Mutex; Kotlin ruft die Bindings über `Dispatchers.IO` auf.

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

- Entdecken: ein aktuelles Inserat pro Person, LIKE/DISLIKE per Swipe oder Buttons, „Später“ nur für die aktuelle Sitzung. Entdecken enthält ausschließlich Personen mit Präferenz NONE, also weder LIKE noch DISLIKE. Favoriten sind unabhängig von der Swipe-Präferenz. Beide Feeds paginieren getrennt mit dem oben beschriebenen Discovery-Cursor; die für Entdecken sitzungsbezogen übersprungenen Personen werden vor dem Paging ausgeschlossen.
- Suche: Ort, Radius, Rubrik, Alter, Größe, Seitenlimit, Rust-Filterkatalog; LazyColumn und sichtbarer Hinweis für unvollständige Suche. Gespeicherte Suchen: siehe [SOURCE.md](SOURCE.md).
- Profil: Galerie, Merkmale, Services, Zeiten, Telefon, externer WhatsApp-Aufruf, Verlauf, explizite Identitätsbestätigung. Zunächst aktuelle Quelldaten; bei einem Abruffehler stellt Rust den aktiven lokalen Snapshot inkl. Medienreferenzen wieder her. UI zeigt den Fehler an und archiviert nicht erneut aus dem Netz. Nie gespeicherte Profile zeigen den Abruffehler.
- Einstellungen: Import, DB-Status und lokale Diagnose. Karte: [MAP.md](MAP.md).

## Operationen, Konsistenz und Tests

- UniFFI RequestControl wird pro Auftrag verwendet. Abbrechen/Navigation/Lebenszyklusende unterbinden weitere Arbeit; laufende HTTP-Anfragen können bis zum Timeout brauchen. Vor Suchpersistenz bzw. gespeicherten Ergebnis-Deltas wird der Abbruchcontroller auch vor dem SQLite-Commit geprüft.
- Das ViewModel verwirft überholte Such-, Vorlagen-, Paging- und Profilantworten. Archivierung wird vor bestätigtem Merge abgebrochen; beide Operationen nutzen innerhalb ihrer Transaktion den aktuellen Personenbesitz des Inserats.
- Tests decken Datenbankidentität, Migration, Fremdschlüssel, Idempotenz, Paging, Nutzerentscheidungen, Snapshot-Übergänge/Rollback, Normalisierung, eindeutige dHash-Evidenz, Merge-Erhalt, Medienlimits und Wiederverwendung ab. Implementierung und Testquellen liegen im Rust-Core; die zentralen Build-/CI-Befehle stehen in [README](../README.md). Ein grüner Offline-Build belegt keine reale Android- oder Quellkompatibilität.

Historische Referenz für das Datenmodell: Justify87/ladies-webapp @ `9c514af6ce3659089807fefd4ed5040599975a5a` (models.py, Repositories persons/search/person_identity/saved_searches). Die früheren Meilensteinberichte bleiben in Git erhalten.
