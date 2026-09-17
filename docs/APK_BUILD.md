# APK build and verification

## Local
The project can be opened in Android Studio and built with Gradle 8.9 + JDK 17.

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## GitHub Actions
Every push to `main` runs `.github/workflows/android-apk.yml`. The workflow builds the APK, runs unit tests, calculates SHA-256 and uploads the APK as a 30-day Actions artifact.

## Device smoke test
1. Install the debug APK on a physical Android device.
2. Grant microphone and notification permissions when requested.
3. Connect a wired, Bluetooth or USB headset.
4. Start the engine.
5. Confirm the status becomes `ÇALIŞIYOR` and at least one supported effect is shown as `AÇIK` when the device exposes it.
6. Speak near the microphone and verify the dBFS meter changes.
7. Stop the engine and verify audio resources are released.

This release is software noise processing. It is not a claim of hardware ANC and it does not promise a fixed percentage of noise reduction without device measurements.
