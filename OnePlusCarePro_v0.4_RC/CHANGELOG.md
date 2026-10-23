# Changelog

## v0.4.0-rc6 — Known-malware fingerprint scanning
- **Malware scan** (Protection): SHA-256 of every installed app's APK files (base + splits, cached by
  path/size/mtime) compared on the phone with known-malware fingerprints: Echap's 3,228 stalkerware
  samples (bundled, CC BY 4.0, refresh on tap) and, optionally, MalwareBazaar's latest APK fingerprints
  using the user's own free abuse.ch key (stored with an Android Keystore AES-GCM key, sent only as a
  header). In memory: 64-bit prefixes, every hit confirmed against the full hash.
- **APK check before installing**: pick a file; it is fingerprinted while copied to a private cache,
  parsed with `getPackageArchiveInfo` and judged KNOWN_MALICIOUS / SUSPICIOUS / NO_KNOWN_MATCH /
  INCONCLUSIVE, with what the app could do once installed. The copy is deleted afterwards.
- The background watch also fingerprints newly installed or updated apps.

## v0.4.0-rc5 — Protection layers
- **Background security watch** (opt-in, off by default, read-only): about hourly checks apps installed or
  updated since the last run (`PackageManager.getChangedPackages`, reset-safe across reboots), new holders of
  accessibility / notification access / device admin, and every 6 h unusual background uploads. Each
  finding is notified once on a new "Security alerts" channel; lock-screen version never names the app.
  The firewall notifies if Android revokes its VPN while the watch is on.
- **Camera, mic & location log** (Shizuku): last uses per app from Android's own app-ops record, with
  background use highlighted. `dumpsys appops --op` is run and parsed inside the Shizuku process; only a
  typed summary crosses the Binder.
- **Device hardening** checklist: screen lock, patch age, encryption, USB/wireless debugging (with the
  Shizuku explanation), lock-screen notification content, apps that can install apps, current Wi-Fi
  security (open/WEP/OWE/WPA), Private DNS; each with a Settings shortcut. Unreadable facts are "check
  yourself", never "OK".

## v0.4.0-rc4 — Per-network firewall, data usage, ad blocking
- **Firewall per network**: block Wi-Fi, mobile data or both for each app. The VPN follows network
  switches and contains only the apps blocked on the current network; when nothing is blocked there it
  holds only this app (never an empty allow-list, which Android would treat as "all apps").
- **Data usage** (Tools → Diagnostics): per-app Wi-Fi / mobile / background bytes for today, 7 or 30 days
  from Android's NetworkStatsManager, and "unusual background upload today" against the app's own last 14
  days (robust median/MAD baseline; worded as unusual, never as malware). Block on Wi-Fi/mobile from a row.
- **Ad & tracker blocking**: DNS-only local VPN (blocked hosts → NXDOMAIN, others forwarded to the
  network's own DNS), StevenBlack hosts list downloaded on tap, per-host exceptions, most-blocked hosts in
  memory only. Mutually exclusive with the firewall (one VPN at a time); Private DNS provider offered as the
  option that works alongside the firewall. Warns when Private DNS strict mode bypasses it.
- Home shows firewall and ad-blocking state.

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
