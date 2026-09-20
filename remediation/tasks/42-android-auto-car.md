# Task 42 — Android Auto on the user's actual vehicle

**User request (round 3):** #8 — still not working on the user's vehicle.
**Depends on:** card 26 (host validator/category fix + DHU steps).
**Touches:** `car/*` (diagnostics/logging), `AndroidManifest.xml` if needed, `res/xml/automotive_app_desc.xml`.

## Objective
Get the dashboard to actually appear on the user's Android Auto screen, and make the failure diagnosable if it still
doesn't.

## Current state (read these)
- Card 26 switched to a real host allowlist (Android Auto `gearhead` + Automotive templates host), POI category, and a
  `Log.i("FuelRoute", "car session created")` marker in `FuelRouteSession.onCreateScreen`.
- The car app needs "Unknown sources" enabled on the phone's Android Auto developer settings, or the app won't be
  listed at all — this is the most common "not showing" cause and is not visible from the phone UI.

## Steps
1. Add a persistent, user-visible diagnostic: persist a `car app last-seen` state (Settings footer or Stats) so the
   user can tell whether the phone ever bound to the car host — without `adb`.
2. Log at every car lifecycle point (service bound, session created, screen created, host rejected) with `FuelRoute`.
3. Write/refine the exact first-run checklist the user must do on the phone (enable Android Auto → Developer mode →
   Unknown sources → reconnect the car), and surface a brief in-app "אנדרואיד אוטו" help card in Settings with those
   steps.
4. If a manifest/validator issue remains (e.g. minCarApiLevel vs the head unit, or a missing `<uses-feature>`), fix it.

## Verify
```
.\gradlew.bat assembleDebug --console=plain
```
Manual (car): the app appears in the Android Auto launcher and the dashboard shows; if not, `adb logcat -s FuelRoute:*`
shows the exact lifecycle/validation reason.

## Done when
There is a concrete diagnostic + first-run checklist, and any remaining code-level blocker is removed. (Final
acceptance still requires the physical car/DHU.)

Do **not** commit.