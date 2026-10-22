# Cybersecurity Audit — OnePlus Care Pro v0.3 → v0.4 RC

Method: manual source review of `01_CURRENT_v0.3` against OWASP MASVS v2 control groups (STORAGE, CRYPTO,
AUTH, NETWORK, PLATFORM, CODE, RESILIENCE, PRIVACY) and the trust boundaries in
`CYBERSECURITY_AUDIT_SCOPE.md`. The threat model treats the app as a privileged maintenance utility:
it may hold All Files Access, Usage Access and a Shizuku (shell/root) binder.

Severity scale: **Critical** (silent loss of user data or privilege escalation), **High** (user data loss
or exposure in a realistic flow), **Medium** (exposure or integrity issue needing unusual conditions),
**Low** (defence-in-depth / hygiene), **Info**.

Each regression test named below is in `core/src/test` (JVM) or `app/src/test` (Robolectric/XML).

## Summary

| ID | Title | Severity | Status |
|---|---|---|---|
| SEC-01 | Duplicate cleaner could trash the only physical copy of a file | High | Fixed |
| SEC-02 | Safe Trash metadata truncated at 300 entries → orphaned, unrecoverable files | High | Fixed |
| SEC-03 | Trash move not crash-safe (async `apply()` after move) | High | Fixed |
| SEC-04 | Restore path from journal not validated (traversal / outside root) | High | Fixed |
| SEC-05 | Restore overwrite race (check-then-rename, TOCTOU) | Medium | Fixed |
| SEC-06 | Symlink following in scanner and move source | Medium | Fixed |
| SEC-07 | Android 8–10 media "trash" was a permanent `ContentResolver.delete` | High | Fixed |
| SEC-08 | Shizuku UserService accepted calls from any binder holder | Medium | Fixed |
| SEC-09 | Shizuku output not size-capped / scrubbed before crossing Binder | Low | Fixed |
| SEC-10 | Diagnostic export included DNS server IPs, Private DNS hostname; probes ran during export | Medium | Fixed |
| SEC-11 | History stored absolute restore paths (file names) | Medium | Fixed |
| SEC-12 | `QUERY_ALL_PACKAGES` over-broad package visibility | Medium | Fixed |
| SEC-13 | No network security config; cleartext implicitly allowed; user CAs trusted on old APIs | Low | Fixed |
| SEC-14 | Backup rules backed up all prefs except two (history incl.) | Low | Fixed |
| SEC-15 | Notification content visible on lock screen | Low | Fixed |
| SEC-16 | MediaStore URIs from journal passed to resolver without validation | Medium | Fixed |
| SEC-17 | Unbounded hashing (DoS / battery / thermal) | Medium | Fixed |
| SEC-18 | Scheduled worker scans regardless of thermal state; no charging constraint | Low | Fixed |
| SEC-19 | Network probe to a third party IP (1.1.1.1) without disclosure | Low | Fixed |
| SEC-20 | `System.exit` in `destroy()` reachable by any caller | Low | Fixed (caller check) |
| SEC-21 | Release build has no signing story; debug/release share applicationId | Low | Fixed |
| SEC-22 | Gradle wrapper JAR missing / distribution checksum not pinned | Low | Partly fixed |
| SEC-23 | Exported ShizukuProvider | Info | Accepted (required by Shizuku, signature permission) |
| SEC-24 | Root-mode Shizuku amplifies impact of any future bug | Info | Mitigated (allow-list + UI warning) |
| SEC-25 | Dependency freshness / CVE scan not executed in this environment | Info | Open (CI task) |

No Critical findings remain. No High finding remains unresolved.

---

## Findings

### SEC-01 — Duplicate cleaner could trash the only physical copy (High) — Fixed
* **Affected (v0.3):** `CleanerRepository.scanFileSystemDuplicates`, `MainActivity` duplicate UI.
* **Scenario:** The same inode reachable through two paths (bind mounts, hard links, a symlinked directory
  inside shared storage) hashes identically, forms a "duplicate group", and the user trashes the
  "extra copy" — which is the only copy. Separately, the keep-one rule existed only as a disabled
  checkbox in the UI; any other code path (or a future UI change) could select all copies.
* **Remediation:** `DuplicateFinder.distinctPhysical()` collapses entries with the same `st_dev:st_ino`
  (`Os.lstat`) or MediaStore `DATA` path; the walker never follows symlinks; `SelectionGuard.sanitize()`
  runs in the ViewModel before any move and removes the keeper whenever a whole group is selected; byte
  verification against the keeper rejects SHA-256 collisions or files that changed between reads.
* **Tests:** `DuplicateFinderTest` (`two paths to the same physical file…`, `selection guard never allows
  every copy…`, `byte verification rejects…`).
