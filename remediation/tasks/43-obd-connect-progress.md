# Task 43 — OBD connection: show progress + connect faster

**User request:** #1 — OBD has connection problems and takes a very long time to connect, while the user sees only
"מתחבר" and is in the dark.
**Depends on:** none (builds on `ObdEngine`/`LiveObdState` from cards 00a/03/20/21/34/35).
**Touches:** `data/obd/ObdEngine.kt`, `domain/obd/ObdConnectionPolicy.kt` (constants only), `ui/stats/StatsScreen.kt`,
`res/values/strings_obd.xml` (create this file; do NOT touch `res/values/strings.xml`).

## Objective
The user should never stare at a bare "מתחבר". Show exactly which connect step is running, how long it has taken, and a
working cancel — and make a dead/slow dongle fail (or succeed) noticeably faster.

## Current state (read these)
- `ObdEngine.start()` runs this silent pipeline: `transport.connect()` (retry chain, up to 3 attempts ×
  `CONNECT_TIMEOUT_MS = 15s`) → `initializeAdapter()` (sends `ElmProtocol.initializationCommands` one at a time; each
  `sendCommand` is a blocking read with `SO_TIMEOUT_MS = 1500ms` per byte-read in `BluetoothClassicTransport`) →
  `settleProtocol()` (up to `PROTOCOL_LOCK_TIMEOUT_MS = 3000ms`) → `negotiatePids()` (4 commands) → `readVin()` (1).
- `LiveObdState` has only `status` (`Disconnected/Connecting/Connected/Error`) and `lastError`. The `Connecting` UI in
  `StatsScreen` is a spinner + `stats_connecting_to` text. There is no stage, no elapsed time, no progress.

## Steps
1. Add a `connectionStage` to `LiveObdState` (default `null`): a small enum, e.g.
   `ObdConnectStage { ConnectingSocket, InitializingElm, SettlingProtocol, NegotiatingPids, ReadingVin }`, plus a
   `connectingSinceMs: Long?` (wall-clock start of the current attempt). Reset both to `null` on `Disconnected`,
   `Connected` and `Error` (clear the stage in the same `update`s that flip `status`).
2. Emit the stage through `ObdEngine.start()`: set `ConnectingSocket` before `transport.connect()`; `InitializingElm`
   at the top of `initializeAdapter()`; `SettlingProtocol` at the top of `settleProtocol()`; `NegotiatingPids` at the
   top of `negotiatePids()`; `ReadingVin` before `readVin()`. Set `connectingSinceMs` once in `start()`.
3. Surface it in `StatsScreen` under `ObdStatus.Connecting`: replace the bare spinner text with the stage label
   (`stats_connect_stage_socket`, `_init`, `_settle`, `_pids`, `_vin`) and an elapsed-seconds counter that ticks from
   `connectingSinceMs` (a small `LaunchedEffect`/`delay` loop updating every ~1 s). Keep the existing "נתק" cancel
   button visible and add a line: "החיבור עשוי לקחת עד ~30 שניות; אם הדונגל כבוי הוא ייכשל מהר".
4. Faster failure for a dead dongle: in `ObdConnectionPolicy` add `INIT_READ_TIMEOUT_MS = 600` and thread it so
   `initializeAdapter` reads fail fast (simplest: pass a shorter timeout to the transport for init commands, or reduce
   `SO_TIMEOUT_MS` to 800ms and add a separate shorter timeout used only during init). Do **not** change the run-loop
   read behaviour. Goal: a powered-off dongle surfaces `CONNECT TIMEOUT` / `bad ATZ` in well under ~10s instead of
   dragging through every init command at 1.5s each.
5. Add a `stats_connect_elapsed` string ("שניות: %1$d") and the stage strings. Put all new strings in
   `res/values/strings_obd.xml` (create it; standard `<resources>` root).

## Verify
```
# run by the orchestrator (do NOT run gradle yourself):
.\gradlew.bat testDebugUnitTest lintDebug --console=plain
```
Manual (device): start a connect with the dongle off — you must see the stage progress (socket → init → …), the elapsed
counter, and a fast, specific failure instead of a long silent hang.

## Done when
Every connect stage is visible with elapsed time and a cancel button, and a dead dongle fails in < ~10s with a clear
reason.

Do **not** commit.
