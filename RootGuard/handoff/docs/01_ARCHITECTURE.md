# Architecture and threat model
## Scope and trust
Protect the consenting owner against risky application networking, suspicious behavioral changes and known malicious destinations. A compromised kernel or a hostile actor with equal root privileges can bypass/modify this product; detect integrity changes where possible, do not claim tamper-proof protection. Root increases the consequence of bugs in this app.

## Module map (proposed Gradle modules)
| Module | Responsibility | Dependencies |
|---|---|---|
| :app | composition, navigation, DI, permission onboarding | feature modules |
| :core:model | immutable IDs, enums, clock and typed outcomes | Kotlin only |
| :core:policy | policy validation, precedence, compile to backend-neutral plan | model |
| :core:root-api | authenticated typed helper protocol, versioning | model |
| :root-helper | capability probes, rule apply/revert, bounded metadata collection | no UI/LLM/feed parser |
| :core:database | Room configuration/telemetry stores, migrations and repositories | model |
| :core:telemetry | bounded ingest, attribution, normalization, aggregation | database, root-api |
| :core:behavior | causal contextual baselines, detectors and explanations | model |
| :core:intelligence | unprivileged feed download, parsing, matching | model, database |
| :core:scanner | explicit file scopes, hashes and optional scanner plugins | model |
| :core:security | secrets, audit redaction, encrypted storage adapter | model |
| :feature:onboarding | capability wizard, preview and connectivity test | repositories |
| :feature:dashboard | health, coverage gaps, evidence and scores | repositories |
| :feature:firewall | app rules, mode and temporary exception editor | policy |
| :feature:activity | event and time series explorer | telemetry |
| :feature:incidents | evidence, feedback and reversible containment | behavior, policy |
| :feature:settings | feeds, retention, export and recovery | repositories |

Dependency rule: domain/model never depend on Android UI, Room or root library. Helper accepts canonical validated plans, not SQL or URLs. Feed parser and ML remain unprivileged. Root library selection must include IPC caller authentication review; a process name or JSON field is not authentication.

## Data flow
Kernel sensor -> typed batch (sequence, boot ID, collection quality) -> bounded unprivileged ingest -> principal mapping -> minute buckets -> context feature window -> baseline snapshot -> score -> evidence -> policy recommendation -> reviewed/enabled action policy -> apply journal -> helper -> read-back -> audit.
Feed updater -> staging file -> parser/limits/license gate -> inactive generation -> normalization -> activate generation transaction -> matching index -> next policy revision if kernel list changes.

## Process/lifecycle
Use a user-visible Android service lifecycle appropriate to current foreground-service policies; WorkManager for deferrable feed maintenance and rollups, never for a continuous packet loop. Verify current targetSdk requirements before choosing service types and boot-start flows. UI process death must not silently remove healthy kernel rules. Helper death preserves current rules and reports stale monitoring. Detect permission/root revocation and backend loss.
Cold boot: persisted minimal policy snapshot available in device-protected storage only if early protection is implemented and tested. No private telemetry or secrets there. Full models and encrypted app data load after unlock. UI distinguishes boot protection unavailable vs active. Early boot coverage requires a tested root-manager boot integration; an ordinary receiver alone is no guarantee.

## Identities
app installation ID is a UUID for (user/profile, package, installation generation); retain signer identity/lineage separately from version history. principal ID is a UUID for a time-bounded UID assignment in a user/profile. A principal may map to several packages. Rules enforce at principal granularity; shared-UID app UI must explain that the choice affects all members. Isolated UID mapping requires a validated sensor; otherwise unknown. No inherited allow on UID reuse.

## Capability contract
Every feature is SUPPORTED / UNSUPPORTED / DENIED / UNVERIFIED with evidence, probe version, boot ID and timestamp. Separate enforcement coverage from monitoring coverage. Probe IPv4/IPv6 owner matching, sets, logging/counters, VPN path, LAN, forwarded tether traffic and optional eBPF. Avoid altering Android netd-owned rules/maps. Android's own eBPF accounting does not imply access to arbitrary per-flow eBPF programs.

## Observability and budgets (initial targets to measure)
Aggregate incrementally, batch DB writes, capped queues, dropped-event counters and coverage ratios. Event storm drops detailed logs before enforcement. Target bounded 64 MiB in-memory telemetry queue/model cache combined; benchmark rather than promise battery percentages. Raw event disk budget default 100 MiB; total telemetry default 300 MiB; keep control policy store isolated from telemetry exhaustion. Record measured idle/active battery and CPU per reference device.
