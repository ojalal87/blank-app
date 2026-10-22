# Database data dictionary

Generated from the actual v1 fixture databases. NULL indicates unavailable/optional, never an implicit zero.

## control.db — 15 tables

### app_installation

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| app_id | TEXT | yes | 1 |
| user_serial | INTEGER | yes | — |
| package_name | TEXT | yes | — |
| install_generation | TEXT | yes | — |
| signer_lineage_sha256 | TEXT | yes | — |
| installed_ms | INTEGER | yes | — |
| removed_ms | INTEGER | no | — |

### app_version

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| app_id | TEXT | yes | 1 |
| version_code | INTEGER | yes | — |
| observed_ms | INTEGER | yes | 2 |
| signer_sha256 | TEXT | yes | — |
| permissions_json | TEXT | yes | — |

### apply_transaction

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| transaction_id | TEXT | yes | 1 |
| from_revision | TEXT | no | — |
| to_revision | TEXT | yes | — |
| phase | TEXT | yes | — |
| created_ms | INTEGER | yes | — |
| updated_ms | INTEGER | yes | — |
| helper_receipt_json | TEXT | no | — |
| error_code | TEXT | no | — |

### audit_event

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| audit_id | TEXT | yes | 1 |
| time_ms | INTEGER | yes | — |
| actor | TEXT | yes | — |
| event_type | TEXT | yes | — |
| subject_id | TEXT | no | — |
| details_json | TEXT | yes | — |

### capability_snapshot

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| snapshot_id | TEXT | yes | 1 |
| boot_id | TEXT | yes | — |
| recorded_ms | INTEGER | yes | — |
| probe_version | TEXT | yes | — |
| capabilities_json | TEXT | yes | — |

### consent

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| consent_id | TEXT | yes | 1 |
| scope | TEXT | yes | — |
| scope_json | TEXT | yes | — |
| granted_ms | INTEGER | yes | — |
| revoked_ms | INTEGER | no | — |
| policy_version | TEXT | yes | — |

### outbox

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| job_id | TEXT | yes | 1 |
| kind | TEXT | yes | — |
| payload_json | TEXT | yes | — |
| state | TEXT | yes | — |
| attempts | INTEGER | yes | — |
| created_ms | INTEGER | yes | — |
| next_attempt_ms | INTEGER | yes | — |

### policy_revision

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| revision_id | TEXT | yes | 1 |
| profile_id | TEXT | yes | — |
| parent_revision_id | TEXT | no | — |
| created_ms | INTEGER | yes | — |
| policy_json | TEXT | yes | — |
| canonical_sha256 | TEXT | yes | — |
| compiler_version | TEXT | yes | — |

### policy_rule

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| rule_id | TEXT | yes | 2 |
| revision_id | TEXT | yes | 1 |
| principal_id | TEXT | no | — |
| action | TEXT | yes | — |
| layer | TEXT | yes | — |
| network | TEXT | yes | — |
| destination_type | TEXT | yes | — |
| destination_value | TEXT | no | — |
| protocol | TEXT | yes | — |
| port_from | INTEGER | no | — |
| port_to | INTEGER | no | — |
| priority | INTEGER | yes | — |
| enabled | INTEGER | yes | — |
| schedule_json | TEXT | no | — |
| expires_ms | INTEGER | no | — |

### principal

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| principal_id | TEXT | yes | 1 |
| user_serial | INTEGER | yes | — |
| android_uid | INTEGER | yes | — |
| valid_from_ms | INTEGER | yes | — |
| valid_until_ms | INTEGER | no | — |
| attribution | TEXT | yes | — |

### principal_member

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| principal_id | TEXT | yes | 1 |
| app_id | TEXT | yes | 2 |
| valid_from_ms | INTEGER | yes | 3 |
| valid_until_ms | INTEGER | no | — |

### profile

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| profile_id | TEXT | yes | 1 |
| name | TEXT | yes | — |
| mode | TEXT | yes | — |
| default_action | TEXT | yes | — |
| behavior_response | TEXT | yes | — |
| failure_mode | TEXT | yes | — |
| created_ms | INTEGER | yes | — |

