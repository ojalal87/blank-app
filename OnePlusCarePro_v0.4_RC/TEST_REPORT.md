# Test Report — OnePlus Care Pro v0.4 RC

## Environments

| Where | What ran | Notes |
|---|---|---|
| Authoring container (Linux, JDK 21, Gradle 8.14.3) | `:core:compileKotlin`, `:core:compileTestKotlin`, `:core:test` | Google Maven (`maven.google.com` → `dl.google.com`) was blocked by the network policy, so no Android build could run here. Maven Central was reached through Google's GCS mirror for this local verification only. |
| GitHub Actions `ubuntu-latest`, Temurin JDK 17 | Full pipeline below | Workflow `.github/workflows/oneplus-care-v04.yml`. Final green run: **#6, run id 38978365442, commit `1377b72`**. |

## Commands and results (final CI run)

All commands run from `OnePlusCarePro_v0.4_RC/`.

| Step | Command | Result |
|---|---|---|
| String resources in sync | `python3 -I tools/gen_strings.py .` + `git diff --exit-code` | PASS — 624 strings, 624 referenced, en/ar format args match |
| Core unit tests | `./gradlew --no-configuration-cache :core:test` | PASS — **84 tests, 0 failures, 0 errors, 0 skipped** |
| Debug APK | `./gradlew :app:assembleDebug` | PASS — `app-debug.apk` uploaded as artifact `oneplus-care-debug-apk` (≈19 MB zip) |
| App unit tests (Robolectric, WorkManager, Compose UI) | `./gradlew :app:testDebugUnitTest` | PASS — **20 tests, 0 failures, 0 errors, 0 skipped** |
| Android lint | `./gradlew :app:lintDebug` (`abortOnError = true`) | PASS — **0 errors, 41 warnings** (below) |
| Release build | `./gradlew :app:assembleRelease` | PASS — R8 minify + resource shrinking + lintVital; APK unsigned (no keystore configured, by design) |

### Lint warnings (accepted for the RC, none security-relevant)

| Check | Count | Disposition |
|---|---|---|
| PluralsCandidate | 24 | Intentional: neutral "N items" phrasing avoids broken Arabic plural forms; convert to `<plurals>` with all six Arabic quantities in a later pass. |
| UseKtx | 9 | Style (`edit {}`, `toUri()`); no behaviour change. |
| ApplySharedPref | 5 | Intentional `commit()` for settings/migration durability (see `SettingsRepository`). |
| ConfigurationScreenWidthHeight | 1 | Rail/bar switch uses `LocalConfiguration.screenWidthDp`; move to window size classes later. |
| ObsoleteSdkInt | 1 | Redundant SDK check; harmless. |
| OldTargetApi | 1 | A newer API level than 36 is available; raise `targetSdk` after device testing on it. |

### CI iterations (honest history)

| Run | Commit | Outcome | Cause → fix |
|---|---|---|---|
| #1 | `4d32695` | lint failed (6 errors); everything else passed | `LocalContextGetResourceValueCall`: `context.getString()` inside composables → `stringResource()` (`b08f461`). |
| #2 | `b08f461` | 7 app tests failed | Robolectric has no external volume; `getExternalFilesDir()` threw → guarded (`a17fff1`). |
| #3 | `a17fff1` | 7 app tests failed | Same for `Environment.isExternalStorageManager()` → guarded (`9393d63`). |
| #4 | `9393d63` | 4 app tests failed | WorkManager not initialized in Robolectric surfaced an unhandled exception in `AppGraph.startup` → each startup step isolated, scope gets an exception handler (`5854d7c`); one UI test asserted an off-screen lazy item → asserts a visible item. |
| #5 | `5854d7c` | **all green** | — |
| #6 | `1377b72` | **all green** | Added result summary step. |

Two of these were real robustness defects in app code (unguarded external-storage calls; one failing
startup step could abort recovery/expiry and crash the process), now fixed.

## Test inventory

### `core` (JUnit 5, 84 tests)
* `HealthEvaluatorTest` (8) — thresholds, unavailable sensors, worst-factor rule, offline ≠ unhealthy.
* `RecommendationEngineTest` (9) — evidence/risk/reversibility present, severity ordering, permission-dependent actions, patch age, unused/sensitive apps, top-3.
* `PathPolicyTest` (7) — traversal, NUL, relative paths, outside root, Android/data & obb, symlinked file and parent, missing/dir.
* `FileSystemWalkerTest` (3) — protected dirs, symlink loop, truncation, cancellation, exclusions.
* `CleanupClassifierTest` (8) — every category, age filters, actionable/excluded, system-trash method, exclusion normalization, access-mode resolution per API.
* `MediaUriPolicyTest` (1) — accepted/rejected URI corpus.
* `DuplicateFinderTest` (10) — grouping, keeper, different content, same inode, byte-verification collision defence, budgets, min size, cancellation, keep-one guard, `without()`.
* `SafeTrashStoreTest` (16) — round-trip restore, no-overwrite naming, parent recreation, tampered journal, refused sources, measured purge, expiry options, clock skew, media entries, crash reconcile (3 cases), orphan adoption incl. v0.3 names, corrupt journal, no truncation, naming.
* `HistoryStoreTest` (2), `NetworkDiagnoserTest` (3), `BatteryAndOemTest` (3), `AppEvaluatorTest` (2), `DiagnosticsTest` (4), `AdvancedAllowListTest` (4), `MigrationTest` (2), `SettingsAndSessionTest` (2).

### `app` (Robolectric, 20 tests)
* `ManifestSecurityTest` (9) — exported components, no deep links, no QUERY_ALL_PACKAGES/cleartext, legacy write cap, backup scope, AIDL surface, no shell in UserService, read-only worker, immutable PendingIntents.
* `SystemIntentsTest` (2) — only public Settings/Intent actions, no explicit components, package-name validation.
* `StorageRepositoryTest` (4) — no permission ⇒ NONE, non-MediaStore URIs refused, trash dir name, Android 10 never offers system trash.
* `WorkerSafetyTest` (1) — `MaintenanceScanWorker` via `TestListenableWorkerBuilder`: succeeds, moves nothing, writes history.
* `ComposeScreensTest` (4) — status text, recommendation details + action, irreversible dialog copy, onboarding → dashboard → Tools.

### Not run in this RC
* `app/src/androidTest` (`MediaStoreTrashInstrumentedTest`) — needs an Android 11+ device/emulator:
  `./gradlew :app:connectedDebugAndroidTest`.
* Manual device plan — `TEST_PLAN.md` (OnePlus/OxygenOS). **Not executed**; no device was available.
* Dependency CVE scan — not executed (see `RELEASE_CHECKLIST.md`).