* **Residual risk:** Low. FUSE on some OEM kernels may report different inode numbers for the same file
  through different mount views; the MediaStore path additionally de-duplicates by `DATA`.

### SEC-02 — Trash metadata truncated at 300 entries (High) — Fixed
* **Affected:** `TrashRepository.append()` → `current.take(MAX_ITEMS)`.
* **Scenario:** After 300 trash moves the oldest entries are dropped while their files stay in
  `safe_trash/`, invisible and unrestorable; "Empty trash" never deletes them (silent storage leak and
  data-loss of restore ability).
* **Remediation:** `SafeTrashStore` never truncates the journal; `reconcile()` adopts orphan files
  (including v0.3 `<uuid>_<name>` files) as restorable entries targeting `Download/OnePlus Care Recovered/`.
* **Tests:** `journal survives reopening and is never truncated`, `reconcile adopts orphaned trash files, including v0_3 names`, `corrupt journal does not lose files`.
* **Residual risk:** Low.

### SEC-03 — Trash move not crash-safe (High) — Fixed
* **Affected:** `TrashRepository.moveAbsoluteFiles()` moved files first, then `prefs.edit().apply()`
  (asynchronous) — a process kill in between loses the only record of where the file went.
* **Remediation:** write-ahead journal with `PENDING_MOVE`/`PENDING_RESTORE` states persisted by
  `AtomicTextFile` (temp + `fsync` + atomic rename + backup) **before** each filesystem mutation;
  `reconcile()` at startup finishes or rolls back.
* **Tests:** `reconcile completes a move interrupted…`, `…rolls back a partial cross-device copy…`,
  `…rolls back an interrupted restore and removes the empty placeholder`, `atomic file falls back to backup…`.
* **Residual risk:** Low. Journal and files are on different volumes (internal vs. external), so a crash
  exactly between two fsyncs is resolved by reconcile heuristics documented in the code.

### SEC-04 — Restore path not validated (High) — Fixed
* **Affected:** `TrashRepository.restoreFile()` used `entry.originalPath` verbatim.
* **Scenario:** A tampered/corrupted preferences file (backup restore from another device, rooted
  attacker, or bug) could make restore write a file anywhere the app can write, including other apps'
  `Android/data` (with All Files Access on some builds) or outside shared storage; `purge` could delete
  any `trashPath`.
* **Remediation:** `PathPolicy.checkRestoreTarget()` (absolute, no traversal segments, inside root, not
  protected, no symlinked ancestor) is applied to the original path and re-applied after parent creation;
  trash file names must match `^[A-Za-z0-9][A-Za-z0-9._ ()_-]{0,250}$` and resolve directly inside the
  trash directory; the journal is excluded from backup (SEC-14); the v0.3 importer skips entries whose
  trash path is not directly inside the trash directory.
* **Tests:** `tampered journal cannot restore outside the root or delete outside the trash dir`,
  `PathPolicyTest.*`, `MigrationTest.v0_3 trash imports only entries inside the trash dir`.

### SEC-05 — Restore overwrite TOCTOU (Medium) — Fixed
* **Affected:** `uniqueRestoreTarget()` checked `exists()` then `renameTo()` (POSIX rename replaces).
* **Remediation:** destination created exclusively (`Files.createFile`, O_EXCL — fails on an existing file
  *or* symlink), then atomically replaced by the trash file. Only the placeholder the app just created can
  be replaced.
* **Tests:** `restore never overwrites an existing file`.

### SEC-06 — Symlink following (Medium) — Fixed
* **Affected:** `File.isDirectory`/`listFiles()` traversal followed links (loops, escaping root);
  `moveFile` accepted symlinks.
* **Remediation:** `Files.walkFileTree` without `FOLLOW_LINKS`; `PathPolicy` rejects symlinks and
  symlinked parents.
* **Tests:** `walk skips protected dirs, does not follow symlink loops…`, `rejects a symlinked file and a symlinked parent directory`.

### SEC-07 — Permanent deletion on Android 8–10 labelled as trash (High) — Fixed
* **Affected:** `CleanerRepository.deleteMediaDirect()` on API < 30 after a "Move to trash" dialog.
* **Remediation:** API < 30 never uses MediaStore removal. With legacy WRITE_EXTERNAL_STORAGE
  (`maxSdkVersion=29`, `requestLegacyExternalStorage`), files go to Safe Trash; otherwise nothing is
  offered. `StorageAccessResolver` + `systemTrashRequest()` guard.
* **Tests:** `storage access resolution across API levels`, `android 10 never offers system trash`.

### SEC-08 — Shizuku UserService caller not verified (Medium) — Fixed
* **Affected:** `AdvancedMaintenanceService` (all methods).
* **Scenario:** The service runs as shell/root. Today only this app receives the binder, but any leak
  (logging the binder, passing it in an Intent extra by mistake, a future IPC) would hand shell-identity
  diagnostics — and `destroy()` — to another app.
