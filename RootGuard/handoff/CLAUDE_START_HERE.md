# Implementation mandate for Claude
You are implementing RootGuard, an owner-authorized Android root firewall and local behavioral security application. Read all files in docs/ and database/ before editing. This package is an architecture handoff, not an already implemented Android application.

If an existing codebase is supplied: inventory modules, build tooling, permissions, root library, database versions and implemented features. Produce a gap map. Preserve working code and existing user data. Do not replace the application or adopt a second UI framework without concrete justification.

## Product requirements
- Root enforcement with per-principal policies, IPv4/IPv6, network/interface awareness, temporary exceptions, LAN/VPN/tethering capability reporting.
- Local traffic metadata analysis, contextual history, statistical anomaly detection, threat intelligence correlation, explainable recommendations.
- Arabic RTL and English wizard, modes, troubleshooting and emergency recovery.
- Optional, explicit local file/APK scanning interfaces, no fabricated antivirus verdicts. File scanning is a later milestone unless an existing engine is already working.
- Capability detection and honest degraded states on each kernel/ROM. Root alone is not proof of compatibility.

## First response and workflow
1. Read this package and audit the existing repository if present.
2. State concrete implementation milestones and repository-specific decisions in IMPLEMENTATION_STATUS.md. Start implementing milestone 1 in the same turn.
3. Choose and pin currently compatible Android/Kotlin/Gradle/Room/NDK/root library versions after checking official sources. Proposed minSdk 29 is a starting product scope, not an assertion that every API is available there. Record the supported device matrix.
4. Use Kotlin/Compose, Coroutines/Flow, Room and a narrowly scoped root helper. Kotlin contracts here are reference interfaces; add the exact serialization/IPC glue and Gradle configuration appropriate to the repo.
5. Translate SQL to Room entities/DAOs with exported schemas and migration tests. Raw SQL here is the authoritative logical v1 schema; SQLite files are fixtures, not Room prepackaged production databases.
6. Build a real vertical slice before expanding features. Do not declare a mock backend, counter, feed or detector to be working protection.
7. Never execute host firewall commands on the development container. Real root tests require an owner-authorized Android test device/emulator with the necessary kernel features.
8. Keep precise build/test evidence, unsupported cases and remaining work. Generate APK only after successful build; label debug/release accurately. Never invent device results.

## Hard engineering constraints
- No arbitrary command execution IPC, LLM-to-shell path, global chain flush, SELinux disabling, stealth operation, certificate interception by default, or automatic sensitive database harvesting.
- Firewall decisions deterministic. LLM optional for explanations only; validated typed policy proposal, explicit review, ordinary compiler. Core runs offline.
- Separate UID enforcement principal from package identity. Shared UID, isolated processes, UID reuse, profiles and signer rotation require documented handling. Unknown attribution must stay unknown.
- Policy apply is a journaled transaction across DB and kernel with compensation, boot reconciliation and last-known-good recovery; never claim SQLite gives atomic kernel updates.
- IPv6, encrypted DNS visibility, shared IP/CDN collateral blocking, netd coexistence, VPN/tethering and counter resets must be handled explicitly.
- Baseline contamination, missing data and cold start are first-class states. Score before learning, quarantine suspicious windows, separate anomaly/risk/confidence.
- Thresholds and retention in this package are initial configurable engineering defaults, not validated security accuracy claims.
- No destructive DB migration fallback. No credentials in source, URLs in logs containing API keys, or unreviewed redistribution of feeds.

## Acceptance
Use docs/07_IMPLEMENTATION_ACCEPTANCE.md as release gates. Implement scoped tests, run build/static checks and device scenarios where possible, update evidence. Complete work autonomously within these requirements; request only essential missing input such as a test device or existing project files. Never use live malware to validate the initial implementation.
