# Release Checklist — v0.4.0

Status legend: [x] done in this RC · [ ] required before a production release

## Build
- [x] Gradle wrapper (8.14.3) committed; build runs with `./gradlew` only.
- [ ] Add `distributionSha256Sum` to `gradle/wrapper/gradle-wrapper.properties` (value from
      https://gradle.org/release-checksums/ for 8.14.3 bin). Could not be fetched in the RC environment.
- [ ] Add Gradle dependency verification (`./gradlew --write-verification-metadata sha256 help`) and
      commit `gradle/verification-metadata.xml`.
- [x] `./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`
      runs in CI (`.github/workflows/oneplus-care-v04.yml`). See TEST_REPORT.md for results.
- [ ] Run `./gradlew :app:connectedDebugAndroidTest` on an Android 11+ device/emulator.
- [ ] Dependency vulnerability scan (e.g. `osv-scanner --lockfile=...` or OWASP Dependency-Check) clean.

## Signing (never commit credentials)
- [ ] Create an upload keystore outside the repo; enrol in Play App Signing.
- [ ] Provide `keystore.properties` locally or as CI secrets (`storeFile`, `storePassword`, `keyAlias`,
      `keyPassword`). It is git-ignored; without it `assembleRelease` produces an unsigned APK.
- [ ] `./gradlew :app:bundleRelease` and verify with `bundletool validate`.
- [ ] Archive `app/build/outputs/mapping/release/mapping.txt` with the release (crash deobfuscation).

## Configuration
- [x] `versionCode 4`, `versionName 0.4.0-rc1`. Bump to `0.4.0` for final.
- [x] `isDebuggable=false`, R8 + resource shrinking, Log.v/d/i stripped in release.
- [x] No cleartext, system CAs only, backup limited to settings.
- [ ] Play Console: All-files-access declaration (or ship a Play flavor without
      `MANAGE_EXTERNAL_STORAGE`), Data safety form ("no data collected/shared"), target API 36.

## Device verification (OnePlus / OxygenOS, plus one non-OnePlus device)
- [ ] Execute every section of TEST_PLAN.md on Android 11, 13/14 and 16 (minimum: one OxygenOS 14+ device).
- [ ] Safe Trash: move → kill app during move (developer option "Don't keep activities" + force stop) →
      reopen → item is either in trash or untouched in place, never lost.
- [ ] Restore with a same-name file present → restored as "name (restored).ext"; original untouched.
- [ ] Duplicate group fully selected → one copy always remains.
- [ ] MediaStore trash and restore dialogs; cancel changes nothing.
- [ ] Daily scan fires while charging (check History) and never moves files.
- [ ] Shizuku in ADB mode: diagnostics collected; in root mode: warning shown; revoke → graceful.
- [ ] TalkBack pass on Home, Cleaner, Trash; 200 percent font scale; Arabic RTL pass.
- [ ] Uninstall prompt offers "keep app data" (hasFragileUserData) on Android 10+.

## Documentation
- [x] TECHNICAL_AUDIT.md, CYBERSECURITY_AUDIT.md, ARCHITECTURE.md, UI_UX_SPEC.md, PERMISSIONS_AND_PRIVACY.md,
      TEST_REPORT.md, CHANGELOG.md, HANDOFF.md.
- [ ] Store listing copy reviewed for honesty (no "boost", "cooler", "speed up" claims).
