# Task 11 — Route history: predicted price vs. actual OBD-measured price

**User request:** #1 — "keep history of routes taken, the initial price suggested by the app, and the final price
actually recorded by the OBD."
**REMEDIATION items:** 8.1 (supersedes/completes 6.1 + 6.2).
**Depends on:** card 10 (new `route_search`/`trip` columns), card 02 (active vehicle), card 03 (checkpointed trip
recording + real trip close). Card 08 must not also rewrite `HistoryScreen` — this card owns it.
**Touches:** `data/routes/RouteSearchRepository.kt`, `data/history/*` (new), `ui/history/*`,
`ui/route/RouteViewModel.kt` (store the *selected* route), `domain/history/*` (new, pure), `res/values/strings.xml`.

## Objective
Close the loop that makes this app trustworthy: for every drive, show **what we predicted** (₪ at search time) next to
**what it actually cost** (measured OBD liters × ₪/L), the delta, and a rolling accuracy figure.

## Current state (read these first)
- `ui/history/HistoryScreen.kt` + `HistoryViewModel.kt` already exist and list `route_search` rows
  (`repository.recent(50)`) — so the *list* is done; the predicted-vs-actual half is missing.
- `data/routes/RouteSearchRepository.kt` — `RouteSearch` holds `cheapestCost`, `savedAmount`, `predictedLiters`
  for the **cheapest** route only. `RouteViewModel.compute()` writes it after ranking.
- `RouteViewModel` has `selectedIndex` (which card the user opened/navigated with) but never persists it.
- `TripEntity` (card 01) has `routeSearchId`; card 10 adds `actualCost`, `pricePerLiterAtTrip`, `linkedAtMs`, plus
  `selectedPredicted*` + `pricePerLiterAtSearch` on `route_search`.
- `ObdEngine` (card 03) closes trips with real `fuelL` / `distanceKm`.

## Steps

1. **Persist what the user actually chose.** In `RouteViewModel`, when a route is selected/navigated
   (`selectResult` + the navigate action), update the stored `RouteSearch` with `selectedRouteIndex`,
   `selectedPredictedCost`, `selectedPredictedLiters`, `selectedPredictedMinutes`, `pricePerLiterAtSearch`, and the
   destination `placeId`/lat/lng. Keep writing the cheapest/fastest values as today (the "saved ₪" badge uses them).
2. **Link a trip to a search.** New `data/history/TripLinker.kt`:
   - **Auto:** when `ObdEngine`/`TripRecorder` closes a trip, look for an unlinked `route_search` whose
     `timestampMs` is within `LINK_WINDOW_MS` (default 30 min) *before* the trip start; link the nearest one.
   - **Manual:** expose `linkTrip(tripId, routeSearchId)` for a "יצאתי במסלול הזה" action.
   - Set `trip.routeSearchId` + `linkedAtMs`; never link one trip to two searches or re-link an already-linked trip.
   Keep the matching rule in a **pure** function in `domain/history/TripMatcher.kt` so it is unit-testable.
3. **Record the actual cost at trip close.** In the trip-close path, set `actualCost = fuelL * pricePerLiter` and
   `pricePerLiterAtTrip` from current settings. Do the multiplication once, at close, so history is stable.
4. **`domain/history/PredictionAccuracy.kt`** (new, pure):
   - `data class DriveOutcome(predictedCost, actualCost, predictedLiters, actualLiters, predictedMinutes, actualMinutes)`
   - `fun errorPct(predicted, actual): Double?` (null when predicted <= 0)
   - `fun mape(outcomes: List<DriveOutcome>): Double?` — mean absolute percentage error, ignoring unusable rows.
5. **Repository + UI.** New `data/history/DriveHistoryRepository` returning a joined view
   (`route_search` LEFT JOIN `trip` ON `trip.routeSearchId`), newest first. Rewrite `HistoryScreen` rows to show:
   - origin → destination, date;
   - **"מחיר שהוצע"** (predicted ₪) and **"מחיר בפועל"** (actual ₪), plus the signed delta in ₪ and %;
   - liters predicted vs. actual, and km;
   - "ממתין לנתוני OBD" when the row has no linked trip yet;
   - a header with rolling accuracy: **"דיוק חיזוי: ±N%"** (from `mape`, last 20 linked drives).
   Add all new strings to `res/values/strings.xml` (Hebrew, RTL-safe).

## Tests (JUnit 4)
- `TripMatcher`: picks the nearest search inside the window; returns none outside it; ignores already-linked searches.
- `PredictionAccuracy.errorPct` / `mape`: exact values, zero-predicted guard, empty list → null.
- `actualCost` is computed once from the trip's own `pricePerLiterAtTrip` (a later price change must not move history).

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
- A search followed by a logged drive shows both prices and the delta on the History screen.
- A drive with no matching search still appears as a trip, and a search with no drive shows "ממתין לנתוני OBD".
- Stats/History shows "דיוק חיזוי: ±N%" after at least one linked drive.

Do **not** commit.
