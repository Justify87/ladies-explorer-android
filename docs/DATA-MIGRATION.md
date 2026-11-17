# M01 — Local Rust domain storage

## Reference and scope

Source reference: Justify87/ladies-webapp @ \`9c514af6ce3659089807fefd4ed5040599975a5a\`
(2026-10-16), particularly \`ladies/models.py\`, \`ladies/core/repositories/persons.py\`,
\`search.py\`, and \`person_identity.py\`.

This milestone does **not** copy SQLAlchemy, Flask, Python scraping, Messenger,
WhatsApp synchronization, OCR, price extraction or any user data to Android.

## Canonical entities and identity invariants

- **Person** is the canonical user-facing identity. Several listings and
  external source profiles can belong to one person.
- **SourceProfile** represents one identity on an external source. Its
  \`(source, external_profile_id)\` must not be reassigned silently.
- **Listing** represents a unique advertisement on a source. Its
  \`(source, external_listing_id)\` must not be reassigned silently.
- A repeated name, telephone number or image resemblance **does not**
  automatically merge persons. Merge/split algorithms require a separate,
  test-backed identity milestone.
- **PersonUserState** holds LIKE/DISLIKE/Favorite and personal notes at
  person-level, not listing-level.
- **ListingSnapshot**, **SnapshotValue**, **SnapshotMedia** and **ChangeEvent**
  are append-only domain history structures; this milestone defines their
  storage, but does not yet implement capture/extraction.
- \`media_assets\` is content-addressed by SHA-256; \`media_sources\` carries
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
| Saved searches | saved_searches | Created; UI/serialization later |
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

- SQLite schema version is \`PRAGMA user_version = 1\`.
- Migration runs transactionally on the **Rust side** before exposing the store.
- A new DB installs the version 1 schema in one transaction.
- The obsolete \`scaffold_notes\` technical-demo table is dropped within the
  migration transaction; real people or history are never implicitly deleted.
- Unknown unversioned tables or future schema versions cause a typed error,
  not silent replacement or destructive recreation.
- \`PRAGMA foreign_keys=ON\` is enabled for every new connection.
- The database is stored privately in \`filesDir/ladies.sqlite\`.
- Public write operations use SQLite parameters and transactions.
- The SQLite connection is shared behind a Rust Mutex; Kotlin invokes the
  generated bindings from \`Dispatchers.IO\`.

## Evidence and tests

Unit tests exercise new DB creation, repeat migration, upgrade from the demo
scaffold, unknown-schema rejection, foreign-key enforcement, successful
person/profile/listing round trips, duplicate idempotency, refusal to
reassign identities, non-merge of distinct people, input validation and
reopening the persisted DB.

CI runs \`cargo fmt --all -- --check\`, \`cargo test --workspace\` and an
ARM64 Android APK build with the Artefact-aligned Gradle + UniFFI toolchain.

## Subsequent milestones

1. Archive exporter/importer and verified reference fixtures from the web app.
2. Rust HTTP search and page extraction, preserving source-specific behavior.
3. Snapshot/change history, media archive, explicit identity evidence and matching.
4. Compose profile views, discovery swipe deck, filters and MapLibre UI.

No runtime HTTP server, Python/Flask process, embedded messenger or cloud DB.
