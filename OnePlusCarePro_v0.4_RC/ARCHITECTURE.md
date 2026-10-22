# Architecture — OnePlus Care Pro v0.4 RC

## Module layout

```
OnePlusCarePro_v0.4_RC/
├── core/            Pure Kotlin/JVM (no Android imports). All decisions live here.
│   └── com.bright.onepluscare.core
│       ├── model/        DeviceFacts, Reading<T> (Measured | Unavailable + Source)
│       ├── health/       HealthEvaluator → HealthReport (HEALTHY / ATTENTION / CRITICAL + factors)
│       ├── rules/        RecommendationEngine (explainable rule set), Recommendation, Evidence
│       ├── storage/      PathPolicy, FileSystemWalker, CleanupClassifier, StorageAccessResolver, MediaUriPolicy
│       ├── duplicates/   DuplicateFinder (size → partial hash → SHA-256 → byte verify), SelectionGuard
│       ├── trash/        SafeTrashStore (write-ahead journal), RestoreNaming, RetentionPolicy
│       ├── history/      HistoryStore, HistoryFilter (counts/bytes only, no names)
│       ├── network/      NetworkDiagnoser (layered verdict)
│       ├── battery/      BatteryInterpreter (bands, OEM-dependent values)
│       ├── oem/          OemDetector → OemProfile + GuidanceItem
│       ├── apps/         AppRecord, AppEvaluator (flags, search, sort)
│       ├── diagnostics/  DiagnosticReportBuilder, Redactor
│       ├── advanced/     AdvancedDiagnostic / AdvancedAllowList / OutputSanitizer (Shizuku contract)
│       ├── security/     StalkerwareIoc (IocDatabase), AppRiskScanner, FirewallRules
│       ├── migration/    LegacyMigration (v0.3 SharedPreferences → v0.4 stores)
│       ├── session/      MaintenanceSession (attributed Before/After)
│       ├── settings/     AppSettings (+ sanitization)
│       └── io/           AtomicTextFile (tmp + fsync + atomic rename + .bak)
└── app/             Android application (Compose, platform adapters)
    └── com.bright.onepluscare
        ├── CareApplication, AppGraph (manual DI, process-scoped shared state), MainActivity
        ├── data/        DeviceRepository, StorageRepository, AppsRepository, NetworkRepository,
        │                SettingsRepository, HistoryRepository, DiagnosticsRepository,
        │                Capabilities, SystemIntents, LegacyMigrator
        ├── shizuku/     AdvancedMaintenanceService (UserService), ShizukuGateway
        ├── work/        MaintenanceScheduler, MaintenanceScanWorker, Notifications
        └── ui/          theme/, components/, nav/ (CareApp, ActivityResultHost), screens/, vm/
```

Why the split: v0.3 kept every decision inside a 1,600-line composable, which made nothing testable and
let correctness rules (keep one duplicate, never overwrite on restore) live only in UI code. In v0.4 the
`core` module owns every rule and every file-system mutation policy and is covered by JVM tests that run
without an emulator. The `app` module only adapts Android APIs to `core` types and renders state.

## Data flow

```
Android APIs ──► data/*Repository ──► core (evaluate / classify / guard) ──► ViewModel StateFlow ──► Compose
                     ▲                                                              │
                     └────────────── user intent (confirm, select, restore) ◄───────┘
```

* **AppGraph** is created once in `CareApplication`. It owns repositories, a `SupervisorJob` scope, and
  process-wide `StateFlow`s for results that several screens share (`SharedResults`: last cleanup scan,
  duplicate result, app inventory, network result, Before/After session), plus `capabilities` and a
  `trashVersion` counter.
* **ViewModels** are activity-scoped (`careViewModel()`), so state survives tab switches and rotation.
  v0.3 lost every scan result on rotation because state lived in `remember {}`.
* **Device polling** (`HomeViewModel`) is a cold flow shared with `WhileSubscribed(5 s)`: it ticks every
  5 s only while a screen is collecting it, on `Dispatchers.IO`. v0.3 called `snapshot()` on the main
  thread every 6 s for the life of the composition.
* **System dialogs** (MediaStore trash/restore confirmation, export file picker) are launched from a single
  `ActivityResultHost` composable. Each pending request carries a `launched` flag, so recomposition,
  screen transitions or rotation never launch a dialog twice.

## Destructive-action pipeline

```
select (UI) ──► requestTrash() ──► SelectionGuard.sanitize()  (duplicates: keep ≥ 1 per group)
            ──► TrashConfirmation (counts/bytes/protected keepers) ──► ConfirmDialog
            ──► confirmTrash()
                  ├─ FILESYSTEM items → SafeTrashStore.moveToTrash()  (PathPolicy + journal)
                  └─ MEDIASTORE items → MediaStore.createTrashRequest() → system dialog → recordMedia()
purge (Trash screen) ──► PurgeConfirmation ──► ConfirmDialog(irreversible) ──► SafeTrashStore.purge()
expiry ──► only if the user set a retention, only at app start (foreground), never in the worker
```

### Safe Trash store (core/trash/SafeTrashStore.kt)

* Journal: `filesDir/trash_journal.json`, written with `AtomicTextFile` (temp file, `fsync`, atomic
  rename, `.bak` fallback). Never truncated (v0.3 capped at 300 entries and silently orphaned files).
