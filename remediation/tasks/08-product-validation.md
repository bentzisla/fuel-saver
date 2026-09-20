# Task 08 — Product & validation (price, capacity, defaults, multi-vehicle polish)

**REMEDIATION items:** Phase 6 — 6.3, 6.4, 6.5, 6.7.
**Depends on:** card 01 (trip.routeSearchId / route_search columns), card 02 (active vehicle), card 06
(`RouteSearch` field wiring + `RoutesError`), card 05 (ranking default already set).
**Touches:** `data/price/*` (new), `ui/refuel/*`, `ui/settings/*`, `ui/route/*` (result badges),
`ui/vehicle/*` / `ui/stats/*` (multi-vehicle switcher + VIN toast), `res/values/strings.xml`.

> **SCOPE NOTE (2026-09-20):** steps 6.1 (history screen) and 6.2 (predicted-vs-actual accuracy) are now owned by
> **card 11** (Phase 8), which gets the dedicated v5 schema columns and does the full predicted-vs-actual story. This
> card therefore does **only 6.3–6.7**. Do not rewrite `HistoryScreen` or trip-linking here.

## Objective
Close out the remaining product items: reflect tank capacity, make fuel price a first-class per-grade value, show the
cheapest/fastest story on results, and finish multi-vehicle UX (switcher + VIN auto-switch surfacing).

## Steps

1. **`tankCapacityL` (6.3).** When a refuel is saved, sanity-check liters against the active vehicle's
   `tankCapacityL` and, if exceeded, show a confirmation/warning ("ליטרים > נפח מיכל?"). On the live dashboard, if a
   fuel-level % reading is available from OBD, show an estimated remaining range
   (`tankCapacityL * level% / 100 * current L/100km`), clearly marked as an estimate.
2. **`data/price/FuelPriceRepository.kt` (6.4, new).** Price + `grade` (95/98/diesel) + `manuallyPinned` flag. A full
   refuel updates the price **only when not pinned**. Keep one source of truth: migrate `SettingsRepository.
   fuelPricePerLiter` into this repo (or read-through), and surface pinning in Settings. Do not add a network
   data.gov.il fetch yet — leave a `TODO` noting the monthly WorkManager fetch depends on a verified dataset.
3. **Defaults/UX (6.5).** Results always show both "הזול ביותר" and "המהיר ביותר" badges plus the delta in ₪ and
   minutes; `valuePerMinute` default 0.5 is already the ranking default from card 05 — make sure the UI reflects it.
4. **Multi-vehicle polish (6.7).** A simple vehicle switcher in the top bar (or Stats/Vehicle), per-vehicle stats
   filtering, and — since card 03 now exposes `LiveObdState.vin` — a toast "זוהה: <name>" when a VIN matches a known
   vehicle (auto-switch the active vehicle). Skip the auto-switch prompt UI; a toast + switch is enough for now.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
- Refuel over tank capacity warns; fuel-level → remaining range shows on the dashboard.
- Price persists per grade and a pinned price is not clobbered by a refuel.
- Results show cheapest/fastest badges + deltas; vehicle switcher + VIN toast work with two vehicles.

Do **not** commit.