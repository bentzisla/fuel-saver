# Task 03 — OBD protocol, engine & transport robustness

**REMEDIATION items:** 1.3 (checkpointing), 2.1 (dt), 2.5 (PID negotiation), 2.6 (ELM init), 2.7 (reconnect),
2.8 (ignition-off), 2.10 (VIN parsing).
**Depends on:** card 01 (trip columns) and card 02 (`vehicle.id` source). Run after 02; coordinate with 04 (04 does
*not* touch these files).
**Touches:** `domain/obd/*`, `data/obd/*` (NOT `service/ObdLoggingService.kt` — that's card 07).

## Objective
Make the OBD loop survive cheap dongles and process death, and stop polling PIDs the car doesn't have.

## Current state
- `domain/obd/ElmProtocol.kt`: `initializationCommands` = `ATZ,ATE0,ATL0,ATS0,ATH0,ATSP0`; `command(pid)`; scalar parser fns; `supportedPids`.
- `domain/obd/PidParser.kt`: `clean`, `isError`, `parseMode01Bytes`, `parseSupportedPids`. No mode-09.
- `data/obd/ObdTransport.kt`: `sendCommand(command): String` (returns `""` on failure in Bluetooth impl).
- `data/obd/BluetoothClassicTransport.kt`: single SPP socket; `sendCommand` swallows I/O errors; `connect` uses `createRfcommSocketToServiceRecord`.
- `data/obd/ObdEngine.kt`: polls 5 PIDs unconditionally; `dt` clamped `coerceIn(0,2)`; bins persisted only in `finally`; duplicated `tripDao.insert`; a single `connect()` failure → `Error` and exit; no reconnect; no negotiation.

## Steps

1. **Transport contract → `Result`.** Change `ObdTransport.sendCommand(command): Result<String>`. Update
   `FakeObdTransport`, `SimulatedObdTransport`, `BluetoothClassicTransport`. In Bluetooth, read-until-`>` returns
   `Result.failure` on timeout/IO; `sendCommand` propagates write/read failures as `Result.failure`.
2. **Bluetooth fallbacks.** `connect()`: try `createRfcommSocketToServiceRecord` → `createInsecureRfcommSocketToServiceRecord`
   → reflection `createRfcommSocket(1)`. Return `Result`.
3. **`ElmProtocol` additions:**
   - init commands += `ATAT1` (adaptive timing), `ATST32` (request timeout 32 ms), `ATDPN` (confirm protocol); `ATRV` (battery voltage) as a repeatable command.
   - `fun batteryVoltage(raw): Double?` (`41 ...`? — ATRV returns `12.3V`; parse the float).
   - `fun isValidReset(reply): Boolean` (contains `ELM327`, case-insensitive).
   - VIN: `const val PID_VIN = 0x02` in mode `09` — command `"0902"`; parse via a new `PidParser.parseVin(raw)`.
4. **`PidParser` mode-09 multi-frame `parseVin(raw`)**:
   - single line `49 02 01 ...DATA...` → strip `49 02`, then payload;
   - legacy multi-line: frames like `49 02 31 ...` / `49 02 32 ...` (3 = `0x20 + packetCount`, low nibble = frame index) — reassemble by order, drop the first frame's page/order byte;
   - CAN ISO-TP: `0: 49 02 01 00 00 00 57 ...` / `1: 58 ...` style — first byte of data is combination frame index + total, reassemble by those nibbles, strip `0x49 02`;
   - tolerate `SEARCHING...`/`NO DATA`/echo by reusing `clean`/`isError`.
   Add a small helper `fun mode09DataBytes(raw, pid): List<Int>?` analogous to `parseMode01Bytes`.
5. **`ObdEngine` rewrite of `start`/`runLoop`:**
   - Validate init (step 3). If `ATZ` invalid or any init reply is an error → `ObdStatus.Error` with a reason string, stop.
   - Negotiate supported PIDs: send `0100/0120/0140/0160`, build the supported set, expose in `LiveObdState.supportedPids`.
     Poll only supported PIDs each cycle (always speed/rpm; MAF/fuel-rate/MAP/IAT/load/fuel-level only if supported).
   - Read VIN once after negotiation; stash it for card 02's consumer (submit via a callback or just expose in `LiveObdState`).
   - **Raw dt**: compute `dtSec = (now - last.timestampMs)/1000.0` with no clamp; pass to aggregator (card 04 rejects >2 s).
   - **Checkpointed persistence**: persist bins every 30 s (and on stop) instead of only in `finally`. Track an open trip:
     insert `TripEntity(isOpen = 1)` on `TripDetector.TripTransition.Started`, update it at each checkpoint, close on
     `Ended`. Factor the duplicated insert into a small `TripRecorder` helper. On start, mark any left-over `isOpen` rows closed.
   - **Reconnect/backoff**: on ≥5 consecutive transport failures → `disconnect()`, status `Connecting`, retry with
     backoff 2,4,8,…60 s up to 3 min, then give up → `Error`. A recovered connection resumes the same trip if within
     the trip window (reuse `TripDetector`).
   - **Ignition-off**: if `rpm` is null / `NO DATA` for 60 s, or battery voltage < 11.5 V → force trip end, disconnect,
     stop loop (status `Disconnected`).
   - Add `sampleRateHz`, `batteryVoltage`, `supportedPids` to `LiveObdState`.
6. **Tests** (`app/src/test/.../domain/obd/`): VIN single/multi/CAN/legacy; `ATRV`; `isValidReset`; PID-negotiation
   (a transport whose `0100` bitmap omits `5E` → the loop never issues `015E`). Use `FakeObdTransport` seeded with
   responses; add a `transport that fails after N calls` for the reconnect test.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
- Unit tests green (VIN, negotiation, battery, reset validation).
- `adb` demo (`SimulatedObdTransport`) still streams live data and now shows a Hz value.
- No `default` vehicle-id assumption added in this card (use the `VehicleProfile` passed to `start`).

Do **not** commit.