### runtime_state

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| singleton | INTEGER | yes | 1 |
| desired_revision | TEXT | no | — |
| applied_revision | TEXT | no | — |
| verified_hash | TEXT | no | — |
| health | TEXT | yes | — |
| updated_ms | INTEGER | yes | — |

### temporary_override

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| override_id | TEXT | yes | 1 |
| revision_id | TEXT | yes | — |
| rule_id | TEXT | yes | — |
| created_ms | INTEGER | yes | — |
| expires_ms | INTEGER | yes | — |
| boot_id | TEXT | yes | — |
| expires_elapsed_ms | INTEGER | yes | — |
| overrides_threat | INTEGER | yes | — |

### wizard_draft

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| draft_id | TEXT | yes | 1 |
| step | TEXT | yes | — |
| draft_json | TEXT | yes | — |
| updated_ms | INTEGER | yes | — |

## telemetry.db — 13 tables

### aggregate_bucket

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| principal_id | TEXT | yes | 1 |
| context_key | TEXT | yes | 2 |
| source | TEXT | yes | 3 |
| bucket_ms | INTEGER | yes | 4 |
| resolution_s | INTEGER | yes | 5 |
| tx_bytes | INTEGER | no | — |
| rx_bytes | INTEGER | no | — |
| connections | INTEGER | no | — |
| blocked | INTEGER | no | — |
| completeness | REAL | yes | — |
| dropped_events | INTEGER | yes | — |

### anomaly_assessment

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| assessment_id | TEXT | yes | 1 |
| window_id | TEXT | yes | — |
| model_snapshot_id | TEXT | no | — |
| scorer_version | TEXT | yes | — |
| anomaly_score | REAL | no | — |
| risk_score | REAL | no | — |
| data_confidence | REAL | yes | — |
| malware_probability | REAL | no | — |
| calibration_id | TEXT | no | — |
| reason_codes_json | TEXT | yes | — |
| assessed_ms | INTEGER | yes | — |

### collection_session

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| session_id | TEXT | yes | 1 |
| boot_id | TEXT | yes | — |
| sensor_id | TEXT | yes | — |
| started_ms | INTEGER | yes | — |
| source_version | TEXT | yes | — |
| capabilities_json | TEXT | yes | — |

### dns_observation

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| observation_id | TEXT | yes | 1 |
| principal_id | TEXT | no | — |
| observed_ms | INTEGER | yes | — |
| expires_ms | INTEGER | yes | — |
| domain_ascii | TEXT | yes | — |
| answer_ip | BLOB | yes | — |
| source | TEXT | yes | — |
| confidence | REAL | yes | — |

### evidence

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| evidence_id | TEXT | yes | 1 |
| incident_id | TEXT | yes | — |
| assessment_id | TEXT | no | — |
| kind | TEXT | yes | — |
| observed_ms | INTEGER | yes | — |
| immutable_snapshot_json | TEXT | yes | — |

### feature_window

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| window_id | TEXT | yes | 1 |
| principal_id | TEXT | yes | — |
| context_key | TEXT | yes | — |
| start_ms | INTEGER | yes | — |
| end_ms | INTEGER | yes | — |
| feature_schema_version | INTEGER | yes | — |
| features_json | TEXT | yes | — |
| missing_features_json | TEXT | yes | — |
| completeness | REAL | yes | — |
| attribution_confidence | REAL | yes | — |
| training_state | TEXT | yes | — |

### feedback

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| feedback_id | TEXT | yes | 1 |
| incident_id | TEXT | yes | — |
| value | TEXT | yes | — |
| scope_json | TEXT | yes | — |
| created_ms | INTEGER | yes | — |

### flow_event

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| event_id | TEXT | yes | 1 |
| session_id | TEXT | yes | — |
| boot_id | TEXT | yes | — |
| sensor_id | TEXT | yes | — |
| sequence | INTEGER | yes | — |
| time_ms | INTEGER | yes | — |
| elapsed_ms | INTEGER | yes | — |
| principal_id | TEXT | no | — |
| app_id | TEXT | no | — |
| attribution_confidence | REAL | yes | — |
| ip_family | INTEGER | yes | — |
| remote_ip | BLOB | yes | — |
| remote_port | INTEGER | no | — |
| protocol | TEXT | yes | — |
| network_type | TEXT | yes | — |
| interface_name | TEXT | no | — |
| direction | TEXT | yes | — |
| tx_bytes | INTEGER | no | — |
| rx_bytes | INTEGER | no | — |
| decision | TEXT | yes | — |
| rule_id | TEXT | no | — |
| revision_id | TEXT | no | — |
| sampling_probability | REAL | no | — |

