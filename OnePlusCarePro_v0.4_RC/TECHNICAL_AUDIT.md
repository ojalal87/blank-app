# Technical Audit — OnePlus Care Pro v0.3 → v0.4 RC

Scope: every file in `01_CURRENT_v0.3/` (17 Kotlin files, 3,815 lines incl. Gradle/XML), compared with
`03_REFERENCE_v0.2/`. Security-specific findings are in `CYBERSECURITY_AUDIT.md` (SEC-xx); this document
covers build, architecture, correctness, Android-API usage and product honesty (TA-xx).

## 1. Build and toolchain

| ID | v0.3 problem | v0.4 fix |
|---|---|---|
| TA-01 | No Gradle wrapper JAR/scripts; build not reproducible. | Gradle 8.14.3 wrapper added (`gradlew`, JAR, properties). |
| TA-02 | `kotlinOptions { jvmTarget = "17" }` — this DSL is an error from Kotlin 2.2, and v0.3 pins Kotlin 2.3.21. | `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`. |
| TA-03 | Versions scattered in build scripts. | `gradle/libs.versions.toml` version catalog. |
| TA-04 | No tests, no test dependencies, no lint configuration. | `core` JUnit 5 suite; `app` Robolectric/WorkManager/Compose tests; lint `abortOnError`, XML/HTML reports. |
| TA-05 | Release: no shrinking of resources, no signing story, debug & release share an applicationId. | `isShrinkResources`, signing from untracked `keystore.properties`, `.debug` suffix. |
| TA-06 | Theme used `android:style/Theme.Material.NoActionBar` with deprecated `statusBarColor` and `fontFamily=sans`; white flash risk. | Window theme with black background, transparent bars, `enableEdgeToEdge()` before `super.onCreate`. |
| TA-07 | Launcher icon was a raw vector `drawable` (no adaptive icon, no monochrome). | Adaptive icon with monochrome layer (Android 13 themed icons). |
| TA-08 | AIDL kept but ProGuard rules referenced a class in the root package only. | Rules updated for the new package; kotlinx-serialization keep rules. |

**Verification:** the real Android build ran in GitHub Actions (`.github/workflows/oneplus-care-v04.yml`):
debug APK, R8 release, lint (0 errors), 84 core + 20 app tests all pass on run 38978365442. See
`TEST_REPORT.md`. The authoring environment had no Google Maven access.

## 2. Architecture

| ID | Finding | Resolution |
|---|---|---|
| TA-10 | All state (scan results, selections, pending system requests) in `remember {}` inside one 1,643-line composable → lost on rotation, process death, or tab change; not testable. | Activity-scoped ViewModels with `StateFlow`; process-scoped `AppGraph.results`; pure `core` module. |
| TA-11 | Repositories constructed inside composition (`remember { Repo(context) }`) with Activity context. | Single `AppGraph` with application context. |
| TA-12 | Business rules (keep oldest duplicate, risk classes, recommendation thresholds) in UI and repositories. | Moved to `core` with unit tests. |
| TA-13 | Free-text Arabic literals in code, English mixed in; no `strings.xml` beyond `app_name`. | 624 strings in `values/` + `values-ar/`, generated from one table (`tools/gen_strings.py`) that fails the build on missing/unused keys or mismatched format args. |
| TA-14 | No navigation model; "More" tab mixed Trash, History, Repair, Shizuku, Settings. | 4 tabs (Home, Clean, Apps, Tools) + saveable back stack; `NavigationRail` ≥ 600 dp. |

## 3. Threading, lifecycle, coroutines

| ID | Finding | Resolution |
|---|---|---|
| TA-20 | `deviceRepo.snapshot()` (StatFs, sticky broadcast, binder calls) on the main thread at first composition and every 6 s via `LaunchedEffect(Unit) { while(true) }`. | `DeviceRepository.facts()` on `Dispatchers.IO`; polling flow shared with `WhileSubscribed`. |
| TA-21 | `smartAdvisor.generate(...)` and `usageRepo.hasUsageAccess()` (AppOps binder call) evaluated on every recomposition. | Computed in `HomeViewModel` flow off the main thread. |
| TA-22 | Scans launched in `rememberCoroutineScope()`; `busy` flag stuck if the composition left mid-scan; no cancellation UI. | `viewModelScope` job with Cancel button; `finally` always clears `running`; `CancellationException` re-thrown. |
| TA-23 | Recursive scan built a full list before showing anything. | Walker reports progress every 250 files; MediaStore every 500 rows; determinate bars when totals are known. |
| TA-24 | `MaintenanceScanWorker` returned `Result.retry()` forever on any exception. | Retries twice, then `failure()`; cancellation propagated. |
| TA-25 | `Shizuku` permission listener registered in Activity; binder-death not handled. | `ShizukuGateway` registers sticky binder-received, binder-dead and permission listeners once; status as `StateFlow`. |
| TA-27 | (found during v0.4 CI) `getExternalFilesDir()` / `isExternalStorageManager()` throw when no external volume is registered; a single failing startup step aborted recovery and expiry and could crash the process. | Guarded calls; each startup step isolated; app scope has a `CoroutineExceptionHandler`. |
| TA-26 | System trash `IntentSender` launched from a click lambda; result handler relied on mutable composition state (lost on rotation). | Pending request in ViewModel with `launched` flag; single `ActivityResultHost`. |

