# Task 10 — Room schema & migration (v4 → v5) for the product additions

**REMEDIATION items:** 8.0 (schema for user requests 1, 3 and the Android Auto/auto-logging cards).
**Depends on:** card 01 (v4 + `Migrations.kt` + `exportSchema` must already exist). **Nothing else may edit
`Migrations.kt` while this card is open.**
**Touches:** `data/db/Entities.kt`, `data/db/Daos.kt`, `data/db/Migrations.kt`, `data/db/AppDatabase.kt`.

## Objective
Exactly like card 01 did for v4: define **one** migration (v4 → v5) that adds every column/table the new product
cards (11, 13, 14, 15) will populate later. This card only defines schema + migration + DAOs and keeps the project
compiling — it wires up no features.

## Current state (read these first)
- `data/db/AppDatabase.kt` — `version = 4`, `exportSchema = true`, 7 entities, `app/schemas/...AppDatabase/4.json` exists.
- `data/db/Migrations.kt` — `MIGRATION_3_4` (pattern to copy).
- `data/db/Entities.kt` — `TripEntity` already has `isOpen`, `routeSearchId`, `coldStartFuelL`;
  `RouteSearchEntity` already has `selectedRouteIndex`, `departureTimeMs`, `tollUnknown`; `RefuelEntity` has
  `pricePerLiter`, `grade`.
- `data/routes/RouteSearchRepository.kt` — `RouteSearch` currently stores only the **cheapest** route's
  `cheapestCost`/`predictedLiters`; there is no record of what the user actually *chose*, which card 11 needs.

## Steps

1. **`RouteSearchEntity` — remember the chosen route (card 11 needs this):**
   - `selectedPredictedCost: Double = 0.0` — total ₪ predicted for the route the user actually picked.
   - `selectedPredictedLiters: Double = 0.0`
   - `selectedPredictedMinutes: Double = 0.0`
   - `pricePerLiterAtSearch: Double = 0.0` — the ₪/L used at prediction time (so a later price change doesn't
     retroactively distort the comparison).
   - `destinationPlaceId: String? = null`, `destinationLat: Double? = null`, `destinationLng: Double? = null`
     (card 12 carries these through to the nav hand-off; storing them makes history re-navigable).
2. **`TripEntity` — record the actual outcome (card 11 needs this):**
   - `actualCost: Double = 0.0` — `fuelL * pricePerLiter` at trip-close time.
   - `pricePerLiterAtTrip: Double = 0.0`
   - `linkedAtMs: Long? = null` — when the trip was linked to a `route_search` (auto or manual).
3. **New table `favorite_destination`** (card 13):
   `FavoriteDestinationEntity(id INTEGER PK autoGenerate, label TEXT, placeId TEXT NULL, latitude REAL NULL,
   longitude REAL NULL, sortOrder INTEGER NOT NULL DEFAULT 0, createdAtMs INTEGER)`. Table name `favorite_destination`.
4. **New DAOs** in `Daos.kt`:
   - `FavoriteDestinationDao`: `observeAll(): Flow<List<..>>` (ordered by `sortOrder`, then `createdAtMs`),
     `upsert(entity)`, `delete(entity)`, `count()`, `updateSortOrder(id, sortOrder)`.
   - `TripDao` additions: `findUnlinkedSince(sinceMs): List<TripEntity>`,
     `linkToRouteSearch(tripId, routeSearchId, linkedAtMs)`, `recentClosed(limit)`.
   - `RouteSearchDao` additions: `findById(id)`, `recentWithinWindow(sinceMs)`.
   Do **not** add DAO methods whose callers don't exist yet if they won't compile — plain `@Query` methods are fine.
5. **`Migrations.kt`** — add `val MIGRATION_4_5 = object : Migration(4, 5) { ... }`:
   - `ALTER TABLE route_search ADD COLUMN selectedPredictedCost REAL NOT NULL DEFAULT 0;` (same for
     `selectedPredictedLiters`, `selectedPredictedMinutes`, `pricePerLiterAtSearch`)
   - `ALTER TABLE route_search ADD COLUMN destinationPlaceId TEXT;`,
     `... destinationLat REAL;`, `... destinationLng REAL;`
   - `ALTER TABLE trip ADD COLUMN actualCost REAL NOT NULL DEFAULT 0;`,
     `... pricePerLiterAtTrip REAL NOT NULL DEFAULT 0;`, `... linkedAtMs INTEGER;`
   - `CREATE TABLE IF NOT EXISTS favorite_destination (...)` matching exactly what Room generates (copy from
     `app/schemas/.../5.json` after the first build if validation complains).
6. **`AppDatabase.kt`**: `version = 5`, add `FavoriteDestinationEntity` to `entities`, expose `favoriteDestinationDao()`.
7. **`DatabaseModule.kt`**: `.addMigrations(MIGRATION_3_4, MIGRATION_4_5)` and `@Provides` for `FavoriteDestinationDao`.

## Verify
```
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat testDebugUnitTest --console=plain
```
- `app/schemas/com.fuelroute.data.db.AppDatabase/5.json` exists after the build.
- **Migration is non-destructive:** the app is already installed on the user's device carrying real v4 data
  (learned bins + trips). `fallbackToDestructiveMigration()` must NOT be reintroduced.

## Done when
- Build + existing unit tests green; `5.json` emitted and committed.
- Report the exact migration SQL and the new DAO signatures so cards 11/13 can wire to them.

Do **not** commit.
