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
- Devices: `adb devices -l`
- Launch: `adb shell am start -n com.fuelroute/.MainActivity`
- Logs: `adb logcat -s FuelRoute:* AndroidRuntime:E`
- Clear app data: `adb shell pm clear com.fuelroute`

Add `--console=plain` to gradle commands for cleaner output. First build downloads Gradle + deps and is slow (several minutes).

A pre-push hook is installed (`.git/hooks/pre-push`) that runs `.\gradlew.bat testDebugUnitTest lintDebug` before every push (install script: `scripts/install-pre-push-hook.ps1`; skip with `git push --no-verify`).

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
- Android Auto: `car/FuelRouteCarAppService` renders a live OBD dashboard (templates only, ~1 Hz). Test via the Desktop Head Unit (DHU), not the emulator: `sdkmanager --install "extras;google;auto"`, enable Android Auto developer mode on the phone, `adb forward tcp:5277 tcp:5277`, run `desktop-head-unit.exe`. Play Store will not approve a generic vehicle-dashboard category → sideload only (Android Auto "Unknown sources").

## Remediation work (sub-agents)
- The canonical project location is **`C:\dev\fuel`** (moved off the OneDrive-linked profile path in Phase 0 of `REMEDIATION.md`). Keep the path ASCII-only.
- The remediation is split into self-contained task cards under `remediation/tasks/*.md`, orchestrated by `remediation/RUNBOOK.md`. Each card lists its `Objective`, `Depends on`, `Touches`, `Steps`, and `Verify` (exact commands).
- When dispatching cheap sub-agents: give one sub-agent exactly one card, and tell it to read that card first. Do not run multiple `gradlew` builds concurrently against the same repo — do code edits in parallel but run the build/test gate serially.
- Every item in `REMEDIATION.md` has a `- [ ]` checkbox in a task card; a sub-agent marks its own checkboxes done but never commits (the orchestrator commits).
- Backup/export feature (REMEDIATION 1.5) uses Document intents; its dialog lives in Settings.
