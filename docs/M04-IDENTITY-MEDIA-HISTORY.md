# M04 — Identity evidence, local media archive and change history

## Scope and execution

This milestone follows M03's stored, immutable source snapshots. It preserves the
single APK, Rust/SQLite ownership and UniFFI interface. No messaging, WhatsApp
protocol, Python runtime, automatic price extraction, swipe UI or maps.

- Each successful profile extraction persists its snapshot and a **transition-based**
  change history in the same SQL transaction. Repeated identical loads create
  observations but no change events; returning to an earlier snapshot emits
  fresh field/media transition events.
- Profile telephone numbers are normalized and persisted as searchable person
  identifiers. Source-profile IDs and image perceptual hashes contribute
  **identity candidates**.
- A single similar image (dHash Hamming distance <= 8), matching telephone or
  shared source identity can suggest another person. Multiple independent image
  matches can strengthen the evidence. Ambiguous ties yield no preferred
  candidate. Evidence does **not automatically merge people**.
- The typed Rust API exposes candidate inspection and explicit confirmation.
  A confirmed merge is transactional and preserves user preference, favorites,
  notes, listings, source profiles, identifiers, encounter data and history.
  It refuses a manual-split listing and refuses targets without evidence.
  Android UI for candidate confirmation belongs to M05.
- Archived images are stored under app-private \`ladies_data/media/images/\`
  with SHA-256 deduplication, JPEG/PNG/WebP/GIF dimensions and a 64-bit dHash
  compatible with the legacy left-vs-right LANCZOS resize. Videos keep their
  remote references by default; the Rust API supports opt-in archiving.
- Images are fetched separately from the profile snapshot transaction. Failed
  downloads leave the canonical profile and remote references intact. The API
  returns downloaded/reused/failed/deferred counts. Repeated calls reuse
  existing archived data without refetching.
- HTTP downloads reject redirects and remote hosts other than recognized
  HTTPS Ladies media domains. The byte limit is 12 MiB per image and 48 MiB
  per requested video, at most 80 references per call. Neither remote URLs
  nor data are written to Git diagnostics except for existing scoped logs.

## Data model

All M04 tables and columns are already present in schema v1:
\`listing_snapshots\`, \`snapshot_media\`, \`media_assets\`, \`media_sources\`,
\`person_identifiers\`, \`identity_evidence\`, \`identity_events\`, and
\`change_events\`. There is **no schema version bump**.

Changes are compared against the **active** snapshot rather than the most
recently inserted historical row, so a return to an older fingerprint still
records the real transition.

## Test strategy

- Rust unit tests: snapshot transitions and rollback, repeated profiles,
  matching phone normalization, independent dHash evidence, merge preservation,
  content-addressed media storage, reuse, strict CDN allowlist.
- GitHub Actions: rustfmt, complete Rust workspace tests, UniFFI binding
  generation, Compose/Kotlin compilation and ARM64 debug APK artifact.
- Real Android testing remains required for current Ladies HTML/CDN behavior
  and full-size media galleries. A green offline CI cannot verify remote sites.

## Review-Nachbesserungen

- Die Zuordnung von Bild-hashes verwendet eine maximale eindeutige Paarbildung statt
  lokaler Greedy-Treffer; Bilder können Kandidaten nicht mehrfach künstlich stärken.
- Ein bestätigter Merge verwirft niemals widersprüchliche LIKE/DISLIKE- oder
  Wiederholungsentscheidungen: Solche Konflikte müssen vor dem Merge geklärt werden.
- Mit Schema v2 bleiben `previous_person_id` und `new_person_id` als historische
  Werte erhalten, auch wenn Personen nach einem bestätigten Merge gelöscht werden.
  Datenbanken mit gültigem Schema v1 werden transaktional migriert.
- Die Kategorie gehört zum Snapshot-Fingerprint, damit reine Kategorieänderungen
  als echte Profiländerung historisiert werden.
- Eine gemeinsame URL-Validierung wird von Parser und Downloader verwendet;
  zugelassene mobile Quellhosts und nummerierte CDN-Hosts werden konsistent behandelt.
- Bilddimensionen und Pixelbudget werden vor vollständiger Dekodierung begrenzt.
- Der Medienabruf verfügt über einen UniFFI-Abbruchcontroller pro Operation sowie
  Gesamt- und Anfragezeitbudgets. Das ViewModel beendet Aufträge bei Ansichtswechsel,
  explizitem Abbruch und Lebenszyklusende. Alte Antworten aktualisieren keine neueren Profile.
- Profil-Snapshots und bereits gespeicherte Dateien bleiben unabhängig von
  Medienabbruch oder Downloadfehlern erhalten.
