# Changelog

## v0.4.0-rc3 — Security Center
- **Security scan** (Tools → Protection): checks all installed apps against the public Echap stalkerware
  indicator list (package names and signing certificates, bundled; updated only when you tap Update) and
  flags risky capability combinations (banking-malware pattern, hidden apps with spying powers,
  accessibility / notification access / device admin, sideloaded SMS access, legacy permission model).
  Each finding is explained, with actions that open Android's own screens.
- **"Who can watch you"**: every app with accessibility, notification access or device admin.
- **Firewall** (no root): local VPN that blocks internet only for the apps you choose; other apps never
  pass through it.
- Honest limits stated in the UI: no app can see other apps' code on Android; Play Protect stays on.
- `QUERY_ALL_PACKAGES` re-added for the security scan (CYBERSECURITY_AUDIT.md SEC-32…37).

## v0.4.0-rc2 — System Tuner (Shizuku)
- New **System Tuner**: OxygenOS fixes (keep chosen apps alive in background, Android 14 child-process
  limit, Private DNS presets incl. ad-blocking), preinstalled-app removal with risk tiers and one-tap
  restore, display & animations (animation speed, pinned refresh rate, bounded screen density),
  and a Changes & undo screen with Undo all.
- Shizuku setup guide (English/Arabic) inside the app.
- Permission Center explains Android 13+ "restricted settings" for sideloaded installs.
- Security: typed, service-side-validated operations; see CYBERSECURITY_AUDIT.md SEC-26…31.

## v0.4.0-rc1 — Audit, hardening and redesign

### Architecture
- New platform-free `:core` module holding every rule, policy and file-system mutation contract, with a
  JVM test suite. `:app` is now adapters + ViewModels + Compose.
- Manual DI (`AppGraph`), activity-scoped ViewModels with `StateFlow`; state survives rotation and tab
  switches. Version catalog; Gradle 8.14.3 wrapper; Kotlin `compilerOptions` DSL (v0.3's `kotlinOptions`
  fails with Kotlin 2.2+).

### Safety and security (see CYBERSECURITY_AUDIT.md)
- Safe Trash rewritten as a write-ahead journaled store: crash recovery, orphan adoption, no truncation,
  exclusive-create restore that never overwrites, path policy on every source/target, one lock for all
  operations.
- Duplicate cleaner: inode-level de-duplication, byte-for-byte verification, keep-one guard enforced in
  the domain layer, hashing budgets and cancellation.
- Android 8–10 no longer permanently deletes media behind a "trash" label.
- Shizuku UserService: caller-UID check, enum-only diagnostics with fixed argv, output caps and scrubbing.
- Removed `QUERY_ALL_PACKAGES`; network security config (no cleartext, system CAs only); backup limited to
  settings; private notifications; immutable PendingIntents; MediaStore URI validation.
- Diagnostic export minimized and opt-in per section; no addresses, names or identifiers.

### Features
- Explainable rule engine: 19 rules, each with evidence, expected benefit, risk, reversibility, required
  permission and action. Labelled "rule-based, not AI".
- Health state (Healthy / Attention / Critical) with named factors replaces the arbitrary 0–100 score.
- Smart Scan with real stages, progress, cancellation and the four result groups.
- Layered Network Doctor (link, gateway, DNS, HTTPS internet, validation, VPN, Private DNS) with verdicts
  and advice; probes only on demand.
- Battery & Thermal: health, power source, voltage, OEM-dependent current, cycle count (Android 14+),
  thermal status and headroom, all with unavailable/OEM labelling.
- App Doctor: per-package StorageStats, five distinct metrics, search, sort, system filter, findings filter.
- Privacy Doctor: screen lock, patch age, developer options, USB debugging, apps by sensitive permission,
  unused apps holding sensitive permissions.
- Before/After session that reports only bytes the app measured while permanently deleting.
- Trash auto-expiry (opt-in, 7–90 days, foreground only), scan exclusions, large-file size/age filters,
  maintenance history filters, notification levels, Permission Center, first-run capability detection,
  per-app language.
- OnePlus / OxygenOS (and ColorOS/realme) detection with guidance using documented Settings actions only.
- Scheduled scan now requires charging and skips when the device is throttling.
- v0.3 data migration (history, Safe Trash metadata, daily-scan setting).

### UI
- Material 3 AMOLED redesign, 4-tab IA with navigation rail on tablets, onboarding, consistent empty /
  loading / error / permission states, accessibility semantics, English + Arabic (RTL) with 624 strings.

### Removed
- RAM delta in Before/After, "Boost" wording, health score, `deleteMediaDirect`, remote-permission probing,
  `ACCESS_WIFI_STATE`.

## v0.3.0 — Product-Ready Foundation
See `01_CURRENT_v0.3/CHANGELOG.md` in the handover package.
