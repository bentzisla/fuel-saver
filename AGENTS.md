# AGENTS.md - FuelRoute (Android)

Android app (Kotlin + Jetpack Compose) that picks the cheapest-fuel route between alternatives from Google Routes API,
using per-segment speed and a vehicle consumption-vs-speed curve. Full design: `PLAN.md`.

## Environment (Windows, PowerShell 5.1)
- `JAVA_HOME` = `C:\Program Files\Java\jdk-24`; Gradle JVM pinned via `org.gradle.java.home` in `gradle.properties`.
  System PATH may have other JDKs (e.g. Android Studio jbr); never rely on bare `java` on PATH.
- `ANDROID_HOME` = `%LOCALAPPDATA%\Android\Sdk` (platform-tools, platforms;android-36, build-tools;36.0.0, cmdline-tools).
- Testing is on a **physical device** over USB/Wi-Fi debugging. No emulator (virtualization disabled in BIOS).
- If `adb`/`sdkmanager` are not found in a fresh shell, use full paths under `%LOCALAPPDATA%\Android\Sdk`.
- Project MUST live on an ASCII path (see note below).

## Commands (run from repo root)
- Build debug APK: `.\gradlew.bat assembleDebug`
- Install on device: `.\gradlew.bat installDebug`
- Unit tests (JVM, domain layer): `.\gradlew.bat testDebugUnitTest`
- Lint: `.\gradlew.bat lintDebug`
- Signing report (SHA-1 for API key restriction): `.\gradlew.bat signingReport`
- Google Play bundle (signed with the upload key from `keystore.properties`): `.\gradlew.bat bundlePlayRelease` -> `app/build/outputs/bundle/playRelease/`
- Full-featured sideload release APK: `.\gradlew.bat assembleSideloadRelease`
- Devices: `adb devices -l`
- Launch: `adb shell am start -n com.fuelroute/.MainActivity`
- Logs: `adb logcat -s FuelRoute:* AndroidRuntime:E`
- Clear app data: `adb shell pm clear com.fuelroute`

Add `--console=plain` to gradle commands for cleaner output. First build downloads Gradle + deps and is slow (several minutes).

A pre-push hook is installed (`.git/hooks/pre-push`) that runs `.\gradlew.bat testSideloadDebugUnitTest testPlayDebugUnitTest lintSideloadDebug lintPlayDebug` before every push (install script: `scripts/install-pre-push-hook.ps1`; skip with `git push --no-verify`).

## Project conventions
- Package: `com.fuelroute`. Layers: `ui/` (Compose + ViewModels), `domain/` (pure Kotlin, no Android imports), `data/` (Retrofit, DataStore, Room), `nav/`, `di/`, `car/` (Android Auto Car App Library templates).
- `domain/` must stay free of Android dependencies so it is unit-testable on the JVM.
- Kotlin DSL (`*.gradle.kts`) + version catalog `gradle/libs.versions.toml`.
- API key lives in `local.properties` as `MAPS_API_KEY=...` and is read in `app/build.gradle.kts` as a
  `BuildConfig` field + manifest placeholder (no secrets plugin). Never commit it; never hardcode it.
- Log tag: `FuelRoute`.
- UI strings in `res/values/strings.xml` (Hebrew primary, RTL supported).
- Prefer `StateFlow` + `collectAsStateWithLifecycle` for UI state; coroutines for async; no RxJava.
- Tests: JUnit4 + MockK. Real Routes API JSON responses are saved under `app/src/test/resources/fixtures/` and used for parser + fuel-model tests.
- WARNING: the project dir must remain **ASCII-only** (currently `C:\dev\fuel`).
  `AGP` refuses non-ASCII paths and Gradle's test worker classpath argfile drops non-ASCII bytes, causing
  `ClassNotFoundException` for all unit tests.

## Fuel model rules (see PLAN.md section 4)
- `consumption(v)` in L/100km = `ratedCombinedL100 * factor(v)`, linear interpolation over a speed curve, clamped to [10, 130] km/h.
- Effective curve = `CurveBlender`: learned (OBD) curve weighted by km-in-bin confidence `w = km/(km+20)`, falling back to manual curve, then default curve.
- Per step: effective speed = free-flow speed (distance/staticDuration) scaled by congestion from `speedReadingIntervals`, then normalized so total time matches route `duration`.
- Cost = fuel liters * price + toll estimate. Ranking by total cost, optionally plus `minutes * valuePerMinute`.

## OBD-II rules (see PLAN.md section 5)
- ELM327 over Bluetooth Classic SPP (UUID `00001101-0000-1000-8000-00805F9B34FB`). Transport is behind `data/obd/ObdTransport` interface; `FakeObdTransport` replays recorded scripts, `SimulatedObdTransport` runs a live demo drive-cycle, and `tools/elm327_emulator.py` is an external TCP emulator.
- Protocol parsing (`domain/obd/ElmProtocol`, `PidParser`) is pure Kotlin and fully unit-tested; it must tolerate `SEARCHING...`, `NO DATA`, `?`, `UNABLE TO CONNECT`, echo, and multi-frame responses.
- Fuel rate priority: PID `5E` (direct L/h) > MAF `10` (`MAF/AFR`, gasoline AFR 14.7, density 745 g/L) > speed-density (`MAP 0B`, `RPM 0C`, `IAT 0F`, needs displacement).
- Learning: 5 km/h speed bins; per bin accumulate `distanceKm`, `fuelL`, `seconds`, `samples` in Room (`speed_bin_stats`). Bin 0 with RPM > 0 = idle L/h. Samples with dt > 2 s or cold engine (< 60 C) are excluded from the curve.
- Logging runs in `service/ObdLoggingService` (Foreground Service, type `connectedDevice`) independent of routing.
- Recorded ELM sessions for tests/fixtures go under `app/src/test/resources/fixtures/obd/`.
- Android Auto: `car/FuelRouteCarAppService` renders a live OBD dashboard (templates only, ~1 Hz). Test via the Desktop Head Unit (DHU), not the emulator: `sdkmanager --install "extras;google;auto"`, enable Android Auto developer mode on the phone, `adb forward tcp:5277 tcp:5277`, run `desktop-head-unit.exe`. Android Auto only runs apps from a trusted store in a real car, so a sideloaded APK will not show there: use the Play internal-testing track for in-car use (see `docs/play/README.md`); the DHU accepts debug builds.

## Build flavors
- `sideload` (default for deploy scripts): everything, including the floating overlay and the battery-optimization prompt.
- `play`: same app minus `SYSTEM_ALERT_WINDOW` and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Play policy). Android Auto is kept in both. Gate flavor-specific behavior with `BuildConfig.SIDELOAD_FEATURES`.
- Plain `installDebug` would install both flavors (same applicationId): always use `installSideloadDebug`.

## Backup / export
- Backup/export uses the Android Storage Access Framework (document intents); its dialog lives in Settings.
