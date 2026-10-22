# Milestones and release gates
## M0 — integrate skeleton
Inventory existing repo; define min/target SDK and device matrix; pin compatible dependencies after current documentation review. Compile empty navigation + domain + DB modules. Map logical schema to Room with schema exports; test FK/index/enum equivalence and migrations. Deliver build evidence, not screenshots only.

## M1 — real root firewall vertical slice
Probe device; enumerate app installations/principals; edit policy; compile preview; apply own rules; read back both families; persist verified revision; restore/recover. Test dedicated owner-controlled Android test device. Root missing/dead/denied yields honest errors. Never operate host/container firewall.
Acceptance: app Wi-Fi/mobile decisions, IPv4/IPv6, shared UID warning, UID reuse, interface switch, reboot, UI/helper death, crash mid-apply, rollback and coexistence. Netd chains unchanged except documented own hooks. Unsupported VPN/tethering clearly labeled. Counter/session semantics verified.

## M2 — metadata pipeline and history
Implement only verified sensors, event sequence dedupe, overflow metrics, attribution confidence, counter reset detection, missingness and retention. Event rate load test; no unbounded DB growth. Hourly rollups upsert on (principal,context,bucket,source), never double-count retries or overlapping sensors. Usage UI distinguishes measured vs sampled.

## M3 — statistics and learning
Port reference median/MAD/EWMA concepts to Kotlin with golden fixtures. Implement multi-feature past-only baselines, sparse-context fallback, readiness, quarantine, drift review, explainable incident dedupe and model rollback. Start in observe mode. Score before learn, exactly-once checkpoints or retry-idempotent state. No fake probability. Tests include missing/all-zero/sparse history, timezone/boot resets, update transition, contaminated baseline and benign bursts.

## M4 — feed lifecycle
Adapter registry with licenses/keys, bounded parser, provenance, snapshot activation, local matching, rollback and stale-source UI. Tests: malformed/huge/empty/stale feeds, redirects, auth redaction, mid-update crash, CIDR boundaries, IDNA suffix boundaries, shared-host URL cases. Network tests use fixtures; live endpoints only for compatibility verification with provider limits.

## M5 — wizard and real response
Arabic RTL/English, accessible UI, capability-driven settings, saved drafts, preview/apply/recovery and scoped exceptions. Behavior policy only applies allowed deterministic bounded actions; observe mode cannot contain. Strict requires consent; score-only never deletes or uninstalls. Test feedback effects, overrides, emergency mode and monotonic expiry after reboot/clock change.

## M6 — evaluation and packaging
Chronological evaluation; battery/CPU/disk on reference devices, false alerts/day, known-threat fixture detection, time/bytes to detect, containment false positives and recovery success. Static/lint/dependency audit and IPC/parser tests. Optional scanner ships only with actual engine and evidence. APK signing secrets kept outside repo. Deliver source, build instructions, test report, limitations, debug/release APK status and licenses/SBOM.

## Initial acceptance goals (not claims)
No policy loss in migrations; deterministic policy conflict resolution; unknown telemetry never shown as clean; no arbitrary root IPC; no global rule flush; no unauthorized file/cloud access; recovery available offline; every applied action auditable with previous/current revision. Set performance/accuracy numerical release targets only after baseline device measurements.
