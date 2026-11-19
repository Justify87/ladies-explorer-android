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
- Coil 3 lädt Vorschaubilder und Galerie lifecycle-gebunden, bevorzugt lokale Archivdateien und nutzt Remote-URLs als Fallback ausschließlich über Rust. Videos und WhatsApp werden extern geöffnet; kein eingebauter Messenger oder Videoplayer.

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

### Gemeinsame Profildarstellung (M05.3)

`ProfileIdentity` projiziert nur persistierte Inserat-/Persondaten (kanonische Person-ID, Status, Neuigkeit <36h, Videomarker, validierte Quell-URL). Der Profilaufruf liest die tatsächliche Inseratzuordnung nach Abruf und Identitätsbestätigung erneut. Kotlin nutzt gemeinsame Galerie-/Identitäts-/Aktionskomponenten und einen extrahierten Profilinhalt; Detailaktionen aktualisieren Personzustand vor Freigabe des gemeinsamen Aktionsguards. Coil bevorzugt vorhandene lokale Dateien; bei Ladefehler fällt die UI auf Remote zurück, bei weiterem Fehler auf sichtbaren Platzhalter. Videos/WhatsApp/Originalprofil werden extern geöffnet, Telefonnummer per Dialer; kein integrierter Player oder Chat. Bestehende Archivierung und Offline-Fallback bleiben erhalten. Vollständige Swipe-Einbettung und neue Detailtabs sind getrennte Folgemeilensteine.

### Swipe-Sitzung (M05.4)

Der aktive Suchlauf bleibt die einzige Quelle des ausgeführten Run-Feeds. `SwipeSession` hält ausschließlich sessionlokale Traversierung und Undo-Records (Listing-ID, erwartete Person, vorherige/neue Präferenz); Replay-Likes ist eine unabhängige Android-Einstellung. Rust `set_swipe_preference` prüft und schreibt den kanonischen Besitzer und bei Undo die erwartete Präferenz atomar. Vor dem nächsten sichtbaren Kartenwechsel wird die Mutation gespeichert; bei Reprojektionsfehler werden veraltete Cards verworfen. Der neutrale lokale Bestandsfeed ist separat ausgewiesen. Swipe und Standalone verwenden dieselben Profil-/Medienkomponenten. Das sichtbare Swipe-Fenster hält aktuelles und maximal zwei folgende Profile; bestehende Snapshot-Lesewege, abbrechbarer sequenzieller Profilabruf und Coil-Cache ersetzen zusätzliche Medienarchivierung. Der blockierende Transport behält seinen Timeout, der Abbruch invalidiert UI und vermeidet Persistenz nach erkanntem Cancel. Keine neue Datenbankstruktur oder weitere Netzwerkbibliothek.

## M05.4a – Quellen-HTTP-Grenze

Alle app-internen Abrufe von ladies.de (inklusive APIs, Subdomains und Medien-CDNs) werden ausschließlich im Rust-Core ausgeführt. Coil 3 verwendet `coil-network-core` mit `RustImageNetworkClient` → CoreStore → UniFFI `load_display_image` → vorhandener Rust-Medientransport. Keine direkte HTTP-Bibliothek für die Quelle in Kotlin. Shared Rust-Medienclient für Anzeige und Archiv; reine Anzeige bleibt ohne SQLite-/Archivmutation. HTTPS-Hostprüfung, keine Redirects, 12 MiB Bildlimit, 15 s Requestbudget, vier gleichzeitige blockierende Anzeigeabrufe über begrenzten IO-Dispatcher, kooperativer Abbruch (laufendes Lesen kann bis zum Timeout dauern). Coil behält URL-/Revisionsschlüssel, Memory-/Disk-Cache, Decodierung und dedupliziert gleichzeitig angeforderte Cache-Schlüssel; kodierte Bytes werden begrenzt über UniFFI übergeben. Archivierung kann bei paralleler Anzeige derselben URL noch separat laden; keine zusätzliche Cache-/Persistenzschicht in M05.4a ohne Messbeleg. MapLibre-Kartenressourcen und ausdrücklich ausgelöste externe Intents bleiben außerhalb dieser Quellen-HTTP-Grenze. Architekturcheck in Android-CI dient als statischer Regressionstest, nicht als vollständiger Laufzeitnachweis.

## M05.5 – Grid-Projektion

Die Grid-Ansicht zeigt `SearchUiState.activeCards` aus der aktiven Rust-Suche, nicht die konsumierte Swipe-Queue. Ohne aktiven Run zeigt sie den explizit neutralen lokalen Deckbestand. `ProfileGridCard` nutzt `ProfileImage` und damit ausschließlich den Rust-Medientransport für Remote-Bilder. Alter ist nur bei eindeutiger gespeicherter Quell-Eigenschaft verfügbar; keine zusätzlichen Profilrequests zur Dekoration. Grid-Herz speichert den Wechsel LIKE/NONE (bzw. anderer Zustand zu LIKE) mit erwarteter Person und Präferenz atomar im Core. Die gemeinsame Projektion liest anschließend den Datenbankstand; Swipe-Verlauf/Undo wird nicht als Grid-Navigation benutzt. Der ausgeführte Rust-Hide-Likes-Filter bleibt fachlich maßgeblich; zusätzlich blendet ein ausschließlich suchlaufgebundener Grid-Präsentationszustand neu gelikte Inserate gemäß finaler Legacy-UI sofort aus (Rollback bei Schreibfehler, Reset bei erfolgreichem neuen Lauf, keine zweite Ergebnisliste). Eine gemeinsame Mutations-/Reprojektionseinheit unterscheidet Fehler vor und nach dem DB-Schreibvorgang und liest bei Aktualisierung mindestens den bereits angeforderten lokalen/Favoritenumfang mit frischen Cursorn. Bei Fehler werden stale Cards verworfen und nur Lesevorgänge wiederholt. Snapshot/Archivänderungen aktualisieren die Projektion; Grid und Profil verwenden dieselbe explizite Altersprojektion aus Rust. Ansichtswechsel bleiben lokale Navigation; nach tatsächlich veränderter Swipe-Vorladung erfolgt beim Wechsel eine lokale Reprojektion. MapLibre unverändert.

## M05.6 – Profildetails

Gemeinsame native Info-/Dienste-/Analyse-/Notizen-/Verlaufpanels in Swipe und Einzelprofil. Rust projiziert Snapshot-Felder, explizite positive/negative/unbekannte Filterbelege, personengebundene Notizen, verknüpfte Anzeigen und gespeicherte Konsistenz-/Medienevidenz. Notizen aktualisieren ausschließlich private_note und updated_at mit transaktionalem Besitzer- und Erwartungswertvergleich; kein neues Schema. Der gemeinsame ViewModel-Aktionsguard besitzt alle Notizschreibvorgänge und personengebundene Entwürfe über Navigation/Rotation hinweg (nicht über Prozessende). Saubere Entwürfe übernehmen neue DB-Werte; Konflikte werden explizit aufgelöst. Identitätsmerge und Import verweigern den Vorgang bei betroffenen ungespeicherten Entwürfen. Die UI besitzt nur cancellable Leseprojektionen je sichtbarem Tab; Fehler invalidieren alte Daten. Filterbelege verwenden die vorhandenen katalogisierten Aliase und den Strukturkontext, keine dynamische Verfügbarkeitsableitung. Der Snapshotvergleich prüft aktuelle Besitzer- und Inseratzuordnung beider Snapshots. Keine zusätzlichen Quellenabrufe, keine KI-Affinität, Preise oder Begegnungen. Swipe nutzt die bestehende explizite Identitätsbestätigung im Detail.
