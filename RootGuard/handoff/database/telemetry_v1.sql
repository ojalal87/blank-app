PRAGMA foreign_keys=ON;
PRAGMA user_version=1;
CREATE TABLE collection_session (
 session_id TEXT PRIMARY KEY, boot_id TEXT NOT NULL, sensor_id TEXT NOT NULL, started_ms INTEGER NOT NULL,
 source_version TEXT NOT NULL, capabilities_json TEXT NOT NULL
);
CREATE TABLE flow_event (
 event_id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES collection_session(session_id),
 boot_id TEXT NOT NULL, sensor_id TEXT NOT NULL, sequence INTEGER NOT NULL CHECK(sequence>=0),
 time_ms INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL CHECK(elapsed_ms>=0),
 principal_id TEXT, app_id TEXT, attribution_confidence REAL NOT NULL CHECK(attribution_confidence BETWEEN 0 AND 1),
 ip_family INTEGER NOT NULL CHECK(ip_family IN (4,6)), remote_ip BLOB NOT NULL,
 remote_port INTEGER CHECK(remote_port BETWEEN 0 AND 65535), protocol TEXT NOT NULL,
 network_type TEXT NOT NULL, interface_name TEXT, direction TEXT NOT NULL CHECK(direction IN ('IN','OUT','FORWARD')),
 tx_bytes INTEGER CHECK(tx_bytes>=0), rx_bytes INTEGER CHECK(rx_bytes>=0),
 decision TEXT NOT NULL CHECK(decision IN ('ALLOW','DENY','UNKNOWN')), rule_id TEXT, revision_id TEXT,
 sampling_probability REAL CHECK(sampling_probability>0 AND sampling_probability<=1),
 UNIQUE(boot_id,sensor_id,sequence), CHECK(length(remote_ip)=CASE ip_family WHEN 4 THEN 4 ELSE 16 END)
);
CREATE INDEX flow_principal_time ON flow_event(principal_id,time_ms);
CREATE INDEX flow_time ON flow_event(time_ms);
CREATE TABLE dns_observation (
 observation_id TEXT PRIMARY KEY, principal_id TEXT, observed_ms INTEGER NOT NULL, expires_ms INTEGER NOT NULL,
 domain_ascii TEXT NOT NULL, answer_ip BLOB NOT NULL CHECK(length(answer_ip) IN (4,16)),
 source TEXT NOT NULL, confidence REAL NOT NULL CHECK(confidence BETWEEN 0 AND 1), CHECK(expires_ms>=observed_ms)
);
CREATE INDEX dns_ip_expiry ON dns_observation(answer_ip,expires_ms);
CREATE TABLE aggregate_bucket (
 principal_id TEXT NOT NULL, context_key TEXT NOT NULL, source TEXT NOT NULL, bucket_ms INTEGER NOT NULL,
 resolution_s INTEGER NOT NULL CHECK(resolution_s IN (60,3600)), tx_bytes INTEGER CHECK(tx_bytes>=0), rx_bytes INTEGER CHECK(rx_bytes>=0),
 connections INTEGER CHECK(connections>=0), blocked INTEGER CHECK(blocked>=0),
 completeness REAL NOT NULL CHECK(completeness BETWEEN 0 AND 1), dropped_events INTEGER NOT NULL CHECK(dropped_events>=0),
 PRIMARY KEY(principal_id,context_key,source,bucket_ms,resolution_s)
);
CREATE TABLE feature_window (
 window_id TEXT PRIMARY KEY, principal_id TEXT NOT NULL, context_key TEXT NOT NULL,
 start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, feature_schema_version INTEGER NOT NULL,
 features_json TEXT NOT NULL, missing_features_json TEXT NOT NULL,
 completeness REAL NOT NULL CHECK(completeness BETWEEN 0 AND 1),
 attribution_confidence REAL NOT NULL CHECK(attribution_confidence BETWEEN 0 AND 1),
 training_state TEXT NOT NULL CHECK(training_state IN ('PENDING','ACCEPTED','QUARANTINED','REJECTED')),
 UNIQUE(principal_id,context_key,start_ms,end_ms,feature_schema_version), CHECK(end_ms>start_ms)
);
CREATE INDEX window_principal_time ON feature_window(principal_id,start_ms);
CREATE TABLE model_snapshot (
 snapshot_id TEXT PRIMARY KEY, principal_id TEXT NOT NULL, context_key TEXT NOT NULL,
 model_type TEXT NOT NULL, model_version TEXT NOT NULL, feature_schema_version INTEGER NOT NULL,
 state_json TEXT NOT NULL, state_sha256 TEXT NOT NULL,
 trained_through_ms INTEGER NOT NULL, observations INTEGER NOT NULL CHECK(observations>=0),
 created_ms INTEGER NOT NULL, state TEXT NOT NULL CHECK(state IN ('SHADOW','ACTIVE','RETIRED','QUARANTINED'))
);
CREATE INDEX model_context ON model_snapshot(principal_id,context_key,trained_through_ms);
CREATE TABLE anomaly_assessment (
 assessment_id TEXT PRIMARY KEY, window_id TEXT NOT NULL REFERENCES feature_window(window_id),
 model_snapshot_id TEXT REFERENCES model_snapshot(snapshot_id), scorer_version TEXT NOT NULL,
 anomaly_score REAL CHECK(anomaly_score BETWEEN 0 AND 100), risk_score REAL CHECK(risk_score BETWEEN 0 AND 100),
 data_confidence REAL NOT NULL CHECK(data_confidence BETWEEN 0 AND 1),
 malware_probability REAL CHECK(malware_probability BETWEEN 0 AND 1), calibration_id TEXT,
 reason_codes_json TEXT NOT NULL, assessed_ms INTEGER NOT NULL,
 UNIQUE(window_id,scorer_version), CHECK(malware_probability IS NULL OR calibration_id IS NOT NULL)
);
CREATE TABLE incident (
 incident_id TEXT PRIMARY KEY, principal_id TEXT, first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL,
 severity TEXT NOT NULL CHECK(severity IN ('INFO','LOW','MEDIUM','HIGH','CRITICAL')),
 status TEXT NOT NULL CHECK(status IN ('OPEN','ACKNOWLEDGED','RESOLVED','FALSE_POSITIVE')),
 dedupe_key TEXT NOT NULL, summary TEXT NOT NULL, CHECK(last_seen_ms>=first_seen_ms)
);
CREATE UNIQUE INDEX one_open_incident ON incident(dedupe_key) WHERE status IN ('OPEN','ACKNOWLEDGED');
CREATE TABLE evidence (
 evidence_id TEXT PRIMARY KEY, incident_id TEXT NOT NULL REFERENCES incident(incident_id) ON DELETE CASCADE,
 assessment_id TEXT REFERENCES anomaly_assessment(assessment_id) ON DELETE SET NULL,
 kind TEXT NOT NULL, observed_ms INTEGER NOT NULL, immutable_snapshot_json TEXT NOT NULL
);
CREATE TABLE response_action (
 action_id TEXT PRIMARY KEY, incident_id TEXT NOT NULL REFERENCES incident(incident_id),
 action_type TEXT NOT NULL CHECK(action_type IN ('LOG','NOTIFY','TEMP_BLOCK','RECOMMEND')),
 policy_revision_id TEXT, expires_ms INTEGER, created_ms INTEGER NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('PROPOSED','APPLIED','REVERTED','FAILED')),
 CHECK(action_type<>'TEMP_BLOCK' OR expires_ms IS NOT NULL)
);
CREATE TABLE feedback (
 feedback_id TEXT PRIMARY KEY, incident_id TEXT NOT NULL REFERENCES incident(incident_id),
 value TEXT NOT NULL CHECK(value IN ('EXPECTED','SUSPICIOUS','UNSURE')), scope_json TEXT NOT NULL, created_ms INTEGER NOT NULL
);
CREATE TABLE scan_job (
 scan_id TEXT PRIMARY KEY, consent_id TEXT NOT NULL, scope_token TEXT NOT NULL, engine_id TEXT,
 state TEXT NOT NULL CHECK(state IN ('QUEUED','RUNNING','DONE','CANCELLED','FAILED')), started_ms INTEGER, ended_ms INTEGER
);
CREATE TABLE scan_finding (
 finding_id TEXT PRIMARY KEY, scan_id TEXT NOT NULL REFERENCES scan_job(scan_id) ON DELETE CASCADE,
 artifact_sha256 TEXT, redacted_location TEXT NOT NULL,
 verdict TEXT NOT NULL CHECK(verdict IN ('KNOWN_MALICIOUS','SUSPICIOUS','NO_KNOWN_MATCH','INCONCLUSIVE')),
 evidence_json TEXT NOT NULL
);
