# Task 26 — Get the Android Auto dashboard actually showing

**User request:** #1 — Android Auto isn't working (maybe not supported on some screens?).
**Depends on:** card 15 (car app skeleton exists).
**Touches:** `car/*`, `AndroidManifest.xml`, `res/xml/automotive_app_desc.xml`.

## Objective
The car dashboard (card 15) doesn't appear on the car screen. Debug and fix it so it shows on a real head unit /
Desktop Head Unit.

## Current state (read these)
- `car/FuelRouteCarAppService` (NAVIGATION category), `car/FuelRouteSession`, `car/DashboardScreen` (PaneTemplate).
- `AndroidManifest.xml` declares the service + `automotive_app_desc` + `androidx.car.app.minCarApiLevel=1`.
- Card 15 used `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` in DEBUG but `hosts_allowlist_sample` (the **car-samples**
  allowlist) otherwise — that sample list blocks real hosts.

## Steps
1. **Fix the host validator** for non-debug builds: do NOT use `androidx.car.app.R.array.hosts_allowlist_sample`.
   Keep `ALLOW_ALL` gated behind `BuildConfig.DEBUG` (this app is sideload-only, so that's correct), and for release use
   a real allowlist or a documented stub. The sample-list reference is a real bug.
2. **Category check:** `NAVIGATION` requires a navigation flow; if the host rejects it, fall back to `POI`. Confirm
   `automotive_app_desc.xml` contains `<uses name="template"/>`.
3. **Log marker:** add a `Log.i("FuelRoute", "car session created")` in `FuelRouteSession.onCreateScreen` so the user
   can confirm in `adb logcat` that the host is invoking the app.
4. **Write the exact DHU verification** for the user (do this even though you can't run it here): enable Android Auto
   developer mode + "Unknown sources" on the phone, `adb forward tcp:5277 tcp:5277`, install + run
   `desktop-head-unit.exe` (from `extras;google;auto`), and what log line confirms the dashboard was reached.

## Verify
```
.\gradlew.bat assembleDebug --console=plain
```
DHU/device check: not runnable here — leave exact steps and expected log output in your report.

## Done when
Host-validator/category issues are fixed and there's a concrete DHU test procedure; any code-level "not showing" cause
is removed.

Do **not** commit.
