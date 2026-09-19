# Task 07 — Automatic logging & service robustness

**REMEDIATION items:** Phase 5 (5.1–5.6).
**Depends on:** card 03 (ObdTransport/ObdEngine API) and card 02 (active vehicle API used by the service).
**Touches:** `service/ObdLoggingService.kt`, `AndroidManifest.xml`, new `service/BluetoothAclReceiver.kt`,
`ui/stats/*`, `ui/settings/*`, `res/values/strings.xml`.

## Objective
Make logging actually *automatic* (not "only when you open the Stats tab") and keep the service alive with the screen
off, with a proper permission story.

## Current state
- `service/ObdLoggingService.kt`: `onStartCommand` → builds transport (simulated when address null) → `startForeground`
  → collects `engine.live` → notification; `START_NOT_STICKY`; no wake lock.
- `AndroidManifest.xml` already declares `BLUETOOTH*`, `FOREGROUND_SERVICE(_CONNECTED_DEVICE)`, `POST_NOTIFICATIONS`,
  `WAKE_LOCK` (unused), `ACCESS_*_LOCATION`.
- `StatsViewModel.autoConnect()` is only reachable from `StatsScreen` entry points; `connect(device)`, `connectDemo()`,
  `stop()` exist.

## Steps

1. **`BluetoothAclReceiver`** (new): on `BluetoothDevice.ACTION_ACL_CONNECTED`, if `autoConnect` setting is on and the
   device address equals `lastDeviceAddress` → call `ObdLoggingService.start(context, address)`. Register in the manifest
   with the matching `<intent-filter>`. (Bluetooth broadcasts requiring `BLUETOOTH_CONNECT` are an allowed
   background-FGS start trigger; `connectedDevice` type is correct for API 34+.)
2. **Service lifecycle**: `START_REDELIVER_INTENT`; acquire a `PARTIAL_WAKE_LOCK` (`FuelRoute:obd`, timeout 4 h, renew at
   each checkpoint) and release it in `onDestroy`. Notification tap → deep-link to the live dashboard (Stats tab).
   Add a "stale" indicator when `now - lastSampleTimestamp > 10 s`.
3. **Battery optimization**: helper to fire `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (needs
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission in manifest) → button in Settings with rationale.
4. **Permissions** (`ui/` composable `PermissionGate`): request `BLUETOOTH_CONNECT`/`SCAN` (for OBD) and
   `POST_NOTIFICATIONS`; location only from the Route tab. Denied → rationale + deep-link to app settings;
   permanently denied → disabled feature explained, app remains usable (no crash).
5. **Dashboard while driving**: keep-screen-on toggle (hold `FLAG_KEEP_SCREEN_ON` on the `LiveDashboard`/Stats live
   section), large-text layout, zero required interaction; one-line note in Settings about ELM327 battery drain.
6. **Strings**: add all new user-facing text to `res/values/strings.xml` (Hebrew).

## Verify
```
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat lintDebug --console=plain
```

## Manual checks (do if a device is available; otherwise report as not-run)
- `adb shell dumpsys deviceidle whitelist` lists the package after toggling on.
- Receiver is present under `dumpsys package com.fuelroute`.

## Done when
- Builds + lint green; receiver registered; permission gate wired; wake lock acquired/released around the run loop.

Do **not** commit.