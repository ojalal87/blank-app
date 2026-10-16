# Building OnePlus Care Pro v0.4 RC

Requirements: JDK 17+, Android SDK with platform 36 (Android Studio or `sdkmanager "platforms;android-36"`),
internet access to `dl.google.com`/`maven.google.com`, Maven Central and the Gradle plugin portal.

```bash
cd OnePlusCarePro_v0.4_RC
./gradlew :core:test                 # pure JVM domain tests (no Android SDK needed for this module's code)
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk  (applicationId *.debug)
./gradlew :app:testDebugUnitTest     # Robolectric, WorkManager and Compose UI tests
./gradlew :app:lintDebug             # app/build/reports/lint-results-debug.html
./gradlew :app:assembleRelease       # R8; unsigned unless keystore.properties exists
./gradlew :app:connectedDebugAndroidTest   # needs a device/emulator (Android 11+ for the MediaStore test)
```

Strings are generated: edit `tools/strings_part*.py`, then run `python3 -I tools/gen_strings.py .`
(the CI fails if the committed XML differs).

Release signing: create `keystore.properties` next to `settings.gradle.kts` (git-ignored):

```properties
storeFile=/absolute/path/upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

CI: `.github/workflows/oneplus-care-v04.yml` runs all of the above on every push touching this folder and
uploads the debug APK and reports as workflow artifacts.
