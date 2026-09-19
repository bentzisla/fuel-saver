# Task 08 — Product & validation (history, predicted-vs-actual, price, defaults)

**REMEDIATION items:** Phase 6 (6.1–6.7).
**Depends on:** card 01 (trip.routeSearchId / route_search columns), card 02 (active vehicle), card 06
(`RouteSearch` field wiring + `RoutesError`), card 05 (ranking default already set).
**Touches:** new `ui/history/*`, `ui/stats/*`, `ui/route/*` (result "יצאתי במסלול הזה" button), `data/routes/
RouteSearchRepository.kt`, new `data/price/*`, `ui/refuel/*`, `ui/settings/*`.

## Objective
Make the already-collected `route_search`/`trip` data actually visible and useful, and close the loop by measuring
prediction accuracy.

## Steps

1. **History screen** (`ui/history/HistoryScreen.kt` + `HistoryViewModel.kt`): read `RouteSearchRepository.recent`,
   render list (origin → destination, date, cheapest cost, saved vs fastest, distance, duration, predicted liters),
   plus a "חסכת X ₪ בסה\"כ" summary. Add a nav entry (from the Route tab action bar or a results-screen link).
2. **Predicted vs actual (the accuracy metric)**:
   - On trip start (OBD), auto-link to a `route_search` whose `departureTimeMs` is within the last 30 min (best match
     by destination/geometry — start simple: most recent); set `trip.routeSearchId`.
   - Add a manual "יצאתי במסלול הזה" button in the results screen that arms the same link for the next trip.
   - On Stats, show per-linked-trip error `(predictedLiters - actualFuelL) / actualFuelL` and a rolling MAPE over the
     last N linked trips, e.g. "דיוק חיזוי: ±N%".
3. **`tankCapacityL`**: refuel sanity check ("ליטרים > נפח מיכל?") and a remaining-range estimate from fuel level (%)
   where available (surfaced on the dashboard).
4. **`data/price/FuelPriceRepository.kt`** (new): price + `grade` + `manuallyPinned` flag. A full refuel updates the
   price **only when not pinned**. Wire into `RefuelViewModel` + Settings. Keep `SettingsRepository.fuelPricePerLiter`
   as the manual override store or migrate to the new repo; keep one source of truth.
5. **Defaults/UX**: `valuePerMinute` default 0.5 (card 05 already changed ranking default); results always show both
   "הזול ביותר" and "המהיר ביותר" badges + the delta in ₪ and minutes.
6. **Multi-vehicle polish**: simple vehicle switcher reachable from Stats/Vehicle; per-vehicle stats filtering; VIN
   auto-switch toast ("זוהה: <name>") once card 03 exposes VIN.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
- History screen reachable and populated after a search; linking one drive shows "דיוק חיזוי: ±N%".
- Price persists per grade and isn't clobbered by a refuel when pinned.

Do **not** commit.