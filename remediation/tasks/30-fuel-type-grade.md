# Task 30 — Separate fuel type from octane grade (no "diesel" octane)

**User request (round 2):** #4 — "diesel" appears both as a fuel type and as an octane (95/98/diesel).
**Depends on:** card 02/08 (vehicle fuelType + grade).
**Touches:** `data/price/FuelPrice.kt` (`FuelGrades`), `domain/model/FuelType.kt`, `ui/vehicle/VehicleScreen.kt` /
`VehicleViewModel.kt` (grade selector), `ui/settings/*` (grade display), `res/values/strings.xml`.

## Objective
Decouple **fuel type** (GASOLINE / DIESEL / HYBRID) from **octane grade** (95 / 98 — gasoline only). A diesel vehicle
must not be offered gasoline octanes, and "diesel" must not appear in the octane list.

## Current state (read these)
- `FuelGrades` = { GASOLINE_95="95", GASOLINE_98="98", DIESEL="diesel" } and `ALL = [95, 98, diesel]`.
- `FuelType` = { GASOLINE, DIESEL, HYBRID } (ELECTRIC removed in card 05). `VehicleProfile.grade` defaults to "95".
- The vehicle form (card 08) shows a grade selector and the Settings price row shows `grade`.

## Steps
1. Model grades by fuel type: gasoline/HYBRID → "95"/"98"; DIESEL → a single implicit grade (e.g. keep `"diesel"` as a
   grade value but do NOT offer it in an octane list). Provide `FuelGrades.forFuelType(fuelType)` returning the allowed
   grades, so a diesel vehicle only ever sees "דיזל" (no 95/98), and a gasoline vehicle never sees "diesel".
2. Update the vehicle form grade selector to use `forFuelType(vehicle.fuelType)` and disable/hide it when the vehicle is
   diesel (or show a fixed "דיזל" label).
3. Update the Settings price row and `FuelPriceRepository` to key prices by grade but only expose grades valid for the
   active vehicle's fuel type.
4. Keep `grade` on the stored vehicle/refuel data (schema unchanged); this is a UI/model cleanup, not a migration.

## Tests (JUnit 4, pure)
`FuelGrades.forFuelType`: GASOLINE/HYBRID → [95, 98]; DIESEL → [diesel] only.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
A diesel vehicle sees no 95/98 octane options, and gasoline vehicles see no "diesel" octane.

Do **not** commit.