* State machine per entry: `PENDING_MOVE → IN_TRASH → PENDING_RESTORE → (removed)`. Every state change
  is journaled **before** the filesystem change. `reconcile()` (startup) completes or rolls back
  interrupted operations and adopts orphaned trash files.
* Trash file names are generated (`<uuid>.trash`); user-controlled names never become path segments.
* Restore never overwrites: the target is created with `Files.createFile` (O_CREAT|O_EXCL) and then
  replaced by an atomic rename of the trash file. A name collision yields `name (restored).ext`.
* One lock serializes move/restore/purge/expiry/reconcile — no restore-vs-purge race.
* Location: `getExternalFilesDir()/safe_trash` (same volume as shared storage, so moves are renames when
  the kernel allows; cross-device moves fall back to copy+delete with rollback). Same directory as v0.3.
  `android:hasFragileUserData="true"` asks the user whether to keep it on uninstall.

### Path policy (core/storage/PathPolicy.kt)

Single choke point for any path the app moves, restores or deletes: absolute, no `.`/`..` segments, no
NUL, inside the external-storage root, not under `Android/data`, `Android/obb` or the trash itself, not a
symlink, and the real parent equals the lexical parent (no symlinked directories). The walker never
follows links.

## Storage access model

| Android | Permission granted | Mode | Removal |
|---|---|---|---|
| 11+ | All files access | FILESYSTEM | Safe Trash (restorable by the app) |
| 11+ | Media read | MEDIASTORE | `createTrashRequest` (system dialog, ~30-day system trash) |
| 14+ | Selected photos | MEDIASTORE_PARTIAL | as above, limited set |
| 8–10 | READ+WRITE external (legacy) | FILESYSTEM | Safe Trash |
| 8–10 | read only / none | NONE | nothing offered (v0.3 permanently deleted here) |

## Scheduled work

`MaintenanceScheduler` enqueues one unique periodic job (24 h, 4 h flex) requiring charging, battery
not low and storage not low. `MaintenanceScanWorker` reads device facts and runs a bounded (15,000 files)
read-only scan, skips entirely when the device is thermally throttled, writes a history event and may post
a private notification. It holds no reference to any mutating API (asserted by `ManifestSecurityTest`).

## Shizuku boundary

```
app process ──bindUserService──► Shizuku server ──► AdvancedMaintenanceService (uid 2000 or 0)
  IAdvancedMaintenanceService: identity(), readProperty(key), runDiagnostic(enumName), destroy()
```

* No method accepts a command, path or argument list. `readProperty` accepts only keys in
  `AdvancedAllowList.PROPERTIES`; `runDiagnostic` accepts only an `AdvancedDiagnostic` enum name whose
  argv is fixed in `core`. `ProcessBuilder` is used with an argv list (no `sh -c`).
* Every call checks `Binder.getCallingUid()` against the app uid captured from the context Shizuku passes
  (fail-closed when unknown).
* Output is capped (lines + 16 KB) and scrubbed of serial/IMEI/MAC patterns before crossing the Binder.
* Shizuku is optional. With it absent, not running, or denied, the Advanced screen explains the state and
  everything else is unaffected.

## System Tuner (Shizuku write path)

```
TunerViewModel → TweaksRepository ─ read current state (read-only TweakOps)
                                   ─ TweakPlanner → TweakChange(apply, undo)
                                   ─ TweakExecutor: journal.begin → run ops → rollback on failure → journal.mark
ShizukuGateway.runOp(TweakOp JSON) → AdvancedMaintenanceService.runOp → TweakCommands.argvFor (re-validate) → ProcessBuilder
```
All decision logic (allow-lists, protected packages, planners, parsers, journal) is in `core/tweaks` and
unit-tested on the JVM.

## Security Center

```
SecurityViewModel → SecurityRepository ─ PackageManager facts (permissions, installer, signing SHA-1,
                                          launcher icon) + Settings.Secure accessibility / listeners,
                                          DevicePolicyManager admins, default SMS
                                       ─ IocDatabase (assets/stalkerware_ioc.yaml or files/, user-tapped update)
                                       ─ core AppRiskScanner.scan → ScanSummary (in memory only)
Firewall screen → FirewallStore (blocked set) → VpnService.prepare consent (ActivityResultHost)
               → FirewallVpnService: addAllowedApplication(blocked only) → tun read-and-discard
```
Rules, the IOC parser and `FirewallRules` (never start with an empty list) are in `core/security`.

## Dependencies

All versions live in `gradle/libs.versions.toml`. `core` depends only on kotlinx-coroutines-core and
kotlinx-serialization-json. `app` adds AndroidX Core/Activity/Lifecycle/Compose (BOM)/WorkManager and the
Shizuku API/provider. No analytics, ads, crash reporters or network SDKs.

## Testing layers

| Layer | Where | Runs on |
|---|---|---|
| Domain rules, path security, trash journal, duplicates, migration | `core/src/test` (JUnit 5) | JVM, no Android |
| Manifest/XML security regressions, intents, worker, repositories, Compose UI | `app/src/test` (Robolectric) | JVM (CI) |
| MediaStore trash request on a real device | `app/src/androidTest` | device/emulator |
| Manual flows (system dialogs, OxygenOS behaviour, Shizuku) | `TEST_PLAN.md` | OnePlus device |
