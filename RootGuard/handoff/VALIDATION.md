# Validation report — 2026-10-22

Status: architecture/SQL/reference bundle verified locally, not a built Android app.

- 16 Python unittest cases passed.
- Three actual SQLite schema fixtures created; integrity_check OK and foreign_key_check empty.
- Schema tests cover UID reuse, rule ports/FKs, source activation, indicator provenance, duplicate windows and uncalibrated probability rejection.
- Statistical tests cover constant history, burst, cold start, missing data, incomplete windows, no baseline mutation, EWMA, invalid values and counter reset.
- Example JSON parsed successfully. IPC JSON schema is an envelope contract, not full per-operation validation.
- Kotlin contracts have NOT been compiled; no Kotlin compiler or Android project/build was supplied.
- No Android device, root helper, kernel rules, network sensor, scanner engine, feed downloader, production encrypted DB or UI has been implemented/tested here.
- No third-party feeds or real user telemetry are included. All examples are synthetic; the three databases contain zero data rows.
- Accuracy, battery usage and prediction quality have not been measured.

Run again: `python3 tests/test_handoff.py` from extracted package.

## Test output
```
test_cold_start_and_incomplete (__main__.BehaviorTests.test_cold_start_and_incomplete) ... ok
test_constant_history_stable_and_burst (__main__.BehaviorTests.test_constant_history_stable_and_burst) ... ok
test_counter_reset (__main__.BehaviorTests.test_counter_reset) ... ok
test_ewma (__main__.BehaviorTests.test_ewma) ... ok
test_invalid_input (__main__.BehaviorTests.test_invalid_input) ... ok
test_known_ioc_survives_cold_start (__main__.BehaviorTests.test_known_ioc_survives_cold_start) ... ok
test_missing_is_not_zero (__main__.BehaviorTests.test_missing_is_not_zero) ... ok
test_score_does_not_train (__main__.BehaviorTests.test_score_does_not_train) ... ok
test_active_uid_reuse_requires_close (__main__.SchemaTests.test_active_uid_reuse_requires_close) ... ok
test_example_json (__main__.SchemaTests.test_example_json) ... ok
test_failed_feed_not_active_and_single_generation (__main__.SchemaTests.test_failed_feed_not_active_and_single_generation) ... ok
test_indicator_provenance_keeps_sources (__main__.SchemaTests.test_indicator_provenance_keeps_sources) ... ok
test_no_fake_probability_without_calibration (__main__.SchemaTests.test_no_fake_probability_without_calibration) ... ok
test_rule_fk_and_port_checks (__main__.SchemaTests.test_rule_fk_and_port_checks) ... ok
test_schema_integrity (__main__.SchemaTests.test_schema_integrity) ... ok
test_window_duplicate_and_missing_quality (__main__.SchemaTests.test_window_duplicate_and_missing_quality) ... ok

----------------------------------------------------------------------
Ran 16 tests in 0.015s

OK
```
