# Task 01 — Room schema & migration (v3 → v4)

**REMEDIATION items:** 1.1 (and schema parts of 1.2/2.9/4.1/6.2/6.4).
**Depends on:** none (start together with card 05).
**Touches:** `app/src/main/java/com/fuelroute/data/db/*`, `di/DatabaseModule.kt`.

## Objective
Bump the DB from v3 to v4 with `exportSchema = true` and a real `MIGRATION_3_4`. Later cards populate the new
tables/columns, so this card only defines the schema + migration and makes the project compile.

## Current state (read these first)
- `data/db/AppDatabase.kt` — `version = 3`, `exportSchema = false`, 5 entities.
- `data/db/Entities.kt` — `ObdSampleEntity`, `SpeedBinStatsEntity`, `TripEntity`, `RefuelEntity`, `RouteSearchEntity`.
- `data/db/Daos.kt` — 5 DAOs.
- `di/DatabaseModule.kt` — `.fallbackToDestructiveMigration()`.
- `app/build.gradle.kts` already has `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`.

## Steps

1. **New entities** in `Entities.kt`:
   - `VehicleEntity(id TEXT PK, name, fuelType, ratedCombinedL100, engineDisplacementL?, tankCapacityL?,
     fuelRateCorrection, manualCurve?, vin?, grade = "95", createdAtMs)`. Table `vehicle`.
   - `LearningExtrasEntity(vehicleId TEXT PK, coldStartExtraL, coldStartCount, updatedAtMs)`. Table `learning_extras`.
2. **Extend existing entities**:
   - `TripEntity`: add `isOpen: Int = 0`, `routeSearchId: Int? = null`, `coldStartFuelL: Double = 0.0`. Keep the rest.
   - `RouteSearchEntity`: add `selectedRouteIndex: Int = 0`, `departureTimeMs: Long? = null`, `tollUnknown: Int = 0`.
   - `RefuelEntity`: add `pricePerLiter: Double = 0.0`, `grade: String = "95"`.
3. **New DAOs** in `Daos.kt`:
   - `VehicleDao`: `getAll()`, `getById(id)`, `upsert(list)`, `delete(vehicle)`, `count()`.
   - `LearningExtrasDao`: `get(vehicleId)`, `upsert(entity)`, `reset(vehicleId)`.
   - Add small query helpers to existing DAOs later cards need:
     - `TripDao`: `recentOpenTrips()`, `findActiveRouteSearch(tripStartMs, windowMs)`.
     - `RefuelDao`: `fullRefuelsSince(vehicleId, sinceMs)` (returns rows ordered ascending).
     - `SpeedBinDao`: `moveNonIdleBinsUp(vehicleId)` is NOT needed — the remap is one-time SQL in the migration only.
4. **`Migrations.kt`** (new file, `object` with `val MIGRATION_3_4 = object : Migration(3, 4) { ... }`):
   - `CREATE TABLE vehicle (...)` and `CREATE TABLE learning_extras (...)` with the same schema Room will generate
     (column order/affinity must match what Room expects; simplest is to copy the SQL Room emits after a clean build —
     see `app/schemas` after step 5's first run if you get a validation mismatch).
   - `UPDATE speed_bin_stats SET binIndex = binIndex + 1 WHERE binIndex > 0;`
   - `ALTER TABLE trip ADD COLUMN isOpen INTEGER NOT NULL DEFAULT 0;`
   - `ALTER TABLE trip ADD COLUMN routeSearchId INTEGER;`
   - `ALTER TABLE trip ADD COLUMN coldStartFuelL REAL NOT NULL DEFAULT 0;`
   - `ALTER TABLE route_search ADD COLUMN selectedRouteIndex INTEGER NOT NULL DEFAULT 0;`
   - `ALTER TABLE route_search ADD COLUMN departureTimeMs INTEGER;`
   - `ALTER TABLE route_search ADD COLUMN tollUnknown INTEGER NOT NULL DEFAULT 0;`
   - `ALTER TABLE refuel ADD COLUMN pricePerLiter REAL NOT NULL DEFAULT 0;`
   - `ALTER TABLE refuel ADD COLUMN grade TEXT NOT NULL DEFAULT '95';`
5. **`AppDatabase.kt`**: `version = 4`, `exportSchema = true`, add `VehicleEntity` + `LearningExtrasEntity` to the
   `entities` list, expose `vehicleDao()` + `learningExtrasDao()`.
6. **`DatabaseModule.kt`**: replace `.fallbackToDestructiveMigration()` with `.addMigrations(MIGRATION_3_4)`, and add
   `@Provides` for `VehicleDao` and `LearningExtrasDao`.

## Verify
```
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat testDebugUnitTest --console=plain
```
- `app/schemas/com.fuelroute.data.db.AppDatabase/4.json` exists after a build.
- No compile errors anywhere (other cards' code will fill in against these entities later, so do not add unused DAO
  methods that don't compile without their callers).

## Done when
- Build + existing unit tests green.
- `git status` shows only `data/db/*`, `di/DatabaseModule.kt`, and `app/schemas/*` changed.
- Report: the migration SQL you wrote, and confirmation the schema JSON was emitted.

Do **not** commit.