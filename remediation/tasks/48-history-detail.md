# Task 48 — History: tap a ride to see details

**User request:** #3 — clicking a historic route does nothing and doesn't show more interesting information.
**Depends on:** none (runs after 46/47 because it touches `ui/history/*` + `DriveHistoryRepository`).
**Touches:** `ui/history/HistoryScreen.kt`, `ui/history/HistoryViewModel.kt`, `data/history/DriveHistoryRepository.kt`
(read-only for detail), `res/values/strings_history.xml`.

## Objective
Tapping a History card opens a detail dialog with everything worth knowing about that ride: predicted vs actual ₪/L/min,
the delta, distance, date/time, price-per-litre used, ride state, and the demo badge when applicable.

## Current state (read these)
- `HistoryRideCard` is a plain `Card(modifier = Modifier.fillMaxWidth())` with no `onClick`. The only interaction is the
  "קשר נסיעה" button on unlinked drives.
- `DriveHistoryEntry` already carries all needed fields (`predictedCost/Liters/Minutes`, `actualCost/Liters/Minutes`,
  `distanceKm`, `pricePerLiterAtSearch/Trip`, `savedAmount`, `timestampMs`, `isDemo` once card 46 lands, `rideState`).

## Steps
1. Add an `onClick` lambda to `HistoryRideCard` and make the `Card` clickable (use `Modifier.clickable`). Wire it in
   `HistoryScreen` to open the detail dialog for that entry. Keep the existing "קשר נסיעה" button tappable without
   triggering the card click.
2. Add a `selectedEntry: DriveHistoryEntry?` to `HistoryUiState` + `openDetail(entry)` / `dismissDetail()` in
   `HistoryViewModel`.
3. Add a `RideDetailDialog(entry)` composable (an `AlertDialog`) showing, in a scrollable column:
   - route title (origin → destination, or "נסיעה לא מקושרת") + date/time,
   - ride state label + demo badge if `isDemo`,
   - predicted line (₪ / L / min) and actual line (₪ / L / min), or the "no actual yet" hint,
   - delta ₪ and % error when both exist (reuse `PredictionAccuracy.errorPct`),
   - distance, price-per-litre at search/trip when available.
   No delete here (card 49 owns delete).
4. Add new Hebrew strings to `res/values/strings_history.xml` (e.g. `history_detail_title`,
   `history_detail_price_search`, `history_detail_price_trip`, `history_detail_predicted`, `history_detail_actual`).
   Reuse existing `history_*` strings where possible.

## Verify
```
# run by the orchestrator:
.\gradlew.bat testDebugUnitTest lintDebug --console=plain
```
Manual (device): tapping a history card opens the detail dialog with the full predicted-vs-actual breakdown.

## Done when
Every history card is tappable and opens a rich detail dialog; no card is inert.

Do **not** commit.
