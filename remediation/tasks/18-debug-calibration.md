# Task 18 — Debug calibration: runtime ModelConstants overrides + fit vs linked trips

**REMEDIATION item:** 6.6.
**Depends on:** card 11 (linked trips via `DriveHistoryRepository`) + card 05 (`ModelConstants`). Runs last in the
Wave 11 group (after 16/17).
**Touches:** `domain/fuel/ModelConstants.kt` + `FuelModel.kt`, `ui/debug/*` (new), `ui/settings/SettingsScreen.kt`
(nav entry only), `data/settings/SettingsRepository.kt` (overrides storage), `ui/route/RouteViewModel.kt`
(construct `FuelModel` with overrides), `res/values/strings.xml`.

## Objective
Let a power user (or the developer) tune the calibratable constants without recompiling, and get a data-driven
suggestion by fitting the model against already-logged linked drives.

## Current state (read these first)
- `domain/fuel/ModelConstants.kt` — an `object` of `const val`s; several are `TODO(calibrate)`
  (`SLOW_FACTOR 0.55`, `JAM_FACTOR 0.25`, `STOP_GO_WEIGHT 0.5`, `COLD_START_DEFAULT_L 0.15`, `IDLE_LPH_DEFAULT 0.8`,
  `LIVE_EMA_ALPHA 0.35`).
- `domain/fuel/FuelModel.kt` reads `ModelConstants.STOP_GO_WEIGHT` (constructor default) and
  `ModelConstants.JAM_FACTOR/NORMAL_FACTOR` (inside `rawSeconds`). `CongestionLevel(speedFactor)` is an enum using the
  same constants.
- `ui/route/RouteViewModel.kt` constructs `FuelModel(curve, idleLitersPerHour ?: DEFAULT_IDLE_LPH)`.
- `data/history/DriveHistoryRepository.kt` exposes `DriveHistory` with linked `predictedLiters` vs `actualLiters`
  (and `accuracyPct`), from card 11.
- `data/settings/SettingsRepository.kt` is DataStore-backed with one `saveX(...)` per key.

## Scope note (honest limits)
A full least-squares re-fit of the per-segment stop-go weight and congestion factors would need the stored per-segment
geometry of each past route, which `route_search` does **not** keep (only summary numbers). So this card ships:
1. **Runtime overrides** for the calibratable constants (the "edit ModelConstants at runtime" half), and
2. A **global fuel correction factor** fit over linked trips (closed-form least squares — a single multiplier that
   minimizes `Σ (predictedLiters·k − actualLiters)²`). The segment-level fit is documented as a future step that
   requires persisting per-route segment geometry first. Do not attempt the full segment fit.

## Steps

1. **Overridable constants.** Add a small value type, e.g. `data class FuelModelOverrides(...)` (nullable fields for
   `slowFactor`, `jamFactor`, `stopGoWeight`, `coldStartDefaultL`, `idleLphDefault`), defaulting to `ModelConstants`
   values when null. Keep `ModelConstants` as the canonical defaults (do not delete it).
2. **Thread overrides into `FuelModel`.** Give `FuelModel` constructor params (or a params object) for the override
   values, defaulting to `ModelConstants`, and use them inside `cost()`/`rawSeconds()` instead of reading the object
   directly. `RouteViewModel` builds `FuelModel` from the stored overrides. Keep the public `cost(route, price,
   coldStartLiters)` shape unchanged (cold-start is already a parameter).
3. **Overrides storage.** Add `FuelModelOverrides` persistence to `SettingsRepository` (one DataStore entry, e.g.
   a JSON string of the overrides) with `saveModelOverrides`/read. Provide a suspend `modelOverrides()` flow.
4. **Pure fitter** `domain/fuel/CalibrationFitter.kt`: `fun fitCorrection(pairs: List<Pair<Double, Double>>): Double?`
   = `Σ(p·a) / Σ(p²)` over (predictedLiters, actualLiters) pairs (null when empty/zero denominator). Also a
   `fun suggestedMape(pairs, k): Double` to report the improvement. Fully unit-tested.
5. **Debug screen** `ui/debug/CalibrationScreen.kt` + `CalibrationViewModel.kt`, reachable from Settings:
   - show current effective constants and edit each override (number fields),
   - "אפס לברירות מחדל" to clear overrides,
   - "התאם מול נסיעות" → fetch linked drives (`DriveHistoryRepository.recent()`), run `CalibrationFitter`, show the
     suggested factor + before/after MAPE, and a button to apply it as an override (persisted).
   All strings Hebrew.
6. **Apply overrides** to prediction: `RouteViewModel` uses the overrides when building `FuelModel` (and passes the
   fitted/override values so ranking uses them).

## Tests (JUnit 4)
- `CalibrationFitter.fitCorrection`: exact for synthetic data (e.g. all actual = 1.1·predicted → k=1.1), null for empty,
  zero denominator guard.
- Overrides: null fields fall back to `ModelConstants`; a set override changes `FuelModel.cost()` accordingly
  (reuse the existing `FuelModelTest` patterns).
- Overrides serialize/deserialize through `SettingsRepository` round-trip.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
- The debug screen edits + persists overrides; changing `stopGoWeight`/factors visibly changes a route's cost.
- "התאם מול נסיעות" produces a factor and shows the MAPE improvement.
- No magic-number regressions: `ModelConstants` remains the single source of defaults.

Do **not** commit.
