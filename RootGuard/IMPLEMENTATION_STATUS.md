# RootGuard — implementation status

Status date: 2026-10-22. Package version `0.1.0-m1` (debug builds `-debug`). This file is the single
source of truth for what is implemented, what is verified and how, and what is not. It is updated with
every milestone. Nothing here is a device-tested result unless the "Device evidence" section says so.

## 1. Repository inventory and gap map

The repository (`ojalal87/blank-app`) contained, before RootGuard:

| Item | What it is | Relation to RootGuard |
|---|---|---|
| `OnePlusCarePro_v0.4_RC/` | No-root maintenance/security app (Kotlin 2.3.21, AGP 8.13.2, Compose BOM 2026.06.00, Shizuku, VpnService firewall) | Different trust model (no root, Shizuku), different package. **Not replaced and not modified.** Its proven toolchain, CI pattern and string generator were reused. |
| `streamlit_app.py`, `requirements.txt` | Template Streamlit app | Unrelated |
| Root library, database, iptables code | none | Built new |

Decision: RootGuard is a **separate Gradle project** in `RootGuard/` with its own application ID
(`com.rootguard.app`). Merging a root firewall into the Shizuku/VpnService app would mix two privilege
models and two firewall engines in one APK; the handoff also says VpnService must not be the primary
engine. It can be merged later if wanted; the core module has no dependency on either app.

Gap map against the handoff (✔ done, ◐ partial, ✘ not started):

| Handoff area | State | Notes |
|---|---|---|
| Domain contracts (`contracts/Domain.kt`) | ✔ | `core/model`, `core/policy`, `core/protocol` (typed, serializable, strict decoding) |
| IPC envelope + error codes (`IPC.md`, schema) | ✔ | `RgProtocol`, `RequestGate`: 256 KiB cap, protocol version, UUID ids, boot binding, ≤60 s monotonic expiry, replay → original outcome, unknown fields rejected |
| Root helper, typed ops only | ✔ | PROBE, PREVIEW (validate), APPLY, VERIFY, ROLLBACK, HEALTH, RECOVER_OWN_RULES; SUBSCRIBE_METADATA → `UNSUPPORTED_OPERATION` |
| Caller authentication | ✔ | libsu RootService binder; `Binder.getCallingUid() == app uid` on every call, fail-closed |
| iptables/ip6tables backend, own chain only | ✔ | `RG_OUT` + one `OUTPUT` jump per family; atomic chain replace via `iptables-restore --noflush`; read-back parser; no global flush |
| Journaled apply + compensation + reconcile | ✔ | App journal in `apply_transaction`; helper journal file written before the switch; `Reconciler` decides commit/rollback/restore |
| Recovery (own rules only, offline) | ✔ | Dashboard action; works without probe; no internet permission at all |
| Capability probe, honest states | ✔ | SUPPORTED / UNSUPPORTED / DENIED / UNVERIFIED with reason; scratch chain only |
| Principals, shared UID, UID reuse | ✔ | `InventoryRepository`; reuse ⇒ new principal ⇒ no inherited rules |
| Room `control.db` with exported schema | ✔ | 15 tables, triggers for CHECKs/partial uniques, equivalence test vs fixture |
| Room `telemetry.db`, `intel.db` | ✘ | M2 / M4 (no sensor or feed yet, so no store is created to look busy) |
| Encrypted DB (SQLCipher + Keystore) | ✘ | Plain app-private SQLite, backup disabled. Listed as a release gate (§6) |
| Wizard (AR/EN, RTL) | ◐ | WELCOME → CONSENT → PROBE → GOAL → APPS → PREVIEW → APPLY/RESULT → DASHBOARD. DATA_SCOPE and FEEDS steps absent (nothing to scope yet). Drafts saved. |
| Modes | ◐ | BALANCED, STRICT, DATA_SAVER. OBSERVE (needs M2 sensor) and PRIVACY (needs M4 feeds) shown as not offered. |
| Temporary overrides / emergency isolation | ◐ | Compiler + schema support them and are tested; no UI yet |
| Domain rules, tethering, LAN+destination | ✘ | Rejected in preview with explicit reasons (never approximated) |
| Boot reconciliation / early-boot protection | ◐ | After reboot the app detects `KERNEL_RULES_ABSENT` and offers "re-apply last verified"; no boot receiver, no root-manager module. The UI says the phone is unfiltered until then. |
| Metadata sensor, behaviour engine | ◐ | Reference statistics ported with golden tests (`RobustStats`); no sensor ⇒ no detector runs |
| Feeds, scanner | ✘ | M4 / later; no third-party data bundled |

## 2. Pinned versions (checked 2026-10-22)

| Component | Version | Source checked |
|---|---|---|
| AGP / Gradle wrapper | 8.13.2 / 8.14.3 | green in this repo's CI (OnePlus Care Pro run 39825527766) |
| Kotlin | 2.3.21 | same |
| KSP | 2.3.12 | Maven Central metadata, released 2026-09-09 |
| Room | 2.8.5 | developer.android.com Room release notes, 2026-09-09 (requires minSdk ≥ 23, AGP ≥ 8.4 for the Room Gradle plugin) |
| libsu (core + service) | 6.0.0 | topjohnwu/libsu README (JitPack; repository restricted to that group in `settings.gradle.kts`) |
| Compose BOM / lifecycle / coroutines / serialization | 2026.06.00 / 2.9.4 / 1.10.2 / 1.9.0 | proven in this repo |
| NDK | not used | iptables binaries are driven by argv; no native code |