* **Remediation:** every method calls `enforceCaller()`, comparing `Binder.getCallingUid()` with the uid
  resolved from the package context Shizuku v13 passes; fail-closed if unknown. The interface accepts no
  command, path or argv; diagnostics are an enum with fixed argv; properties are allow-listed; no shell.
* **Tests:** `AdvancedAllowListTest` (injection strings, identifiers refused, no shell argv),
  `ManifestSecurityTest.AIDL exposes no command…`, `…user service never invokes a shell`.
* **Residual risk:** Low; the Shizuku server itself and root mode are outside this app's control (SEC-24).

### SEC-09 — Unbounded Shizuku output (Low) — Fixed
`OutputSanitizer` caps lines and 16 KB, redacts `serialno/imei/meid/iccid/android_id` key-values and MAC
addresses. Process is destroyed on timeout. Tests: `output sanitizer caps lines and redacts…`.

### SEC-10 — Diagnostic export over-collection (Medium) — Fixed
* **Affected:** `DiagnosticReportRepository.build()` exported DNS server IP list and Private DNS hostname,
  exact byte counts, and ran a live network probe as a side-effect of exporting.
* **Remediation:** `DiagnosticReportBuilder` exports health states, rounded storage (0.1 GB), battery and
  thermal readings, permission booleans; device model is a default-on toggle; network layer *statuses*,
  history counts and (redacted) Shizuku output are opt-in. Never: file names, package list, IP/MAC/DNS
  addresses, serial/IMEI/ANDROID_ID, fingerprint. `Redactor` scrubs paths, e-mails, IPs, MACs, package
  names and token-like pairs in free text. Export uses only the last on-screen network result. Written via
  SAF `CreateDocument` only; never uploaded.
* **Tests:** `DiagnosticsTest.*`.

### SEC-11 — File names in history (Medium) — Fixed
v0.3 stored `RestoreResult.message` (an absolute path) in history. v0.4 `HistoryEvent` holds type, count,
bytes and a fixed detail key. Migration keeps only the v0.3 title (truncated) and drops details.
Test: `v0_3 history converts and drops free-text details`.

### SEC-12 — QUERY_ALL_PACKAGES (Medium) — Fixed
Removed. `<queries>` declares the launcher intent and the Shizuku package. App Doctor / Privacy Doctor
review launchable apps only (documented in UI). Test: `no QUERY_ALL_PACKAGES and no cleartext`.

### SEC-13 — Network security config (Low) — Fixed
`usesCleartextTraffic="false"`, `network_security_config.xml` with cleartext disabled and **system CAs
only** (user-installed CAs cannot MITM probes). Probes use `HttpsURLConnection` with platform TLS
validation, no redirects followed, no caches. Test: as SEC-12.

### SEC-14 — Backup scope (Low) — Fixed
Backup and device-transfer include only `care_settings.xml`. Trash journal, history and external files are
excluded (device-local paths must never be restored onto another device). Test: `backup includes only settings…`.

### SEC-15 — Notification privacy (Low) — Fixed
Channel and notification `VISIBILITY_PRIVATE` with a generic public version; importance LOW; explicit,
`FLAG_IMMUTABLE` PendingIntent. Test: `pending intents are immutable`.

### SEC-16 — MediaStore URI validation (Medium) — Fixed
`MediaUriPolicy.isMediaStoreItem()` (authority `media`, numeric id, no query/fragment/user-info/encoding)
gates `createTrashRequest`, hashing `openInputStream`, reconciliation and journal recording.
Tests: `MediaUriPolicyTest`, `StorageRepositoryTest.system trash request rejects non-MediaStore uris`.

### SEC-17 — Hashing DoS / battery (Medium) — Fixed
Size pre-grouping, 64 KB head+tail partial hash, then full SHA-256 bounded by `maxHashBytes`
(8 GB dedicated / 2 GB Smart Scan) and `maxHashFiles`; cancellation via coroutine `ensureActive`; walker
caps files (60,000 interactive / 15,000 scheduled) and reports truncation. Tests: budget/cancellation tests.

### SEC-18 — Background resource abuse (Low) — Fixed
Worker requires charging + battery-not-low + storage-not-low, skips when thermal status ≥ MODERATE, never
mutates files. Expiry (the only automatic deletion) is opt-in and foreground-only.

### SEC-19 — Undisclosed third-party probe (Low) — Fixed
v0.3 TCP-connected to `1.1.1.1:443`. v0.4 uses `connectivitycheck.gstatic.com` (the host Android already
uses for validation) and the user's gateway, only on explicit tap, disclosed on screen.

