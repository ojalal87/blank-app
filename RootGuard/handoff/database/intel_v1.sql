PRAGMA foreign_keys=ON;
PRAGMA user_version=1;
CREATE TABLE feed_source (
 source_id TEXT PRIMARY KEY, name TEXT NOT NULL, homepage TEXT NOT NULL, adapter_id TEXT NOT NULL,
 category TEXT NOT NULL CHECK(category IN ('MALWARE','PHISHING','TRACKING','ADS','IP_THREAT')),
 enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
 license_state TEXT NOT NULL CHECK(license_state IN ('PENDING','APPROVED','REJECTED')),
 terms_url TEXT NOT NULL, terms_checked_ms INTEGER, credential_alias TEXT,
 CHECK(enabled=0 OR license_state='APPROVED')
);
CREATE TABLE feed_generation (
 generation_id TEXT PRIMARY KEY, source_id TEXT NOT NULL REFERENCES feed_source(source_id),
 fetched_ms INTEGER NOT NULL, upstream_version TEXT, etag TEXT, payload_sha256 TEXT NOT NULL,
 signature_status TEXT NOT NULL CHECK(signature_status IN ('VERIFIED','UNSIGNED','FAILED')),
 entry_count INTEGER NOT NULL CHECK(entry_count>=0), expires_ms INTEGER,
 state TEXT NOT NULL CHECK(state IN ('STAGED','ACTIVE','PREVIOUS','REJECTED')),
 UNIQUE(source_id,generation_id), CHECK(state<>'ACTIVE' OR signature_status<>'FAILED')
);
CREATE UNIQUE INDEX one_active_feed_generation ON feed_generation(source_id) WHERE state='ACTIVE';
CREATE TABLE indicator (
 indicator_id TEXT PRIMARY KEY,
 kind TEXT NOT NULL CHECK(kind IN ('DOMAIN','DOMAIN_SUFFIX','IP','CIDR','URL','SHA256')),
 normalized_value TEXT NOT NULL, ip_family INTEGER CHECK(ip_family IN (4,6)), network_bytes BLOB, prefix_length INTEGER,
 UNIQUE(kind,normalized_value),
 CHECK((kind IN ('IP','CIDR') AND ip_family IS NOT NULL AND network_bytes IS NOT NULL AND prefix_length IS NOT NULL AND length(network_bytes)=CASE ip_family WHEN 4 THEN 4 ELSE 16 END AND prefix_length BETWEEN 0 AND CASE ip_family WHEN 4 THEN 32 ELSE 128 END)
 OR (kind NOT IN ('IP','CIDR') AND ip_family IS NULL AND network_bytes IS NULL AND prefix_length IS NULL))
);
CREATE TABLE indicator_provenance (
 indicator_id TEXT NOT NULL REFERENCES indicator(indicator_id) ON DELETE CASCADE,
 generation_id TEXT NOT NULL REFERENCES feed_generation(generation_id) ON DELETE CASCADE,
 category TEXT NOT NULL, first_seen_ms INTEGER, last_seen_ms INTEGER, expires_ms INTEGER,
 upstream_confidence REAL CHECK(upstream_confidence BETWEEN 0 AND 1),
 PRIMARY KEY(indicator_id,generation_id)
);
CREATE INDEX provenance_generation ON indicator_provenance(generation_id);
CREATE VIEW active_indicator AS
 SELECT i.*, p.generation_id,p.category,p.expires_ms AS provenance_expires_ms,g.expires_ms AS generation_expires_ms,s.source_id
 FROM indicator i JOIN indicator_provenance p USING(indicator_id)
 JOIN feed_generation g USING(generation_id) JOIN feed_source s USING(source_id)
 WHERE g.state='ACTIVE' AND s.enabled=1 AND s.license_state='APPROVED';
CREATE TABLE feed_update_attempt (
 attempt_id TEXT PRIMARY KEY, source_id TEXT NOT NULL REFERENCES feed_source(source_id),
 started_ms INTEGER NOT NULL, ended_ms INTEGER, status TEXT NOT NULL CHECK(status IN ('RUNNING','SUCCESS','NOT_MODIFIED','FAILED')),
 error_code TEXT, redacted_details_json TEXT NOT NULL
);
