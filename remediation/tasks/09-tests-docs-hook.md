# Task 09 — Tests, fixtures, docs, pre-push hook

**REMEDIATION items:** Phase 7 (7.1–7.4) + Phase 0.4 (doc fixes).
**Depends on:** all other cards (runs last; also the only card allowed to edit `PLAN.md` / `AGENTS.md`).

## Objective
Add the fixtures and missing unit tests, convert `FakeObdTransport` to file replay, install the build gate, and bring
`PLAN.md`/`AGENTS.md` in line with the finished code.

## Steps

1. **Fixtures**:
   - Create `app/src/test/resources/fixtures/routes/` with 4 JSON files (raw `computeRoutes` responses):
     - `3-alternatives.json` (TA→JLM, 3 routes with route- and leg-level `speedReadingIntervals`),
     - `with-tolls.json` (has `tollInfo.estimatedPrice`),
     - `single-route.json`,
     - `route-level-only.json` (`speedReadingIntervals` only under `routes[0].travelAdvisory`).
   - Create `app/src/test/resources/fixtures/obd/` with at least one recorded ELM session file
     (`init + vin + samples` format: one `CMD> response` per line, e.g. `010D>41 0D 3C`, with an optional leading
     `delay=NN` line). These can be synthetic but must be realistic (include a `NO DATA`, a `SEARCHING...`, and a
     multi-frame VIN).
   - Add a test-only helper to load a resource file into a String (e.g. `testutil/Fixtures.kt`).
2. **`FakeObdTransport` → file replay**: add a constructor that takes a file path / response map + delays and replays
   sequentially. Keep `SimulatedObdTransport` for the live demo. Wire the OBD fixture into a `PidParser`/`ElmProtocol` test.
3. **New unit tests** (JUnit 4, mirror existing test style): `TripDetector`, `RouteRanker`, `CongestionModel`,
   normalization invariant (already added in card 05 — verify it's there), `RoutesCache`, `RoutesError` mapping,
   `RefuelCalibrator`, VIN parsing, `SpeedBinAggregator` (dt / bin remap), `ColdStartLearner`, `VehicleRepository`
   bootstrap (using a fake/pre-existing `VehicleDao`? mark android-only if needed, else skip), mapper round-trip on the
   4 route fixtures.
4. **Build gate**: run `.\scripts\install-pre-push-hook.ps1` (creates `.git/hooks/pre-push`). If it errors, fix and rerun.
5. **Docs**:
   - `PLAN.md`: fix section 1 table (JUnit 4, no Vico), rewrite section 6 tree to match reality, convert section 8 to a
     status checklist, add **section 10 "מגבלות המודל וקבועים"** (list `ModelConstants`), extend section 9 (keystore loss,
     REST key restriction, FGS start rules, OneDrive/ASCII path, destructive migration → migrations).
   - `AGENTS.md`: confirm path note, SDK 36, JUnit 4, `SimulatedObdTransport`, backup/export commands.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat lintDebug --console=plain
```
Run the combined gate exactly as the hook will: `.\gradlew.bat testDebugUnitTest lintDebug --console=plain`.

## Done when
- All fixtures load and at least the mapper + OBD fixture tests pass.
- Pre-push hook installed; `git push --no-verify` still works if you want to skip.
- `PLAN.md`/`AGENTS.md` reflect the final code.

Do **not** commit. Report which doc sections changed and the fixture file list.