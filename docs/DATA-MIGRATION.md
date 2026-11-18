# M01 — Local Rust domain storage

## Reference and scope

Source reference: Justify87/ladies-webapp @ `9c514af6ce3659089807fefd4ed5040599975a5a`
(2026-10-16), particularly `ladies/models.py`, `ladies/core/repositories/persons.py`,
`search.py`, `person_identity.py`, and `ladies/core/repositories/saved_searches.py`.

This milestone does **not** copy SQLAlchemy, Flask, Python scraping, Messenger,
WhatsApp synchronization, OCR, price extraction or any user data to Android.

## Canonical entities and identity invariants

- **Person** is the canonical user-facing identity. Several listings and
  external source profiles can belong to one person.
- **SourceProfile** represents one identity on an external source. Its
  `(source, external_profile_id)` must not be reassigned silently.
- **Listing** represents a unique advertisement on a source. Its
  `(source, external_listing_id)` must not be reassigned silently.
- A repeated name, telephone number or image resemblance **does not**
  automatically merge persons. Merge/split algorithms require a separate,
  test-backed identity milestone.
- **PersonUserState** holds LIKE/DISLIKE/Favorite and personal notes at
  person-level, not listing-level.
- **ListingSnapshot**, **SnapshotValue**, **SnapshotMedia** and **ChangeEvent**
  are append-only domain history structures; this milestone defines their
  storage, but does not yet implement capture/extraction.
- `media_assets` is content-addressed by SHA-256; `media_sources` carries
  remote origin metadata. Actual download/archive behavior comes later.

## Schema mapping

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
| PriceList / PriceItem | price_lists / price_items | Created; manual entry only |
| AppSetting | app_settings | Created |
| Messaging / Baileys / chat threads | — | Explicitly excluded |
| Automated AI/OCR price extraction | — | Explicitly excluded |
| VisualAnalysis | — | Deferred; not needed for initial core |

The Android schema is intentionally not a binary copy of the legacy database.
The legacy SQLAlchemy DB must **not** be opened as the Android SQLite store.
A later, explicit exporter/importer will transform legacy data into the new
tables, preserving foreign keys, evidence, historical records and media.

## Migration and safety semantics

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
  of whether Kotlin/UniFFI or a future importer inserted it.
- SQLite triggers forbid linking a listing to a source profile belonging to a
  different person or source, including direct INSERT/UPDATE from an importer.
- Seeing a source profile or listing updates the canonical person's last-seen
  timestamp; listing events also update the attached source profile.
- The people feed exposes keyset pagination (`last_seen_at`, `person_id`) with
  explicit 1–100 page-size validation and deterministic tie-breaking.
- The SQLite connection is shared behind a Rust Mutex; Kotlin invokes the
  generated bindings from `Dispatchers.IO`.

## Evidence and tests

Unit tests exercise new DB creation, repeat migration, upgrade from the demo
scaffold, unknown-schema rejection, foreign-key enforcement, successful
person/profile/listing round trips, duplicate idempotency, refusal to
reassign identities, non-merge of distinct people, input validation and
reopening the persisted DB. Additional tests cover legacy v1 rejection and
preservation, same-version schema drift, saved-search string IDs and result
history, direct SQLite cross-source/person writes, activity time updates,
and paging across more than 100 people with identical timestamps.

CI runs `cargo fmt --all -- --check`, `cargo test --workspace` and an
ARM64 Android APK build with the Artefact-aligned Gradle + UniFFI toolchain.

## Subsequent milestones

1. Archive exporter/importer and verified reference fixtures from the web app.
   The importer must explicitly validate referential integrity and transfer
   saved-search definitions, result histories, and their timestamps/state.
2. Rust HTTP search and page extraction, preserving source-specific behavior.
3. Snapshot/change history, media archive, explicit identity evidence and matching.
4. Compose profile views, discovery swipe deck, filters and MapLibre UI.

No runtime HTTP server, Python/Flask process, embedded messenger or cloud DB.