## 4. Storage and Android 11–16 correctness

| ID | Finding | Resolution |
|---|---|---|
| TA-30 | Cleaner listed files it could not act on (e.g. read-only MediaStore rows on API < 30, files in non-writable dirs). | `FileFacts.actionable` (writable parent, or API 30+ valid media URI); classifier drops non-actionable items. |
| TA-31 | `MediaStore.Files` generic URIs passed to `createTrashRequest`; some OEM builds reject non-typed URIs. | Typed Images/Video/Audio URIs when `MEDIA_TYPE` is known. |
| TA-32 | Trashed media were never reconciled; app trash list showed items the system had already purged. | `reconcileMedia()` queries `IS_TRASHED` with `QUERY_ARG_MATCH_TRASHED` at startup. |
| TA-33 | API 33+: only images/video/audio visible; API 34 partial access ignored. | `READ_MEDIA_VISUAL_USER_SELECTED` declared and modelled as `MEDIASTORE_PARTIAL`. |
| TA-34 | `WRITE_EXTERNAL_STORAGE maxSdk=28` but Android 10 needs legacy storage to write. | `maxSdkVersion=29` + `requestLegacyExternalStorage`. |
| TA-35 | Storage total from `StatFs(/data)` under-reports vs. Settings. | `StorageStatsManager.getTotalBytes/getFreeBytes(UUID_DEFAULT)` with StatFs fallback. |
| TA-36 | `MediaScanner` not informed after moves → stale gallery entries. | `MediaScannerConnection.scanFile` for moved/restored paths. |
| TA-37 | Download heuristic matched any path containing "download". | Top-level `Download/` or `Downloads/` only (tested). |
| TA-38 | No exclusions, no age filter, fixed 250 MB threshold. | User exclusions (normalized, traversal-rejected), large-file size and age filters. |

## 5. Feature correctness (Phase 3 requirements)

| Requirement | v0.3 | v0.4 |
|---|---|---|
| Cleaner reports only actionable files | No (TA-30) | Yes |
| Duplicates keep ≥ 1 copy; collision-safe | UI-only rule; physical duplicates possible | Domain guard + inode de-dup + byte verification |
| App Doctor separates package / data / cache / usage / last use / unavailable | `queryStatsForUid` (merges shared-UID apps); "data" shown without noting it includes cache; zeros for unknown | `queryStatsForPackage`; "Data (includes cache)"; `null` → "Not available"; usage window and approximation stated |
| Network Doctor separates local, DNS, gateway, internet, validation, VPN/Private DNS | DNS timing + TCP to 1.1.1.1 + validated flag | 7 layers, gateway RST-as-reachable logic, HTTPS 204 probe, Private DNS mode, VPN, captive portal; verdict + advice |
| Battery/thermal labels unavailable/OEM values | `-1`/`null` shown as numbers; thermal 0 when API < 29 | `Reading.Unavailable(reason)`; current marked OEM-dependent; cycle count API 34+; thermal headroom API 30+ |
| Before/After measures only real deltas | Free-space delta and **RAM delta** (meaningless) reported as "recovered" | Attributed bytes = measured sizes at permanent deletion; net change shown separately with an "other activity" notice; RAM removed |
| Trash reversible, no silent overwrite | Overwrite race, truncation, crash loss | SEC-02/03/05 fixed |
| Scheduled scans non-destructive | Yes | Yes, asserted by test |
| Diagnostic export minimized | Partly | SEC-10 |

## 6. Honesty / placebo audit

* v0.3 showed a 0–100 "Health score" whose weights were arbitrary. v0.4 shows a three-level state with the
  named factors that caused it; there is no score.
* v0.3's advisor text implied "Boost" ("before attempting any Boost"). v0.4 states explicitly that RAM
  boosting, CPU cooling and charge acceleration are not offered because they do not work on modern Android.
* Recommendations are labelled "Rule-based recommendation from measured values. Not AI."
* "Smart Scan" is a staged, cancellable scan of real categories (health, storage, duplicates, apps).

## 7. Dead code / removed

* `AppsRepository.loadApps()` (v0.3) loaded every package with `GET_META_DATA` and checked permissions one
  `checkPermission` IPC at a time; replaced by `requestedPermissionsFlags`.
* `ShizukuManager.capabilityReport()` (remote permission probing) removed: unused and not needed.
* `DeleteResult`, `deleteMediaDirect`, `RepairLevel`, `MaintenanceDelta.memoryDeltaBytes` removed.
* `screenOrientation="unspecified"`, unused `ACCESS_WIFI_STATE` removed.

## 8. Known limitations (carried to HANDOFF.md)

1. Compose UI and Android adapters are verified by the CI build and Robolectric tests, not on a OnePlus
   device in this engagement.
2. Process death while the system trash dialog is open: the user's choice is applied by Android, but the
   app does not record the item in its Trash list (the item remains restorable from the gallery's trash).
3. App Doctor lists launchable apps only (by design, to avoid `QUERY_ALL_PACKAGES`).
4. Usage stats are bucketed by the platform; "screen time over 30 days" is approximate (stated in UI).
