# Task 45 — Android Auto: bring the dashboard up for real

**User request:** #7 — Android Auto support STILL DOESN'T WORK (most important feature).
**Depends on:** cards 15/26/42 (skeleton + host-validator fix + lifecycle diagnostics already landed).
**Touches:** `car/*`, `AndroidManifest.xml`, `res/xml/automotive_app_desc.xml`, `ui/settings/SettingsScreen.kt`,
`res/values/strings_car.xml` (create this file), possibly `gradle/libs.versions.toml` (coordinate with orchestrator —
do NOT edit it directly).

## Objective
Find and remove whatever is still blocking the dashboard from appearing on a real Android Auto (projected) head unit /
Desktop Head Unit. This is a hands-on bring-up: prove each layer with a log marker or fix it.

## Current state (read these)
- `car/FuelRouteCarAppService` (POI category, not NAVIGATION), `car/FuelRouteSession`, `car/DashboardScreen`
  (PaneTemplate, ~1 Hz). DEBUG builds use `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR`; release uses a hardcoded
  `ALLOWED_HOSTS` list of `(package, SHA-256)` pairs for the projection + automotive hosts.
- `AndroidManifest.xml`: service exported=true with `androidx.car.app.CarAppService` + `androidx.car.app.category.POI`,
  `com.google.android.gms.car.application` -> `automotive_app_desc.xml`, and `androidx.car.app.minCarApiLevel=1`.
- `automotive_app_desc.xml`: `<automotiveApp><uses name="template"/></automotiveApp>`.
- `gradle/libs.versions.toml`: `carApp = "1.7.0"` (`androidx.car.app:app` + `:app-projected`).
- Instrumentation already logs "car service created" / "car session created" / "car dashboard screen created" and
  persists `carLastSeen`/`carLastHost` in Settings.

## Steps
1. **Establish the failure point.** Read the Settings "car last-seen" (card 42) value and any `adb logcat -s FuelRoute:*`
   markers. Decide which of these is true and fix it:
   - **Host never bound** → "Unknown sources" is off / app not in the AA launcher, or the release host-validator
     digests are wrong/stale. For a personal sideload you should be on a DEBUG build (allow-all). If the user runs a
     RELEASE build, the hardcoded `ALLOWED_HOSTS` digests must match the current Android Auto host cert — use web
     search to verify the current `com.google.android.projection.gearhead` / automotive templates host digests and
     update `ALLOWED_HOSTS`. (Never use `hosts_allowlist_sample`.)
   - **Host bound but session/template rejected** → category mismatch or `PaneTemplate` unsupported by the host. POI is
     correct for a dashboard; if the host requires it, ensure `automotive_app_desc.xml` is exactly
     `<uses name="template"/>`.
2. **Manifest hygiene.** Verify the `intent-filter` action is exactly `androidx.car.app.CarAppService` and the category
   `androidx.car.app.category.POI`. Remove `androidx.car.app.minCarApiLevel` if the Car App Library 1.7 no longer reads
   it (check the lib docs via web search) — a stray/legacy meta-data is a known cause of the app being ignored.
3. **Verify the Car App Library version.** Confirm `androidx.car.app:app:1.7.0` is the right artifact/version (web
   search). If a newer stable exists and is the source of a known fix, report the exact version to the orchestrator to
   bump `libs.versions.toml`; do not edit the catalog yourself.
4. **Runtime self-check in Settings.** Extend the existing Android Auto Settings card to show: installed build type
   (DEBUG/RELEASE), `carLastSeen`, `carLastHost`, and a one-line verdict ("המכונית חוברה / לא חוברה — הפעל Unknown
   sources"). New strings → `res/values/strings_car.xml`.
5. **Document the exact DHU procedure** in your report (enable AA developer mode + "Unknown sources",
   `adb forward tcp:5277 tcp:5277`, install `desktop-head-unit.exe`, and the expected logcat lines), even though you
   cannot run it here.

## Verify
```
# run by the orchestrator:
.\gradlew.bat testDebugUnitTest lintDebug --console=plain
.\gradlew.bat assembleDebug --console=plain
```
DHU/device: not runnable here — leave the exact procedure + expected markers in your report.

## Done when
Every code-level blocker (host digests, category, manifest meta-data, library version) is removed or verified, the
Settings card can self-diagnose on-device, and there is a concrete DHU test procedure.

Do **not** commit.
