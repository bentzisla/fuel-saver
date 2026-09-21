# Task 46 — Mark demo rides as "הדגמה" (trip.source)

**User request:** #2 — the History ("routes") page shows rides with no indication that they are fake demo drives from
the "הדגמה" feature.
**Depends on:** none (but runs after card 43 because both touch `data/obd/ObdEngine.kt`).
**Touches:** `data/db/Entities.kt`, `data/db/Migrations.kt`, `data/db/AppDatabase.kt`, `data/db/Daos.kt`,
`data/obd/ObdTransport.kt`, `data/obd/SimulatedObdTransport.kt`, `data/obd/ObdEngine.kt`, `data/obd/TripRecorder.kt`,
`data/history/DriveHistoryRepository.kt`, `ui/history/HistoryScreen.kt`, `res/values/strings_history.xml` (create).

## Objective
A demo drive (started via "הדגמה", which runs `SimulatedObdTransport` because `ObdLoggingService` is started with a
null address) is persisted with a `source = 'demo'` marker and rendered with a clear "הדגמה" badge in History, so it is
never mistaken for a real ride.

## Current state (read these)
- `ObdLoggingService.startLogging(address)`: `address == null` → `SimulatedObdTransport()` (demo). The engine then
  writes real `trip` rows via `TripRecorder` with no source field.
- `TripEntity` has no `source`. `TripRecorder.start/write` rebuild `TripEntity` on every checkpoint (so a source column
  must be carried through `TripRecorder`, not just the insert).
- `DriveHistoryRepository` builds `DriveHistoryEntry` from `trip` + `route_search`; demo trips appear as orphan trips
  (`NO_PREDICTION`) with no marker.

## Steps
1. **Schema v5 -> v6.** Add `source TEXT NOT NULL DEFAULT 'real'` to `trip`. In `AppDatabase.kt` bump `version = 6`,
   add `MIGRATION_5_6` (`ALTER TABLE trip ADD COLUMN source TEXT NOT NULL DEFAULT 'real'`) in `Migrations.kt`, register
   it in `DatabaseModule` (next to `MIGRATION_4_5`). Update `TripEntity` with `val source: String = "real"`. (The
   schema JSON under `app/schemas/` regenerates when the orchestrator builds.)
2. **Transport marker.** Add `val isSimulated: Boolean get() = false` to `ObdTransport`; override `true` in
   `SimulatedObdTransport` (and `FakeObdTransport` if it replays demo scripts — check `FakeObdTransport.kt`).
3. **Thread it through.** In `ObdEngine`, capture `val source = if (transport.isSimulated) "demo" else "real"` and pass
   it to `TripRecorder.start(vehicleId, startedAtMs, source)`. `TripRecorder` stores the source for the open trip and
   re-applies it in `write()` (so `checkpoint`/`end` keep it). Constant values: use `"real"`/`"demo"` (or a small
   `TripSource` object in `data/db`).
4. **Skip linking/accuracy for demos.** In `ObdEngine` where it calls `tripLinker.autoLink(...)`, do not auto-link a
   demo trip to a real search. In `DriveHistoryRepository`, exclude demo trips from the rolling accuracy computation.
5. **Expose + badge.** Add `isDemo: Boolean` to `DriveHistoryEntry` (from `TripEntity.source == "demo"`). In
   `HistoryScreen`, render a "הדגמה" badge (e.g. a small `Text` with error/outline colour) on any demo entry, next to the
   `RideStateLabel`. New strings → `res/values/strings_history.xml`.
6. Add/adjust a unit test if there's a pure seam (the `TripRecorder` source carry-over is a good MockK-DAO target;
   follow existing `SpeedBinStatsTest`/DAO-test style).

## Verify
```
# run by the orchestrator:
.\gradlew.bat testDebugUnitTest lintDebug --console=plain
```
Manual (device): run "הדגמה" → the resulting History row is badged "הדגמה"; a real drive is not.

## Done when
Demo rides are persisted with `source='demo'`, badged in History, and excluded from accuracy — via a non-destructive
v5->v6 migration.

Do **not** commit.
