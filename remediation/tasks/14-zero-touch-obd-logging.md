# Task 14 — Zero-touch OBD: connect and record with no user interaction

**User request:** #4 — "automatic connection to the OBD when it's available and recording data automatically".
**REMEDIATION items:** 8.4 (completes Phase 5; extends card 07).
**Depends on:** card 07 (`BluetoothAclReceiver`, wake lock, `START_REDELIVER_INTENT`, permission gate) and card 03
(reconnect/backoff + ignition-off detection). **Run after 07.**
**Touches:** `service/BluetoothAclReceiver.kt`, `service/BootReceiver.kt` (new), `service/ObdLoggingService.kt`,
`data/settings/SettingsRepository.kt`, `ui/settings/*`, `ui/stats/*`, `AndroidManifest.xml`, `res/values/strings.xml`.

## Card 07 already did this — do NOT redo it
07 lands: `ACTION_ACL_CONNECTED` receiver gated on `autoConnect` + `lastDeviceAddress`, `PARTIAL_WAKE_LOCK`,
`START_REDELIVER_INTENT`, notification deep-link, battery-optimization prompt, `PermissionGate`.
Card 03 lands: reconnect with backoff, and ignition-off detection (rpm-null 60 s / voltage < 11.5 V).

This card closes the remaining gaps that stop it from being genuinely zero-touch.

## Objective
The user plugs the dongle in (or just starts the car with it permanently plugged) and **never touches the phone**:
logging starts, survives drops, and stops cleanly when the engine goes off — including after a phone reboot and when
they have never opened the app that day.

## Steps

1. **Works before a device was ever picked.** 07's receiver only fires when the connected address equals
   `lastDeviceAddress`. On first ever use that is null, so nothing happens. Add resolution order:
   `lastDeviceAddress` → else a bonded device whose name matches a known ELM pattern
   (`OBD`, `ELM327`, `Vgate`, `viecar`, `KONNWEI`, case-insensitive) → else ignore.
   Put the matcher in a **pure** function (`domain/obd/ObdDeviceMatcher.kt`) and unit-test it.
   When auto-resolved, persist it as `lastDeviceAddress`.
2. **Survive a phone reboot.** New `service/BootReceiver.kt` on `ACTION_BOOT_COMPLETED` (+
   `RECEIVE_BOOT_COMPLETED` permission): do **not** start a foreground service directly from boot (it will be
   blocked / wasteful) — instead just re-arm state so the next `ACL_CONNECTED` starts logging. Verify the ACL
   receiver is manifest-registered (it must be, to fire while the app is dead).
3. **Re-arm after Bluetooth is toggled.** Listen for `BluetoothAdapter.ACTION_STATE_CHANGED` → `STATE_ON` and, if the
   target device is already connected at that moment, start the service (an ACL broadcast will not be re-sent for an
   already-connected device).
4. **Stop cleanly, don't spin.** On `ACTION_ACL_DISCONNECTED` for the target device → stop the service (flush bins +
   close the open trip via card 03's `TripRecorder`). Combined with card 03's ignition-off rule, the service must not
   stay alive on a powered-but-idle dongle. Guard against a start/stop loop with a short debounce
   (e.g. ignore a re-start within ~5 s of a stop).
5. **`autoConnect` on by default + honest first-run.** Default `autoConnect = true` in `AppSettings`. Because
   auto-start needs `BLUETOOTH_CONNECT` (+ notifications), add a one-time Settings/Stats card explaining what will be
   logged and a single "הפעל רישום אוטומטי" button that requests the permissions and, if needed, the
   battery-optimization exemption. If permissions are missing, auto-connect must degrade silently (log + a Settings
   hint), never crash or nag on every boot.
6. **Make the state visible.** In Settings/Stats show: auto-logging on/off, the resolved device name/address, last
   auto-start time, and the last failure reason (reuse `LiveObdState.lastError` from card 00a). A user must be able to
   tell *why* nothing was recorded without `adb`.

## Tests (JUnit 4)
- `ObdDeviceMatcher`: matches the listed ELM name patterns case-insensitively; ignores unrelated bonded devices
  (headphones, speakers); prefers `lastDeviceAddress` over a name match.
- Debounce logic: a stop immediately followed by an ACL start does not re-enter the run loop (pure helper + virtual time).
- `autoConnect = false` → the receiver's decision function returns "do nothing".

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat lintDebug --console=plain
```

## Done when (device/car acceptance — the important part)
- Phone locked, app never opened: powering the dongle starts the notification and `adb logcat -s FuelRoute:*` shows
  samples; a trip appears in the DB afterwards.
- Reboot the phone, then power the dongle → logging still starts.
- Turn the engine off → service stops by itself (no overnight battery drain, no endless reconnect).
- If you cannot reach the car, report exactly which checks were not run.

Do **not** commit.
