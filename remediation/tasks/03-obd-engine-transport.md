# Task 03 — OBD protocol, engine & transport robustness (builds on 00a)

**REMEDIATION items:** 1.3 (checkpointing), 2.1 (dt), 2.5 (PID negotiation), 2.6 (ELM init — remaining bits),
2.7 (reconnect), 2.8 (ignition-off), 2.10 (VIN).
**Depends on:** card 01 (trip columns) and card 02 (`vehicle.id` source). Run **after** 02; runs in parallel with 04
(04 does not touch these files).
**Touches:** `domain/obd/*`, `data/obd/*` (NOT `service/ObdLoggingService.kt` — card 07).

## P0 (card 00a) is ALREADY DONE — do not redo it
00a landed before this card and already implemented:
- Blocking read (`stream.read()` bounded by a reflection-set socket `soTimeout`) + `ELM>>`/`ELM<<` raw logging in
  `BluetoothClassicTransport`.
- Init validation (`ATZ` must contain `ELM327`), `settleProtocol()` that waits out `SEARCHING...`, and `ATAT1`/`ATST64`
  in `ElmProtocol.initializationCommands`.
- `LiveObdState.lastRawReply` + `lastError` and consecutive-bad tracking in `ObdEngine`.
- `PidParser` reorder (valid `41 <PID>` data wins over noise) + `SEARCHING`/`ACT ALERT`/etc. in `ERROR_MARKERS`.

Build on top of those. Do NOT re-add them and do NOT revert them.

## Current state (post-00a — verify as you read)
- `data/obd/ObdTransport.kt`: `sendCommand(command): String` (keep it — do NOT change to `Result`; reconnect detection
  reuses the empty-reply + `lastError` tracking already present).
- `data/obd/ObdEngine.kt`: still polls 5 PIDs unconditionally; `dtSec` still `coerceIn(0.0, 2.0)`; bins still persisted
  only in `finally`; `tripDao.insert` duplicated in the loop + `finally`; no reconnect; no PID negotiation; no VIN.
- `domain/obd/ElmProtocol.kt`: no `ATRV`/`batteryVoltage`, no mode-09/VIN.
- `domain/obd/PidParser.kt`: no mode-09 multi-frame.

## Steps

1. **Raw dt (2.1).** In `ObdEngine.runLoop`, change
   `dtSec = lastSample?.let { ((now - it.timestampMs) / 1000.0).coerceIn(0.0, 2.0) } ?: 0.0`
   to `(now - last.timestampMs) / 1000.0` (no clamp), still `0.0` when `lastSample == null`. Card 04 enforces the >2 s
   rejection inside `SpeedBinAggregator`.
2. **PID negotiation (2.5).** After init (and after `settleProtocol`), send `0100`/`0120`/`0140`/`0160` via
   `ElmProtocol.command(ElmProtocol.PID_SUPPORTED_01_20)` etc. and build a supported set with
   `ElmProtocol.supportedPids(...)`. Expose `Set<Int>` in `LiveObdState.supportedPids`. Each cycle, poll only supported
   PIDs (speed/rpm always; MAF/fuel-rate/MAP/IAT/load/fuel-level only when supported).
3. **VIN + mode-09 (2.10).** Add `PID_VIN = 0x02` (mode `09`) and `command09(pid) = "09" + hex02`; add
   `PidParser.parseVin(raw)` (and a `mode09DataBytes(raw, pid)` helper) handling: single-line `49 02 01 ...`, legacy
   multi-line `49 02 31/32/33 ...`, and CAN ISO-TP `0: 49 02 01 ...` / `1: ...`. Read VIN once after negotiation and
   expose it in `LiveObdState.vin`.
4. **Battery voltage (2.6 remainder).** Add `ATRV` + `ElmProtocol.batteryVoltage(raw): Double?` (parse the leading float
   of e.g. `12.3V`); poll it every ~10 s in the loop and expose `LiveObdState.batteryVoltage`.
5. **Checkpointed persistence (1.3).** Persist bins every 30 s (and in `finally`) — currently only in `finally`. Track an
   open trip: on `TripDetector.TripTransition.Started` insert `TripEntity(isOpen = 1)`, update totals at each 30 s
   checkpoint, close on `Ended`. Factor the two `tripDao.insert` blocks into a small `TripRecorder` helper. On engine
   start, close any leftover `isOpen` rows (add a `TripDao` query if needed).
6. **Reconnect/backoff (2.7).** After ≥5 consecutive empty/failed replies for the speed PID → `disconnect()`, status
   `Connecting`, then retry `connect()` with backoff 2,4,8,…60 s (max 3 min) before giving up → `Error`. A recovered
   connection resumes the same trip if within the trip window (reuse `TripDetector`). Do not let a reconnect change the
   `vehicle.id` resolution (see below).
7. **Ignition-off (2.8).** If `rpm` is null / `NO DATA` for 60 s, or `batteryVoltage` < 11.5 V → force trip end,
   `disconnect()`, stop the loop (status `Disconnected`).
8. **`LiveObdState` additions:** `supportedPids: Set<Int> = emptySet()`, `sampleRateHz: Double = 0.0`,
   `batteryVoltage: Double? = null`, `vin: String? = null`. Compute `sampleRateHz` from loop iterations.

## Constraints
- Do NOT touch `service/ObdLoggingService.kt` (card 07).
- Do NOT change how `vehicleId` is derived in `start()`/`runLoop()` — card 02 already switched it to the active vehicle;
  preserve whatever is there and pass it through.
- `domain/` stays pure Kotlin (`Log` only in `data/`/`ui/`).

## Tests (`app/src/test/.../domain/obd/`, JUnit 4)
VIN single / multi-line legacy / CAN ISO-TP; `batteryVoltage`; supported-PID bitmap (a transport whose `0100` omits `5E`
→ the negotiation set excludes it); a transport that fails after N calls returns empty → reconnect path exercises
(assert the failure detection, not the socket).

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
- Unit tests green (VIN, voltage, PIDs).
- Simulated demo still streams data and now shows a Hz value.
- No `default` vehicle-id assumption reintroduced.

Do **not** commit.