minSdk 29 (Android 10), targetSdk/compileSdk 36.

## 3. Supported device matrix (design scope, not test results)

| Condition | Expected state |
|---|---|
| Android 10–16, Magisk / KernelSU / APatch granting su to the app | Root SUPPORTED; enforcement depends on probe |
| Kernel with `xt_owner` for IPv4 and IPv6, `iptables-restore --noflush` | Full per-app Wi-Fi/mobile/VPN rules, both families |
| `xt_owner` IPv4 only (ip6tables missing or no owner match) | IPv4 enforced; preview warns "IPv6 not enforced"; STRICT refused |
| No `xt_owner` | Enforcement UNSUPPORTED; app says so and does not pretend |
| Root denied / not installed | ROOT_DENIED; nothing changed |
| Another firewall (AFWall+, etc.) | Reported as coexistence warning (`foreignChains`); both may act |
| Tethering / domain rules / per-flow eBPF / metadata | UNSUPPORTED in v1, shown as such |
| VPN path (`tun+`) | UNVERIFIED until checked on a device |
| Work profile / secondary users | Only the current user's UIDs are enumerated and covered (default-deny range is per user) |

## 4. Milestones

| Milestone | Scope | Status |
|---|---|---|
| M0 | Skeleton, pinned deps, core domain, Room control.db + equivalence tests, CI | Done — CI green (§7); v1 schema exported and committed, CI fails if it drifts |
| M1 | Probe → principals → edit → preview → journaled apply → read-back verify → commit → reconcile → rollback/recover | Implemented and tested off-device against an iptables model; **device acceptance not yet run** (needs an owner-authorised rooted test device) |
| M2 | Metadata sensor (verified sensors only), dedupe, counter-reset handling, telemetry.db, retention | Not started |
| M3 | Multi-feature baselines, score-before-learn, quarantine, incidents (observe mode first) | Statistics primitives ported only |
| M4 | Feed adapters with licence gate, intel.db generations, matching | Not started |
| M5 | Full wizard incl. data scope/feeds, overrides UI, emergency isolation UI, boot integration | Not started |
| M6 | Evaluation, battery/CPU measurements, SBOM, release | Not started |

## 5. Evidence

Local (authoring container, no Android SDK/Google Maven access):
* `python3 -I handoff/tests/test_handoff.py` — 16/16 OK (handoff reference, unchanged).
* `:core:test` — 39/39 pass (JUnit 5): address/IDNA normalisation, validator ≡ SQL checks, precedence and
  determinism, unsupported rejection, iptables render/read-back round-trip, helper re-validation of
  tampered plans, request gate, reconciliation decisions, `RobustStats` golden cases ported 1:1 from the
  Python reference, and the helper engine end-to-end on `FakeIptables` (probe uses scratch chain only;
  netd/foreign OUTPUT rules untouched; IPv6 failure compensated; rollback; recovery without probe;
  probe-before-apply; revision conflict).

CI (GitHub Actions `.github/workflows/rootguard.yml`): handoff tests, strings check, core tests,
`assembleDebug`, exported Room schema, Robolectric app tests (schema equivalence vs
`control_v1.sqlite`, trigger-enforced constraints, controller integration incl. crash-mid-apply,
reboot, compensation, UID reuse, root denied; manifest/AIDL security; UI smoke), lint, unsigned release.
Results are recorded in §7 per run.

Device evidence: **none yet.** No host or container firewall command has been executed; all iptables
behaviour above is against the in-memory model.

## 6. Release gates still open (from docs/07_IMPLEMENTATION_ACCEPTANCE.md)

1. M1 device scenarios on an owner-controlled rooted device: Wi-Fi/mobile decisions, IPv4/IPv6, shared
   UID, UID reuse, interface switch, reboot, UI death, helper death, crash mid-apply, rollback,
   coexistence with netd (diff `iptables-save` before/after: only `RG_OUT` and one OUTPUT jump), whether
   existing TCP sessions stop, VPN path.
2. Encrypted control store (SQLCipher or field encryption with Keystore-wrapped key) before any release.
3. Room migration tests start with schema v2 (`MigrationTestHelper` against `app/schemas`); v1 has no
   migration path yet. `fallbackToDestructiveMigration` is never used.
4. Boot integration decision (opt-in after-unlock re-apply vs root-manager module) with a tested disable
   procedure.
5. Dependency audit/SBOM, licence decision for the app.

## 7. CI run log

| Commit | Run | Result |
|---|---|---|
| f8ce7ce | 39829… | cancelled (superseded by next push) |
| 7b3144c | [39831640268](https://github.com/ojalal87/blank-app/actions/runs/39831640268) | **success**: handoff 16/16, core 39/39, assembleDebug, Robolectric app tests (schema equivalence, constraints, controller integration, manifest/AIDL, UI smoke), lint 0 errors, unsigned release. Debug APK artifact `rootguard-debug-apk` (16.9 MB zip). |