### incident

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| incident_id | TEXT | yes | 1 |
| principal_id | TEXT | no | — |
| first_seen_ms | INTEGER | yes | — |
| last_seen_ms | INTEGER | yes | — |
| severity | TEXT | yes | — |
| status | TEXT | yes | — |
| dedupe_key | TEXT | yes | — |
| summary | TEXT | yes | — |

### model_snapshot

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| snapshot_id | TEXT | yes | 1 |
| principal_id | TEXT | yes | — |
| context_key | TEXT | yes | — |
| model_type | TEXT | yes | — |
| model_version | TEXT | yes | — |
| feature_schema_version | INTEGER | yes | — |
| state_json | TEXT | yes | — |
| state_sha256 | TEXT | yes | — |
| trained_through_ms | INTEGER | yes | — |
| observations | INTEGER | yes | — |
| created_ms | INTEGER | yes | — |
| state | TEXT | yes | — |

### response_action

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| action_id | TEXT | yes | 1 |
| incident_id | TEXT | yes | — |
| action_type | TEXT | yes | — |
| policy_revision_id | TEXT | no | — |
| expires_ms | INTEGER | no | — |
| created_ms | INTEGER | yes | — |
| status | TEXT | yes | — |

### scan_finding

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| finding_id | TEXT | yes | 1 |
| scan_id | TEXT | yes | — |
| artifact_sha256 | TEXT | no | — |
| redacted_location | TEXT | yes | — |
| verdict | TEXT | yes | — |
| evidence_json | TEXT | yes | — |

### scan_job

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| scan_id | TEXT | yes | 1 |
| consent_id | TEXT | yes | — |
| scope_token | TEXT | yes | — |
| engine_id | TEXT | no | — |
| state | TEXT | yes | — |
| started_ms | INTEGER | no | — |
| ended_ms | INTEGER | no | — |

## intel.db — 5 tables

### feed_generation

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| generation_id | TEXT | yes | 1 |
| source_id | TEXT | yes | — |
| fetched_ms | INTEGER | yes | — |
| upstream_version | TEXT | no | — |
| etag | TEXT | no | — |
| payload_sha256 | TEXT | yes | — |
| signature_status | TEXT | yes | — |
| entry_count | INTEGER | yes | — |
| expires_ms | INTEGER | no | — |
| state | TEXT | yes | — |

### feed_source

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| source_id | TEXT | yes | 1 |
| name | TEXT | yes | — |
| homepage | TEXT | yes | — |
| adapter_id | TEXT | yes | — |
| category | TEXT | yes | — |
| enabled | INTEGER | yes | — |
| license_state | TEXT | yes | — |
| terms_url | TEXT | yes | — |
| terms_checked_ms | INTEGER | no | — |
| credential_alias | TEXT | no | — |

### feed_update_attempt

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| attempt_id | TEXT | yes | 1 |
| source_id | TEXT | yes | — |
| started_ms | INTEGER | yes | — |
| ended_ms | INTEGER | no | — |
| status | TEXT | yes | — |
| error_code | TEXT | no | — |
| redacted_details_json | TEXT | yes | — |

### indicator

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| indicator_id | TEXT | yes | 1 |
| kind | TEXT | yes | — |
| normalized_value | TEXT | yes | — |
| ip_family | INTEGER | no | — |
| network_bytes | BLOB | no | — |
| prefix_length | INTEGER | no | — |

### indicator_provenance

| Column | SQLite type | Required | PK position |
|---|---|---|---|
| indicator_id | TEXT | yes | 1 |
| generation_id | TEXT | yes | 2 |
| category | TEXT | yes | — |
| first_seen_ms | INTEGER | no | — |
| last_seen_ms | INTEGER | no | — |
| expires_ms | INTEGER | no | — |
| upstream_confidence | REAL | no | — |

