# Task 34 — OBD disconnect must fully stop (no reconnect re-arm)

**User request (round 3):** #1a — clicking "התנתק" doesn't stop the app from trying to connect.
**Depends on:** card 21 (disconnect/reset UI), card 14 (auto-connect receiver).
**Touches:** `service/ObdLoggingService.kt`, `service/BluetoothAclReceiver.kt`, `data/obd/ObdEngine.kt`,
`ui/stats/StatsViewModel.kt`, `data/settings/SettingsRepository.kt` (a latch), `res/values/strings.xml`.

## Root cause (diagnosed)
A user-initiated disconnect (`StatsViewModel.disconnect()` → `engine.disconnect()` + `ObdLoggingService.stop()`) is not
"sticky". The engine job is cancelled, but: (1) `ObdLoggingService` returns `START_REDELIVER_INTENT` for start intents,
so a re-delivered start intent re-runs `startLogging`; (2) `BluetoothAclReceiver` / `autoConnect()` can immediately
re-start the service when the dongle is still ACL-connected or on any subsequent Bluetooth event; (3) the engine's
`reconnectWithBackoff()` runs `transport.connect()` (blocking) that is not interrupted by cancellation until it
returns.

## Steps
1. **A sticky "user disconnected" latch.** Add `manualDisconnect: Boolean` (or `suppressAutoConnectUntil`) to
   `AppSettings` + `SettingsRepository`. On `StatsViewModel.disconnect()`/`reset()` set it true; on
   `connect()`/`connectDemo()`/`autoConnect()` clear it.
2. **Gate auto-start on the latch.** In `BluetoothAclReceiver` (both ACL paths and `STATE_CHANGED`) and
   `autoConnect()`, do nothing when the latch is set. This stops the "keeps trying to connect" loop after a disconnect.
3. **Interrupt reconnect promptly.** In `ObdEngine`, make `disconnect()`/`stop()` set a volatile `stopRequested` flag;
   check it inside `reconnectWithBackoff()` (between attempts) and at the top of the run-loop iteration, and return
   immediately. Also wrap the blocking `transport.connect()` calls so cancellation from `job.cancel()` takes effect:
   the `BluetoothClassicTransport.connect()` already uses `withTimeout`, so ensure the cancelled `job` propagates
   (don't swallow `CancellationException`) — rethrow it.
4. **Stop signal hardening.** Make `ObdLoggingService.stop()` set `START_NOT_STICKY` semantics for the current run and
   ensure `onDestroy` cancels `engine` + releases the wake lock (already mostly there). Verify no `START_REDELIVER_
   INTENT` re-delivery re-arms logging after an explicit stop (use `stopSelf(startId)` so the specific start is
   consumed, or track a `stopped` flag and ignore further start intents until a fresh explicit connect).

## Tests
Extend `ConnectPolicy` (pure) with `shouldSuppressAutoConnect(manualDisconnect)` and unit-test the gating; test that a
cancelled reconnect loop returns early (pure helper).

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat lintDebug --console=plain
```
Manual (device): tap "התנתק" → notification gone AND no reconnect happens even if the dongle stays paired/connected.

## Done when
A manual disconnect is sticky — nothing re-arms auto-connect until the user explicitly reconnects.

Do **not** commit.