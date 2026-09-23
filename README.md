# FuelRoute

An Android app that tells you which route to drive by **fuel cost**, not just time. It learns your car's real
fuel-consumption curve from an OBD-II dongle and combines it with live traffic/toll data from the Google Routes API to
rank alternative routes by what they actually cost in fuel (and tolls).

The UI is **Hebrew / RTL**. This README is for developers.

## The two halves

1. **Learn your car (OBD-II).** Connect a cheap ELM327 Bluetooth dongle, log continuous speed + fuel rate while you
   drive, and build a **personal consumption-vs-speed curve** that gets more accurate with every trip. No dependency on
   navigation — logging runs on its own in a foreground service.

2. **Pick the cheapest route.** For a chosen destination, fetch up to 3 alternative routes from the Routes API, fold in
   per-segment congestion, and price each route with `fuelL × price + tolls` using your learned curve (or a default
   curve until enough data is gathered). Cheapest and fastest are both surfaced, with the ₪/minute trade-off explicit.

## Features

- **OBD-II learning** over Bluetooth Classic SPP (`ELM327`) — speed, RPM, MAF/fuel-rate, coolant, fuel level, VIN.
- **Fuel-rate priority:** direct `5E` L/h > MAF `10` (gasoline AFR 14.7) > speed-density (`MAP`/`RPM`/`IAT`).
- **Personal consumption curve** blended from learned km-per-bin confidence + manual override + default curve.
- **Route ranking** by total cost (fuel + toll), with congestion modelling and a time-value (`₪/minute`) option.
- **Live dashboard** on the phone and on **Android Auto** (template-based, ~1 Hz) — speed, instant L/100, ₪/hour,
  trip cost.
- **History** of route searches paired with the actual measured drive, showing predicted-vs-actual ₪/L/min and tracking
  forecast accuracy; demo ("הדגמה") drives are clearly marked.
- **Refueling, calibration, favorites, backup/export, retention, per-vehicle stats** (multi-vehicle supported).

## Architecture

MVVM with strict layering (mirrors the design doc `PLAN.md` §6):

```
app/src/main/java/com/fuelroute/
├── ui/        Compose screens + ViewModels (route, stats, curve, refuel, vehicle, history, favorites, settings, debug, permission)
├── domain/    Pure Kotlin — no Android imports, fully JVM unit-tested (fuel model, learning, obd protocol, ranking, history)
├── data/      Retrofit, DataStore, Room (obd, routes, places, history, location, vehicle, price, refuel, backup, settings, db)
├── service/   ObdLoggingService (foreground, connectedDevice), Bluetooth ACL/Boot receivers, overlay, retention worker
├── car/       Android Auto Car App Library (CarAppService + Session + PaneTemplate dashboard)
├── nav/       Navigation host + Google Maps / Waze hand-off
└── di/        Hilt modules
```

**Key rule:** `domain/` stays free of Android dependencies so the whole fuel/OBD model is unit-testable on the JVM.

## Tech stack

- Kotlin 2.x, Jetpack Compose + Material 3, Material icons
- Hilt (DI), coroutines + `StateFlow` / `collectAsStateWithLifecycle`
- Retrofit + OkHttp + kotlinx.serialization (Routes/Places REST)
- Room (`exportSchema = true`, non-destructive migrations) + DataStore
- Google Maps SDK + `maps-compose`, Places SDK (New), FusedLocation
- Android Auto via `androidx.car.app` (templates only — **not** a Play-approvable category, personal sideload)
- JUnit 4 + MockK (JVM unit tests); ELM327 emulator for on-device testing without a car

## Prerequisites (Windows)

- JDK 24: `JAVA_HOME = C:\Program Files\Java\jdk-24` (the Gradle JVM is pinned in `gradle.properties`; don't rely on
  bare `java` on PATH).
- Android SDK 36 at `%LOCALAPPDATA%\Android\Sdk` (`platform-tools`, `platforms;android-36`, `build-tools;36.0.0`).
- A **physical device** over USB/Wi-Fi debugging. There is no emulator (virtualization is disabled in BIOS).
- **The repo path must stay ASCII-only** (currently `C:\dev\fuel`) — AGP and the Gradle test worker break on non-ASCII
  paths.

## Build & run

```powershell
.\gradlew.bat assembleSideloadDebug  # build debug APK (full-featured flavor)
.\gradlew.bat installSideloadDebug   # build + install on the connected device
.\gradlew.bat bundlePlayRelease       # signed Google Play bundle (see docs/play/README.md)
.\gradlew.bat testDebugUnitTest      # JVM unit tests (domain layer)
.\gradlew.bat lintDebug              # Android lint
.\gradlew.bat signingReport          # SHA-1 for the API-key restriction
adb devices -l
adb shell am start -n com.fuelroute/.MainActivity
adb logcat -s FuelRoute:* AndroidRuntime:E
```

Add `--console=plain` for cleaner output. The first build downloads Gradle + dependencies and is slow.

A pre-push hook runs `testSideloadDebugUnitTest testPlayDebugUnitTest lintSideloadDebug lintPlayDebug` before every `git push` (install via
`scripts/install-pre-push-hook.ps1`; skip with `git push --no-verify`). Helper scripts live in `scripts/`, the ELM327
TCP emulator in `tools/elm327_emulator.py`.

## Configuration

The Google API key is read from `local.properties` (git-ignored) as `MAPS_API_KEY=...` and becomes a `BuildConfig`
field + manifest placeholder — never commit it, never hardcode it. Restrict the key to package `com.fuelroute` +
your signing SHA-1 (`.\gradlew.bat signingReport`). Routes/Places REST calls carry
`X-Android-Package`/`X-Android-Cert` headers so Android-restricted keys work for web services.

## Android Auto

The car screen is a read-only live dashboard fed by the existing OBD engine — it never owns the connection.
**Distribution is personal sideload only**: enable Android Auto developer mode → **"Unknown sources"**, install a
**debug** build (its host validator allows all hosts), and test with the Desktop Head Unit:

```powershell
sdkmanager --install "extras;google;auto"   # in Android Studio or cmdline-tools
adb forward tcp:5277 tcp:5277
& "$env:LOCALAPPDATA\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
```

A generic vehicle-dashboard app is not a Play-approvable car-app category, so don't plan on Play distribution.

## Documentation

- `PLAN.md` — the full design (Hebrew): OBD protocol, fuel model, Routes API, architecture, risks.
- `AGENTS.md` — environment, commands, and code conventions for AI agents working in this repo.

## License

Personal project; not published to a store.