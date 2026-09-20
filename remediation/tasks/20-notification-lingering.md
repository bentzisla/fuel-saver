# Task 20 — Stop the lingering "מתעד צריכת דלק" notification

**User request:** #3 — the OBD foreground-service notification shows ~80% of the time even when far from the car and the
dongle is not connected.
**Depends on:** none (builds on card 14 auto-logging + card 03 engine).
**Touches:** `data/obd/BluetoothClassicTransport.kt`, `data/obd/ObdEngine.kt`, `service/ObdLoggingService.kt`,
`service/BluetoothAclReceiver.kt`, `res/values/strings.xml`.

## Objective
The service must drop the notification and stop itself when it fails to actually connect and read OBD data within a
short window, instead of lingering.

## Root cause (diagnosed — verify as you read)
1. **No connect timeout.** `BluetoothClassicTransport.connect()` calls `BluetoothSocket.connect()` with **no timeout**;
   when the dongle is absent the call can block for tens of seconds (or longer on some ROMs). During that time the
   foreground notification is up.
2. **Eager auto-start.** Card 14's `BluetoothAclReceiver` starts the service on `ACTION_STATE_CHANGED` → `STATE_ON`
   whenever a bonded ELM-like device exists, and on `ACTION_ACL_CONNECTED` — so turning Bluetooth on (or any ELM-ish
   pairing) spawns the service, often with no dongle actually present.
3. **Stop gating.** `ObdLoggingService` only stops on `Error`/`Disconnected` **after** `sawActive == true`. A hang in
   `connect()` means the engine never emits a terminal state, so the service never stops.

## Steps
1. **Connect timeout.** In `BluetoothClassicTransport.connect()`, bound the socket `connect()` with
   `withTimeout(CONNECT_TIMEOUT_MS)` (default ~15 s) — and/or set a connect timeout on the underlying `java.net.Socket`
   via the same reflection helper used for `soTimeout`. On timeout/IO, return `Result.failure` promptly.
2. **Terminal failure in the engine.** In `ObdEngine.start()`, if connect fails (or a `CONNECT_TIMEOUT_MS` passes
   without reaching `Connected`), set `lastError` and emit a terminal `Error` (already mostly wired) — ensure the run
   loop is never entered.
3. **Service stops on failure regardless of `sawActive`.** Replace the `sawActive` gate with a short **startup grace**
   (e.g. don't stop during the first `GRACE_MS = 2_000` ms, to survive the initial `Disconnected → Connecting`
   transient), then stop on `Error`/`Disconnected` whether or not data was ever read. Call
   `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()`.
4. **Less eager auto-start.** In `BluetoothAclReceiver`, only auto-start when there is a resolved target device
   (`lastDeviceAddress` or a bonded ELM name match) AND it is actually connected; reuse the ~5 s debounce. Do not start
   merely because Bluetooth turned on with a paired-but-absent device.
5. Keep the manual demo (address == null) startable, and always stoppable.

## Tests
Extract any pure decision logic into a helper (e.g. `domain/obd/ConnectPolicy.kt`:
`shouldAutoStop(status, elapsedMs, sawData)` + the debounce already in `ObdDeviceMatcher`/`AutoConnectDebounce`) and
unit-test it.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat lintDebug --console=plain
```
Manual (device): dongle off → notification appears only on a real attempt, then disappears within ~30 s; turning
Bluetooth on with no dongle does NOT summon the notification.

## Done when
No lingering notification when the dongle is absent; the service self-stops after a failed/hung connect.

Do **not** commit.
