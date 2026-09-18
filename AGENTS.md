# AGENTS.md - FuelRoute (Android)

Android app (Kotlin + Jetpack Compose) that picks the cheapest-fuel route between alternatives from Google Routes API,
using per-segment speed and a vehicle consumption-vs-speed curve. Full design: `PLAN.md`.

## Environment (Windows, PowerShell 5.1)
- `JAVA_HOME` = `C:\Program Files\Android\Android Studio\jbr` (JDK 21). System PATH also has Oracle Java 24 first;
  Gradle uses `JAVA_HOME`, so this is fine. Never rely on bare `java` on PATH.
- `ANDROID_HOME` = `%LOCALAPPDATA%\Android\Sdk` (platform-tools, platforms;android-35, build-tools;35.0.0, cmdline-tools).
- Testing is on a **physical device** over USB/Wi-Fi debugging. No emulator (virtualization disabled in BIOS).
- If `adb`/`sdkmanager` are not found in a fresh shell, use full paths under `%LOCALAPPDATA%\Android\Sdk`.

## Commands (run from repo root)
- Build debug APK: `.\gradlew.bat assembleDebug`
- Install on device: `.\gradlew.bat installDebug`
- Unit tests (JVM, domain layer): `.\gradlew.bat testDebugUnitTest`
- Lint: `.\gradlew.bat lintDebug`
- Signing report (SHA-1 for API key restriction): `.\gradlew.bat signingReport`
- Devices: `adb devices -l`
- Launch: `adb shell am start -n com.fuelroute/.MainActivity`
- Logs: `adb logcat -s FuelRoute:* AndroidRuntime:E`
- Clear app data: `adb shell pm clear com.fuelroute`

Add `--console=plain` to gradle commands for cleaner output. First build downloads Gradle + deps and is slow (several minutes).

## Project conventions
- Package: `com.fuelroute`. Layers: `ui/` (Compose + ViewModels), `domain/` (pure Kotlin, no Android imports), `data/` (Retrofit, DataStore, Room), `nav/`, `di/`.
- `domain/` must stay free of Android dependencies so it is unit-testable on the JVM.
- Kotlin DSL (`*.gradle.kts`) + version catalog `gradle/libs.versions.toml`.
- API key lives in `local.properties` as `MAPS_API_KEY=...` and is injected via `secrets-gradle-plugin`. Never commit it; never hardcode it.
- Log tag: `FuelRoute`.
- UI strings in `res/values/strings.xml` (Hebrew primary, RTL supported).
- Prefer `StateFlow` + `collectAsStateWithLifecycle` for UI state; coroutines for async; no RxJava.
- Tests: JUnit5 + MockK. Real Routes API JSON responses are saved under `app/src/test/resources/fixtures/` and used for parser + fuel-model tests.

## Fuel model rules (see PLAN.md section 4)
- `consumption(v)` in L/100km = `ratedCombinedL100 * factor(v)`, linear interpolation over a speed curve, clamped to [10, 130] km/h.
- Per step: effective speed = free-flow speed (distance/staticDuration) scaled by congestion from `speedReadingIntervals`, then normalized so total time matches route `duration`.
- Cost = fuel liters * price + toll estimate. Ranking by total cost, optionally plus `minutes * valuePerMinute`.
