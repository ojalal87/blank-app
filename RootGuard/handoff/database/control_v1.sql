PRAGMA foreign_keys=ON;
PRAGMA user_version=1;
CREATE TABLE app_installation (
 app_id TEXT PRIMARY KEY, user_serial INTEGER NOT NULL, package_name TEXT NOT NULL,
 install_generation TEXT NOT NULL, signer_lineage_sha256 TEXT NOT NULL,
 installed_ms INTEGER NOT NULL, removed_ms INTEGER,
 UNIQUE(user_serial,package_name,install_generation), CHECK(removed_ms IS NULL OR removed_ms>=installed_ms)
);
CREATE UNIQUE INDEX one_active_install ON app_installation(user_serial,package_name) WHERE removed_ms IS NULL;
CREATE TABLE app_version (
 app_id TEXT NOT NULL REFERENCES app_installation(app_id), version_code INTEGER NOT NULL,
 observed_ms INTEGER NOT NULL, signer_sha256 TEXT NOT NULL, permissions_json TEXT NOT NULL,
 PRIMARY KEY(app_id,observed_ms)
);
CREATE TABLE principal (
 principal_id TEXT PRIMARY KEY, user_serial INTEGER NOT NULL, android_uid INTEGER NOT NULL CHECK(android_uid>=0),
 valid_from_ms INTEGER NOT NULL, valid_until_ms INTEGER, attribution TEXT NOT NULL CHECK(attribution IN ('EXACT','SHARED','UNKNOWN')),
 CHECK(valid_until_ms IS NULL OR valid_until_ms>=valid_from_ms)
);
CREATE UNIQUE INDEX one_active_uid ON principal(user_serial,android_uid) WHERE valid_until_ms IS NULL;
CREATE TABLE principal_member (
 principal_id TEXT NOT NULL REFERENCES principal(principal_id), app_id TEXT NOT NULL REFERENCES app_installation(app_id),
 valid_from_ms INTEGER NOT NULL, valid_until_ms INTEGER,
 PRIMARY KEY(principal_id,app_id,valid_from_ms), CHECK(valid_until_ms IS NULL OR valid_until_ms>=valid_from_ms)
);
CREATE TABLE profile (
 profile_id TEXT PRIMARY KEY, name TEXT NOT NULL, mode TEXT NOT NULL CHECK(mode IN ('OBSERVE','BALANCED','PRIVACY','STRICT','DATA_SAVER','CUSTOM')),
 default_action TEXT NOT NULL CHECK(default_action IN ('ALLOW','DENY')),
 behavior_response TEXT NOT NULL CHECK(behavior_response IN ('LOG','NOTIFY','TEMP_CONTAIN')),
 failure_mode TEXT NOT NULL CHECK(failure_mode IN ('PRESERVE_LAST_GOOD','STRICT_FAIL_CLOSED')),
 created_ms INTEGER NOT NULL
);
CREATE TABLE policy_revision (
 revision_id TEXT PRIMARY KEY, profile_id TEXT NOT NULL REFERENCES profile(profile_id),
 parent_revision_id TEXT REFERENCES policy_revision(revision_id), created_ms INTEGER NOT NULL,
 policy_json TEXT NOT NULL, canonical_sha256 TEXT NOT NULL, compiler_version TEXT NOT NULL
);
CREATE TABLE policy_rule (
 rule_id TEXT NOT NULL, revision_id TEXT NOT NULL REFERENCES policy_revision(revision_id),
 principal_id TEXT REFERENCES principal(principal_id), action TEXT NOT NULL CHECK(action IN ('ALLOW','DENY')),
 layer TEXT NOT NULL CHECK(layer IN ('EMERGENCY','OVERRIDE','THREAT','USER')),
 network TEXT NOT NULL CHECK(network IN ('ANY','WIFI','MOBILE','VPN','LAN','TETHER')),
 destination_type TEXT NOT NULL CHECK(destination_type IN ('ANY','IP','CIDR','DOMAIN_SUFFIX')),
 destination_value TEXT, protocol TEXT NOT NULL CHECK(protocol IN ('ANY','TCP','UDP','ICMP','ICMPV6')),
 port_from INTEGER,port_to INTEGER,priority INTEGER NOT NULL DEFAULT 0, enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
 schedule_json TEXT, expires_ms INTEGER,
 PRIMARY KEY(revision_id,rule_id),
 CHECK((destination_type='ANY' AND destination_value IS NULL) OR (destination_type<>'ANY' AND destination_value IS NOT NULL)),
 CHECK((port_from IS NULL AND port_to IS NULL) OR (port_from IS NOT NULL AND port_to IS NOT NULL AND port_from>=1 AND port_to<=65535 AND port_from<=port_to AND protocol IN ('TCP','UDP')))
);
CREATE INDEX rules_by_principal ON policy_rule(principal_id,revision_id);
CREATE TABLE runtime_state (
 singleton INTEGER PRIMARY KEY CHECK(singleton=1), desired_revision TEXT REFERENCES policy_revision(revision_id),
 applied_revision TEXT REFERENCES policy_revision(revision_id), verified_hash TEXT,
 health TEXT NOT NULL CHECK(health IN ('OFF','ACTIVE','DEGRADED','UNKNOWN')), updated_ms INTEGER NOT NULL
);
CREATE TABLE apply_transaction (
 transaction_id TEXT PRIMARY KEY, from_revision TEXT REFERENCES policy_revision(revision_id),
 to_revision TEXT NOT NULL REFERENCES policy_revision(revision_id),
 phase TEXT NOT NULL CHECK(phase IN ('PREPARED','APPLYING','VERIFIED','COMMITTED','ROLLED_BACK','FAILED')),
 created_ms INTEGER NOT NULL, updated_ms INTEGER NOT NULL, helper_receipt_json TEXT, error_code TEXT
);
CREATE TABLE temporary_override (
 override_id TEXT PRIMARY KEY, revision_id TEXT NOT NULL REFERENCES policy_revision(revision_id),
 rule_id TEXT NOT NULL, created_ms INTEGER NOT NULL, expires_ms INTEGER NOT NULL,
 boot_id TEXT NOT NULL, expires_elapsed_ms INTEGER NOT NULL, overrides_threat INTEGER NOT NULL CHECK(overrides_threat IN (0,1)),
 FOREIGN KEY(revision_id,rule_id) REFERENCES policy_rule(revision_id,rule_id), CHECK(expires_ms>created_ms)
);
CREATE TABLE capability_snapshot (
 snapshot_id TEXT PRIMARY KEY, boot_id TEXT NOT NULL, recorded_ms INTEGER NOT NULL,
 probe_version TEXT NOT NULL, capabilities_json TEXT NOT NULL
);
CREATE TABLE consent (
 consent_id TEXT PRIMARY KEY, scope TEXT NOT NULL, scope_json TEXT NOT NULL,
 granted_ms INTEGER NOT NULL, revoked_ms INTEGER, policy_version TEXT NOT NULL,
 CHECK(revoked_ms IS NULL OR revoked_ms>=granted_ms)
);
CREATE TABLE wizard_draft (
 draft_id TEXT PRIMARY KEY, step TEXT NOT NULL, draft_json TEXT NOT NULL, updated_ms INTEGER NOT NULL
);
CREATE TABLE audit_event (
 audit_id TEXT PRIMARY KEY, time_ms INTEGER NOT NULL, actor TEXT NOT NULL, event_type TEXT NOT NULL,
 subject_id TEXT, details_json TEXT NOT NULL
);
CREATE INDEX audit_by_time ON audit_event(time_ms);
CREATE TABLE outbox (
 job_id TEXT PRIMARY KEY, kind TEXT NOT NULL, payload_json TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('PENDING','RUNNING','DONE','FAILED')),
 attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0), created_ms INTEGER NOT NULL, next_attempt_ms INTEGER NOT NULL
);
