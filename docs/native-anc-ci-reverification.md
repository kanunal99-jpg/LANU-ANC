# Native ANC CI reverification

The native ANC migration has been applied to `main` in commit `b65eefcec8897f43f8affe8d014d546a8e71fbc4`.

This file intentionally triggers the Android APK workflow so the migrated Kotlin/JNI/C++ audio path is compiled and unit-tested before further physical-device validation.
