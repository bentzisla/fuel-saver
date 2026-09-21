# Task 47 — Scope History + Refuel to the active vehicle

**User request:** #5 — statistics about the car seem to be for all cars, not per vehicle.
**Depends on:** none (runs after 46 because both touch `data/db/Daos.kt` + `data/history/DriveHistoryRepository.kt`).
**Touches:** `data/db/Daos.kt`, `data/history/DriveHistoryRepository.kt`, `data/refuel/RefuelRepository.kt`,
`ui/history/HistoryViewModel.kt`, `ui/refuel/RefuelViewModel.kt`, `ui/refuel/RefuelScreen.kt`,
`ui/debug/CalibrationViewModel.kt`, `res/values/strings_history.xml`.

## Objective
Every history/refuel list the user sees is filtered to the **active vehicle**, matching the Stats trips list which is
already per-vehicle. Switching the active vehicle must switch what History and Refuel show.

## Current state (read these)
- `StatsViewModel.loadTrips()` already calls `tripRepository.recentTrips(vehicleId, 20)` → `TripDao.recentForVehicle`.
- But `DriveHistoryRepository.recent()` uses `routeSearchDao.recent(limit)` (no vehicle) and `tripDao.recentClosed(limit)`
  (no vehicle), and `linkCandidates()` scans `tripDao.recentClosed(LINKED_SCAN_LIMIT)` (no vehicle).
- `RefuelViewModel.load()` calls `refuelRepository.recent(20)` → `RefuelDao.recent(limit)` (no vehicle); `RefuelEntity`
  already has `vehicleId`.
- `CalibrationViewModel` (debug) calls `driveHistoryRepository.recent()` too.

## Steps
1. **DAOs.** Add `TripDao.recentClosedForVehicle(vehicleId, limit)` (`... WHERE isOpen = 0 AND vehicleId = :vehicleId
   ORDER BY startedAtMs DESC LIMIT :limit`) and `RefuelDao.recentForVehicle(vehicleId, limit)`. Keep the old global
   queries only where backup/export or the linker genuinely needs them.
2. **DriveHistoryRepository.** Change `recent(vehicleId, limit)` and `linkCandidates(vehicleId)` to take the active
   vehicle id; use the vehicle-scoped trip queries. Route searches remain vehicle-agnostic (a search isn't tied to a
   car until driven), so the `routeSearchDao.recent` side stays global — but the linked-set scan in `linkCandidates`
   must be scoped to `vehicleId`.
3. **HistoryViewModel.** Inject `VehicleRepository`, resolve the active vehicle id (and re-load on vehicle switch), and
   pass it to `recent(...)`/`linkCandidates(...)`.
4. **Refuel.** Change `RefuelRepository.recent(vehicleId, limit)` to use `recentForVehicle`; update `RefuelViewModel`
   to pass `vehicleRepository.active().id` (it already resolves the active vehicle for `add`, so reuse it in `load`
   and after `save`). If `RefuelScreen` shows a total, make it the active vehicle's total (`totalFullLiters(vehicleId)`
   is already per-vehicle — wire it).
5. **CalibrationViewModel.** Update its `driveHistoryRepository.recent()` call to pass the active vehicle id.
6. If History shows a "total saved" summary, ensure it sums only the active vehicle's entries (the entries are now
   already scoped). New strings (if any) → `res/values/strings_history.xml`.

## Verify
```
# run by the orchestrator:
.\gradlew.bat testDebugUnitTest lintDebug --console=plain
```
Manual (device): with two vehicles that have data, switch the active vehicle — History and Refuel lists change to that
vehicle only.

## Done when
History and Refuel are filtered by active vehicle end-to-end (DAO → repository → ViewModel), and switching vehicles
updates both screens.

Do **not** commit.