### SEC-20 — destroy() reachable (Low) — Fixed by SEC-08.

### SEC-21 — Release configuration (Low) — Fixed
Release: R8 + resource shrinking, `isDebuggable=false`, `Log.v/d/i` stripped. Signing only from an
untracked `keystore.properties` (gitignored, together with `*.jks`, `*.keystore`); otherwise the release
APK is unsigned — no credentials are invented. Debug uses `.debug` suffix so it never replaces a release
install (and its Safe Trash).

### SEC-22 — Gradle wrapper integrity (Low) — Partly fixed
Wrapper JAR and scripts for Gradle 8.14.3 are now included (v0.3 had none). `distributionSha256Sum` could
not be fetched from this environment (downloads.gradle.org blocked); add it before release
(`RELEASE_CHECKLIST.md`).

### SEC-23 — Exported ShizukuProvider (Info) — Accepted
Required by the Shizuku API to receive the binder; protected by the signature/privileged permission
`INTERACT_ACROSS_USERS_FULL`, which third-party apps cannot hold.

### SEC-24 — Root-mode Shizuku (Info) — Mitigated
When Shizuku runs as root the same allow-list applies; the UI shows a warning recommending ADB mode.

### SEC-25 — Dependency vulnerability scan (Info) — Open
No OWASP Dependency-Check / OSV scan could run here (Google Maven blocked). The CI workflow is the place
to add `osv-scanner` or Gradle dependency verification metadata (`gradle/verification-metadata.xml`).

## v0.4 addendum — System Tuner (Shizuku write access)

Product requirement changed after the RC: the owner asked for OxygenOS fixes, preinstalled-app removal and
display/animation tuning. These require writing system state, so the Shizuku boundary moved from
read-only to **allow-listed write**. Design and controls:

| ID | Risk | Control | Test |
|---|---|---|---|
| SEC-26 | Caller (or a compromised app process) sends arbitrary commands to the shell/root service | `runOp` accepts only a JSON `TweakOp`; the **service** re-validates with `TweakCommands.argvFor` (fixed binaries, allow-listed setting keys with per-key value rules, two app-ops, five standby buckets, package-name regex, DPI bounds). Unknown/malformed JSON is refused; argv goes to `ProcessBuilder` without a shell. | `TweakCommandsTest` (injection strings, wrong namespace, out-of-range values, hostile JSON) |
| SEC-27 | Disabling a critical package bricks the phone (boot loop, no dialer, no emergency alerts) | `DebloatClassifier` protects system UI, telephony/IMS, Play services, installers, WebView, providers, network stack, emergency/cell-broadcast, Qualcomm modem, any launcher/keyboard by name; the app adds the *current* launcher and IME at runtime. Protection is enforced in the service too. Default action is `disable-user` (reversible); "remove" is `uninstall -k --user 0` (APK stays on /system, `install-existing` restores). Unknown packages only appear in expert mode with a warning. | `protected packages … can never be removed`, `inventory …` |
| SEC-28 | Partial change leaves the system in a mixed state | Every change is planned from the current state, journaled before execution (`TweakJournal`, atomic file), applied op by op; first failure triggers rollback with the recorded undo ops. Same-slot changes inherit the original undo. "Undo all" restores newest-first. | `executor applies, journals and reverts…`, `failure mid-change rolls back` |
| SEC-29 | Display density makes the UI unusable | Density limited to ±25 % of the panel's physical density (and 120–960 dpi in the service); confirmation dialog; undo restores the previous override or resets. | `density stays within 25 percent of physical`, `density limits` |
| SEC-30 | Privacy: listing all system packages via shell | Package list stays in memory on the device; never stored, exported or logged. | — |
| SEC-31 | Root-mode Shizuku amplifies any mistake | Same allow-list in root mode; UI marks root mode with an attention badge. | — |

Residual risk: OEM builds can attach unexpected behaviour to packages outside the curated list (expert
mode). Mitigation is the reversible default, the always-available restore, and the warning copy.
Shizuku itself and the user's choice to grant it remain outside this app's control.

## Areas reviewed with no finding

* **Intents / deep links:** one exported activity (launcher), no `<data>` filters, no extras read.
* **WebView:** none. If one is added: disable JavaScript and file access by default, no
  `addJavascriptInterface`, load only bundled assets.
* **Serialization:** kotlinx-serialization of app-owned JSON with `ignoreUnknownKeys`; no Java
  serialization, no reflection-driven polymorphism.
* **Logging:** no `Log` calls in sources; R8 strips `Log.v/d/i` anyway.
* **SharedPreferences secrets:** none stored (no tokens/accounts).
* **Crypto:** SHA-256 used only for content equality, never for security decisions.
* **Content providers / file sharing:** no FileProvider; export goes through SAF to a user-picked URI.
