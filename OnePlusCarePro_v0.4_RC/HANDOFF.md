# Handoff — OnePlus Care Pro v0.4 RC

## Where things are
* Source: `OnePlusCarePro_v0.4_RC/` (modules `:core`, `:app`). Start with `ARCHITECTURE.md`.
* CI: `.github/workflows/oneplus-care-v04.yml` builds, tests, lints and produces the debug APK artifact
  (`oneplus-care-debug-apk`) plus reports (`oneplus-care-reports`).
* Results of this RC: `TEST_REPORT.md`. Security findings: `CYBERSECURITY_AUDIT.md`.

## What is done
* Full audit (technical + security) of v0.3 with fixes implemented, not just recommended.
* Domain logic moved to a tested pure-Kotlin module (trash journal, path policy, duplicates, rules,
  network diagnosis, diagnostics redaction, Shizuku allow-list, v0.3 migration).
* Material 3 AMOLED redesign of every required area, English + Arabic.
* Real Android build in CI (the authoring environment could not reach Google Maven).

## Remaining risks (highest first)
1. **No physical-device run yet.** Compose screens, system dialogs, OxygenOS background behaviour and
   Shizuku were verified by compilation, lint and Robolectric only. Execute `TEST_PLAN.md` on a OnePlus
   device before any release.
2. **Safe Trash location is app-specific external storage.** It is removed on uninstall unless the user
   keeps app data (`hasFragileUserData` prompts on Android 10+). The UI states this. A future option is to
   move trash into a user-visible folder via SAF, at the cost of rename-speed moves.
3. **Cross-volume moves** (when FUSE refuses rename into `Android/data`) copy then delete; very large files
   take time. The journal makes this crash-safe, but progress per file is not shown.
4. **System-trash result lost on process death** while the MediaStore dialog is open (the user's choice is
   still applied by Android; the item just will not be listed in the app's Trash).
5. **Play policy:** All files access needs a Play declaration; prepare a flavor without it if rejected.
6. **Version pins** (AGP 8.13.2, Kotlin 2.3.21, Compose BOM 2026.06.00, etc.) were carried from v0.3 and
   confirmed to resolve in CI; no CVE scan was run (see RELEASE_CHECKLIST.md).
7. **Gradle wrapper checksum** not pinned (could not be fetched here).

## Next priorities
1. Device test pass (TEST_PLAN.md) on OxygenOS 14/15/16 and one non-OnePlus device; fix findings.
2. Add `distributionSha256Sum`, dependency verification metadata and an OSV scan to CI.
3. Release signing via Play App Signing; `bundleRelease`; archive R8 mapping.
4. Optional: per-file progress for long cross-volume moves; Compose previews per screen with
   the existing fixtures; screenshot tests (Roborazzi/Paparazzi) for LTR and RTL.
5. Optional product work (only if it stays honest): storage breakdown by category from MediaStore,
   "large app caches" list linking to App info (Android does not let third-party apps clear other apps'
   caches since Android 6 — do not add a fake "clear all caches" button).
