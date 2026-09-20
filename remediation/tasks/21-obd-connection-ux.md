# Task 21 — OBD connection controls (connect / disconnect / reset + status)

**User request:** #2 — OBD connection is not user-friendly: hard to control, stop, reset; dongle rarely connects
immediately.
**Depends on:** none (builds on `ObdEngine`/`ObdStatus` + card 20's connect-timeout).
**Touches:** `data/obd/ObdEngine.kt`, `ui/stats/StatsScreen.kt` + `StatsViewModel.kt`, `service/ObdLoggingService.kt`,
`res/values/strings.xml`.

## Objective
A clear, controllable connection experience: explicit Connect / Disconnect / Reset ("אפס") actions, a real-time status
with a reason, and an easy retry — no app restarts.

## Current state (read these)
- `StatsViewModel` has `connect(device)`, `connectDemo()`, `stop()`; `ObdEngine` has `start`/`stop`;
  `LiveObdState(status, deviceName, lastError, supportedPids, vin, ...)`.
- There is no explicit disconnect-that-clears-state or reset, and no "why isn't it connected" affordance beyond
  `lastError` (which card 03/14 surface only partially).

## Steps
1. Add to `ObdEngine`: `fun reset()` — `stop()` + clear the last-error/`sawActive`-style state so an immediate
   `start()` is allowed. Expose a clean `disconnect()` that stops the run loop without starting it again.
2. Surface in `StatsViewModel`: `connect()`, `disconnect()`, `reset()` + a `connectDemo()` that is clearly labelled
   "הדגמה". Wire buttons in the Stats live section.
3. Status line: always explain state — "מנותק", "מתחבר…", "מחובר (שם מכשיר)", or the error reason from `lastError`
   (e.g. "לא נמצא דונגל", "בדיקת פרוטוקול…", "שגיאת ATZ"). Show `vin`/`supportedPids` once connected ("מחובר ל־VIN …").
4. Retry: a "נסה שוב" button that calls `reset()` + `connect()`; show it prominently after a failed attempt rather than
   making the user hunt for it.
5. Ensure card 20's connect timeout feeds a clean error here (no silent hang).

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```
Manual (device/car): connect → disconnect → reset → reconnect all respond immediately and the status line always
explains the current state.

## Done when
A stuck/failed connection can be reset and retried from the UI without restarting the app.

Do **not** commit.
