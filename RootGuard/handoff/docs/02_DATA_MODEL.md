# Database design
## Stores
- control.db: user intent, app/principal identity, profiles, policy revisions, apply journal, capability and consent/audit.
- telemetry.db: short-lived flow metadata, feature windows, baseline/model state, incidents and evidence.
- intel.db: feed metadata, immutable generations, normalized indicators and per-source provenance.

SQL files create portable SQLite logical v1 databases with FK enforcement and indexes. They intentionally do not contain Room metadata. Generate Room entities and exported schemas before use in Android; verify equivalence with these SQL constraints. Database schema version and feature/model schema version are different.

Cross-store app_id, principal_id, revision_id and indicator IDs are logical references, not foreign keys. Reconcile via typed repositories and idempotent jobs. Incidents retain immutable source/value evidence so deletion of raw telemetry or feed generations does not erase explanations. Purge/erasure jobs must process all stores; tombstones/outbox track completion. IDs are application-generated UUIDs unless a natural key is documented.

## Time and measurement
UTC epoch milliseconds for persisted wall time; elapsed realtime and boot ID for rates, TTL and sequences. Reject negative durations. Never subtract cumulative counters across boot/interface/principal reset. Raw bytes are nonnegative integer deltas; unavailable values NULL, not zero. Record source, attribution confidence, completeness, sampling and drop counts. UID-global totals cannot be presented as destination-specific measured bytes. No extrapolation without a label.
Domain matches: lowercase IDNA ASCII, strip trailing dot, DNS label boundary suffix matching. IP canonical binary 4/16 bytes with prefix validation and network host bits cleared. Full URL matching requires actually observed URL; HTTPS metadata is insufficient. DNS responses map names to IP with TTL and provenance, not certainty about a later connection's hostname.

## Retention defaults, editable in wizard
flow events 48 hours; minute aggregates 14 days; hourly aggregates 90 days; feature windows 30 days; accepted baseline summaries 90 days or bounded rolling sample; incidents 90 days; security audit 180 days. Closed app/principal mappings retained while referenced then purged. Feed snapshots keep active and last known good plus bounded debugging metadata. Retention is coupled to quotas and user deletion/export.

## Encryption
Production private stores require a supported encrypted SQLite adapter (or documented field encryption where appropriate), Android Keystore-wrapped key material, auto-backup exclusion and locked-device behavior. The included SQLite fixtures are plaintext synthetic empty databases. No claim of encrypted DB implementation is made here. No passwords, API keys, certificate private keys, raw message bodies or full packet payloads in SQL/logs. Secret aliases point to a separate secure store. Rooted host cannot be treated as a perfect confidentiality boundary.

## Migration and recovery
exportSchema=true, incremental explicit migrations and MigrationTestHelper tests on every supported path. Never fallbackToDestructiveMigration for user policy/consent/audit. Rebuildable intel indexes may be regenerated from validated snapshots; policy store corruption enters visible recovery with last-known-good snapshot, not fabricated success. Backup export is explicit and encrypted; importing validates schema version, size, enums and capabilities and always previews rule diff.

## Write protocols
Telemetry batches use one transaction and dedupe (boot_id, sensor_id, sequence). Producer retries retain sequence IDs. Model writes snapshot state + watermark; scoring is retry-idempotent by model/window uniqueness. Outbox carries cross-store work and retries. Rule application uses PREPARED/APPLYING/VERIFIED/COMMITTED/ROLLED_BACK/FAILED; atomic DB commits are never assumed atomic with kernel operations.
