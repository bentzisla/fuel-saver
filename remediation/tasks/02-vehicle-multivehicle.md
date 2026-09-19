# Task 02 — Vehicle source of truth + multi-vehicle

**REMEDIATION items:** 1.2 (+ 6.7 groundwork, VIN storage for 2.10).
**Depends on:** card 01 (VehicleDao + `vehicle` table must exist).
**Touches:** `data/vehicle/*`, `ui/vehicle/*`, `di/AppModule.kt`, `domain/model/VehicleProfile.kt`, plus removing
`"default"` constants in `data/obd/ObdEngine.kt`, `ui/route/RouteViewModel.kt`, `ui/refuel/RefuelViewModel.kt`,
`ui/stats/StatsViewModel.kt`.

## Objective
Make a Room `vehicle` table the source of truth (multi-vehicle), with `activeVehicleId` in DataStore. Stop treating a
blank vehicle id as `"default"`.

## Current state
- `data/vehicle/VehicleRepository.kt` is DataStore-backed (single profile). `VehicleProfile` has no `vin`/`grade`.
- `ui/vehicle/VehicleScreen.kt` + `VehicleViewModel.kt` = single form (no list).
- Hardcoded `DEFAULT_VEHICLE_ID = "default"` / `"default"` literals in `ObdEngine.kt`, `RouteViewModel.kt`,
  `RefuelViewModel.kt`, `StatsViewModel.kt`.

## Steps

1. `domain/model/VehicleProfile.kt`: add `val vin: String? = null`, `val grade: String = "95"`.
2. Rewrite `data/vehicle/VehicleRepository.kt` to wrap `VehicleDao` (from card 01):
   - `fun vehicles(): Flow<List<VehicleProfile>>`
   - `suspend fun active(): VehicleProfile` (or `activeId(): Flow<String>` + `active(): Flow<VehicleProfile>`)
   - `suspend fun setActive(id: String)`, `suspend fun upsert(profile: VehicleProfile)`,
     `suspend fun delete(id: String)` (disallow deleting the last one).
   - Keep DataStore (the existing `@Named("vehicle")` store or a new one) **only** for `active_vehicle_id`.
   - **Bootstrap + migration of `"default"` rows**: on first access, if `vehicleDao.count() == 0`: build a profile from
     the legacy DataStore vehicle keys (mint a UUID if id blank), insert it, set it active, then repoint orphaned data:
     `SpeedBinDao.repoint(oldVehicleId, newVehicleId)` + same for `trip` and `refuel` (add three `UPDATE ... SET
     vehicleId = :new WHERE vehicleId = :old` queries). Make these DAO queries idempotent.
3. Rewrite `ui/vehicle/VehicleScreen.kt` + `VehicleViewModel.kt`:
   - List of vehicles (name, fuel type, combined L/100), select-active, "+" add (same edit form as today),
     edit, delete (confirm dialog; disabled when only one).
4. Remove the `"default"` constants and replace with `vehicle.id` from the active vehicle at each call site
   (`ObdEngine.start` receives `vehicle: VehicleProfile` unchanged; `service/ObdLoggingService` already reads
   `vehicleRepository.profile.first()` — update that to the new `active()` API; `RouteViewModel.compute`,
   `RefuelViewModel` similarly).
5. Update `di/AppModule.kt` (or a small new module) to provide `VehicleDao` and the new repository; remove the now-unused
   `@Named("vehicle")` DataStore if nothing else needs it.

## Verify
```
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat testDebugUnitTest --console=plain
```

## Done when
- Compiles; existing tests green.
- Fresh install → demo drive → save profile → the demo drive's bins/trips are still shown on the curve/stats screen
  (no data orphaned to `"default"`).
- Two vehicles can be created, and selecting each shows separate curves.
- No `"default"` string literal remains in `main` source.

Do **not** commit.