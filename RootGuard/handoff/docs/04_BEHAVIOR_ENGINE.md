# Behavioral analytics specification
## Objective
Detect unexpected metadata patterns early and estimate operational risk; do not promise prediction of unseen attacks before any observable evidence. Distinguish a traffic-volume forecast from probability of malware. No model can guarantee prevention of the first harmful request.

## Features v1 (versioned)
Per principal in 5-minute windows: tx/rx bytes, connection count, distinct IPs, observed DNS-domain count, new-destination fraction, blocked/failure fractions, foreground/screen context and collector completeness. Periodicity requires actual per-connection timestamps across sufficient history; unavailable in coarse counters. File activity/access is an optional separately probed sensor, not inferred from upload timing. Persist only aggregated read/change metadata for approved scan scopes, never private contents by default.
Compare against contextual baselines by principal and feature schema, coarse local-hour bucket, weekday/weekend, network type and foreground/screen state. Store timezone version/context; wall-clock shifts must not create rate spikes. Avoid sparse Cartesian explosion: fallback exact context -> principal/network -> principal global -> insufficient history. No individual baseline borrowed as fact from unrelated apps.

## Pipeline
1. Validate event provenance, dedupe, counter deltas, principal mapping and quality.
2. Finalize bounded window with watermark; lateness allowance initial 60 seconds. Persist late data but do not silently change previously actioned result; produce a versioned correction if needed.
3. Fetch prior model snapshot with trained_through strictly before window start.
4. Score current features before learning; persist score + explanations + snapshot ID.
5. Correlate threat provenance, independent behavior evidence and capability coverage.
6. Apply deterministic response policy; emit notification/recommendation.
7. Train only eligible clean windows; otherwise quarantine. Commit checkpoint and watermark idempotently.

## Baseline and statistics
Initial 7–14 day learning target, but minimum observations and contextual coverage govern readiness. Do not imply clean history is known merely because the user installed the app. During cold start keep known-threat enforcement; no behavioral auto-block.
Robust feature deviation z = abs(x - median(history)) / max(1.4826*MAD(history), feature_scale_floor). Use log1p on heavy-tailed count/byte features. Floors are per-feature, validated on data. MAD=0 is handled with floor; constant history never produces division by zero. Unavailable features remain missing and reduce coverage.
EWMA uses prior-only state with versioned alpha; detect gradual trend against calibrated residual distributions. ADWIN/change-point methods are optional after benchmark; drift alert is not proof of benign change. Keep short and long baselines so gradual attacker behavior cannot freely redefine normal.
Multivariate streaming model such as Half-Space Trees is phase 2. Normalize on past-only distributions, freeze updates for suspicious windows, log model version and deterministic seed. A model artifact must include feature order, scales, training watermark, digest and supported schema.

## Outputs
anomaly_score 0..100 is a detector ranking; risk_score 0..100 is an ordinal policy heuristic; confidence 0..1 describes data sufficiency/coverage, NOT probability of correctness or infection. Probability output is null until trained with representative labeled held-out data and calibrated on independent time splits; include horizon and target definition if forecasting a future event. Rare baseline percentile/p-value is not P(malware|evidence).
Prototype behavior reference in reference/behavior_reference.py deliberately uses only one upload feature. Its thresholds and weights are illustrative, not a validated production engine. Full Android design requires multi-feature correlation and feature-specific floors.

## Anti-contamination
Quarantine anomalous windows, known IOC matches, attribution failures and incomplete windows. User 'expected' feedback applies narrowly and does not whitelist an entire domain/app forever. Review released windows before training. On app update create baseline epoch/shadow model; retain previous baseline to compare and explain changes. Detect training starvation and surface it. Store model snapshots and rollback; do not learn unboundedly from newly installed potentially malicious apps.

## Decision policy
Observe mode never applies behavioral blocks. Balanced: low-confidence anomaly logs; adequate-confidence repeated independent indicators notify; high-confidence threat matches may block under enabled feed policy. Strict: user-approved reversible containment with short expiry and bounded scope, only when data and policy gates pass. Never auto-uninstall, delete files or kill system processes based on anomaly alone. Long-lived TLS, HTTP/2, QUIC or telemetry batching may look periodic; these are review indicators only.

## Evaluation
Chronological train/calibration/test split by device and application; never random overlapping windows. Split across devices/apps for generalization claims. Include backup, app updates, travel/timezone, captive portal, VPN, streaming, gaming, cloud sync and network outages as benign cases. Safe synthetic scenarios: burst to local test endpoint, periodic local traffic, destination churn, gradual ramp and collector dropout. Report precision/recall with class prevalence, false alerts/device/day, containment mistakes, detection delay and bytes sent before detection. If claiming probability add reliability diagram, Brier score/calibration error and drift monitoring. Avoid universal accuracy claims from synthetic data.
