# Task 00a — Fix real OBD dongle: connects but reads no data

**REMEDIATION item:** P0-A (urgent, before Wave 2).
**Depends on:** none. File-disjoint from 00b.
**Touches:** `data/obd/BluetoothClassicTransport.kt`, `data/obd/ObdEngine.kt`, `domain/obd/PidParser.kt`,
`domain/obd/ElmProtocol.kt`, `data/obd/LiveObdState` (inside `ObdEngine.kt`), `ui/stats/StatsScreen.kt` (show the new
diagnostic fields).

## Objective
The dongle connects (Bluetooth + SPP socket up, status = Connected) but the dashboard never shows speed/fuel, even
though the sampling loop runs. Make the app actually READ and PARSE real ELM data, and give us raw visibility so this
is diagnosed on the car, not in the dark.

## Current state (read these first)
- `data/obd/BluetoothClassicTransport.kt` — `sendCommand` writes `command + '\r'`, then `readUntilPrompt`, which loops
  on **`stream.available() > 0`** for a 2 s deadline and `Thread.sleep(5)`. On a real Bluetooth socket `available()`
  is unreliable and often returns 0 with data in-flight, so this returns `""` → every parsed value is null. This is the
  #1 suspect.
- `data/obd/ObdEngine.kt` — `start()` ignores all init replies and sets `Connected` even if `ATZ`/`ATE0` failed. The
  loop parses 5 PIDs every 250 ms and never reports *why* values are null.
- `domain/obd/PidParser.kt` — `ERROR_MARKERS` lacks `SEARCHING`, `ACT ALERT`, `LVP RESET`, `RTR TIMEOUT`, so a dongle
  stuck auto-detecting looks like "not an error" and just yields null silently.
- `domain/obd/ElmProtocol.kt` — init = `ATZ,ATE0,ATL0,ATS0,ATH0,ATSP0`; no validation, no adaptive timing, no protocol lock.

## Steps

1. **Raw-traffic logging + diagnostics (do this FIRST).**
   - In `BluetoothClassicTransport.sendCommand`, `Log.d("FuelRoute", "ELM>> " + command)` and
     `Log.d("FuelRoute", "ELM<< " + reply)` (truncate long replies). Also log a per-reply latency ms.
   - Add to `LiveObdState`: `lastRawReply: String?`, `lastError: String?` (e.g. TIMEOUT / NO DATA / SEARCHING /
     PARSE), and set them every cycle in `ObdEngine`.
   - Show `lastError` + a short "raw reply" line on `StatsScreen` (debug section) so it is visible without logcat.
2. **Blocking read (the real fix).**
   - Replace `readUntilPrompt` with a blocking reader: set `socket.setSoTimeout(1500)` once after connect, then
     `input.read()` in a loop into a `ByteArray`/`StringBuilder` until `'>'` or SocketTimeout. Keep the whole
     `sendCommand` in `withContext(Dispatchers.IO)`. Flush the input stream (drain `available()` bytes) before each
     command so a slow previous reply can't pollute the next parse. On timeout, return what was buffered (don't return "").
3. **Init validation + protocol lock.**
   - `ObdEngine.start`: capture each init reply; require `ATZ` contains `ELM327` (case-insensitive), else `Error("bad
     ATZ: $reply")`. After `ATSP0`, allow up to ~3 s for the first `0100`/`010D` to stop `SEARCHING...`; if it does not,
     log and retry. Add `ATAT1` (adaptive timing) after `ATZ`. Append `ATST64` (timeout 64 ms) for clones.
   - If a data PID returns `SEARCHING...`/`NO DATA` for > 10 consecutive cycles, set `lastError` and keep going (do not
     crash); surface it on the dashboard.
4. **Parser gaps.**
   - Add `SEARCHING`, `ACT ALERT`, `LVP RESET`, `RTR TIMEOUT` to `PidParser.ERROR_MARKERS` (and note that `SEARCHING` is
     a *not-yet-ready* state, not a hard error).
   - Verify echo tolerance already works (it does — `parseMode01Bytes` scans for the `41 <PID>` pair), but add a unit
     test proving a response `"010D\r41 0D 3C\r"` still yields 60 km/h.
5. **Tests** (`app/src/test/.../domain/obd/`): parser returns null on `SEARCHING...`; `41 0D 3C` → 60; echo-tolerance;
   `NO DATA` → null. (The blocking-read change is device-level; cover it with a comment + the raw-log output as the gate.)

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when (car acceptance — the important part)
- With the dongle connected to the car: `adb logcat -s FuelRoute:*` shows `ELM<< 41 0D XX` and the dashboard shows a
  live speed; `lastError` is empty (or, if the car is stubborn, it shows a specific reason instead of blank).
- If you cannot reach the car, report that the raw-log code path is in place and what the unit tests cover.

Do **not** commit.