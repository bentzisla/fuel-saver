# Task 35 — OBD connection reliability (repeated connect error/timeout)

**User request (round 3):** #1b — connection fails repeatedly due to "connect error" and "connection timeout" (and
maybe more).
**Depends on:** card 20 (connect timeout) + 34 (sticky disconnect). Run AFTER 34 (both touch ObdEngine/transport).
**Touches:** `data/obd/BluetoothClassicTransport.kt`, `data/obd/ObdEngine.kt`, `domain/obd/ObdConnectionPolicy.kt`.

## Root cause (diagnosed — verify as you go)
1. **One-shot `connect()` has no retry/backoff.** `ObdEngine.start()` calls `transport.connect()` once; the first
   failure is terminal (`Error`). Bluetooth SPP to a cheap dongle often fails the first attempt (socket busy / not
   yet ready) and succeeds on the 2nd/3rd.
2. **No pre-connect workaround chain.** Only `createRfcommSocketToServiceRecord` is used. Real dongles frequently need
   `createInsecureRfcommSocketToServiceRecord`, or reflection `createRfcommSocket(1)` (channel 1).
3. **Reconnect loop still spams** against an absent dongle even after card 20/34 (short backoff, no cap on noise).
4. `ATZ`/`ATSP0` init failures surface as "bad ATZ"/"SEARCHING" but the user only sees a generic failure.

## Steps
1. **Connect retry + workaround chain.** In `BluetoothClassicTransport.connect()`, try in order, with a short
   per-attempt timeout: `createRfcommSocketToServiceRecord` → `createInsecureRfcommSocketToServiceRecord` →
   reflection `createRfcommSocket(1)`. Retry the whole chain up to `ConnectPolicy.CONNECT_ATTEMPTS` (3) with a small
   backoff. Return the first success; on total failure return `failure` with the last error.
2. **Surface the real reason.** Distinguish `lastError` values: `"CONNECT TIMEOUT"`, `"SOCKET CLOSED"`, `"SECURITY"`
   (permission), `"BAD ATZ"`, `"SEARCHING"`. Map them to clear Hebrew in the stats status card.
3. **Quieter reconnect.** In `ObdConnectionPolicy`, cap reconnect attempts (e.g. 3) before giving up → `Error`, and
   only retry while the device is still ACL-connected (pass-through a boolean). Don't hammer a missing dongle.
4. **Re-init after reconnect** already happens; verify `initializeAdapter` + `negotiatePids` run on each successful
   reconnect and that a `SEARCHING` that never settles does not loop forever (it already falls through — confirm).

## Tests
Extend `ConnectPolicy` / add pure helpers: `connectAttempts()`, `shouldRetryReconnect(attempt, deviceConnected)`,
backoff cap. Unit-test the decision logic and (if separable) the workaround-order selection.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat lintDebug --console=plain
```
Manual (car/device): first connect succeeds within a few attempts or fails fast with a specific, user-readable reason.

## Done when
Connect attempts the workaround chain with retries, and failures are specific rather than a generic timeout loop.

Do **not** commit.