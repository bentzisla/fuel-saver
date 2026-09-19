# Task 04 — Learning domain correctness

**REMEDIATION items:** 2.2 (idle vs creeping bins), 2.9 (cold-start learner), 2.11 (RefuelCalibrator).
(2.3 hybrid / 2.4 diesel are **deferred** — leave the code paths, do not block on them.)
**Depends on:** card 02 (RefuelViewModel uses active vehicle; new `learning_extras` table from card 01).
**Touches:** `domain/learning/*`, `domain/model/SpeedBinStats.kt`, `ui/curve/*`, `ui/refuel/*`.
Do **not** touch `domain/obd/*` or `data/obd/*` (card 03 owns those).

## Objective
Fix the three things that bias the learned curve, and move refuel calibration into a tested domain object.

## Current state
- `domain/model/SpeedBinStats.kt`: `speedToBinIndex` (≤0 → 0, else `v/5`), `binIndexToSpeedKmh` (`i*5+2.5`).
- `domain/learning/SpeedBinAggregator.kt`: drops `!engineRunning`, `dt > 2`, coolant < 60, bad rate.
- `domain/learning/LearnedCurve.kt`: skips `binIndex <= 0` (so bin 0 is treated as idle-only already).
- No `ColdStartLearner`; no `RefuelCalibrator`. Calibration is inline in `ui/refuel/RefuelViewModel.kt`.

## Steps

1. **Bin remap (2.2)** in `SpeedBinStats.kt`:
   - `speedToBinIndex(v)`: `v < 1` → 0; else `(v / 5).toInt() + 1`.
   - `binIndexToSpeedKmh(i)`: `i == 0` → 0.0 (idle); else `(i - 1) * 5 + 2.5`.
   - Keep `SpeedBinStats` fields; `litersPerHour` for bin 0 already reads `seconds`.
   - Update `ui/curve/CurveScreen.kt` labels if they render bin→speed text.
2. **`SpeedBinAggregator`**: use the new `speedToBinIndex`; keep the `dt > 2 s` rejection (card 03 now sends raw dt).
   No change for gasoline engine-off samples (keep dropping them).
3. **`domain/learning/ColdStartLearner.kt`** (new, pure): given `(speed, fuelRateLph, dtSec, warmCurve)` while coolant
   < 60 °C, accumulate `fuelL - warmCurve.litersPer100Km(v) * (v*dt/3600)/100` into a per-trip-start `extraL`.
   Persist a running mean into `learning_extras` (`coldStartExtraL`, `coldStartCount`) via a small repository in
   `data/obd/LearnedCurveRepository` or a new `data/learning/` (use `LearningExtrasDao` from card 01). Default
   `COLD_START_DEFAULT_L = 0.15` until enough cold starts are seen. Keep it pure + unit-tested; the DAO side can be a
   thin wrapper.
4. **`domain/learning/RefuelCalibrator.kt`** (new, pure):
   ```
   sealed interface Calibration { data object Insufficient; data class Exact(k); data class Clamped(k); }
   fun calibrate(pumpedLitresBetweenFull, obdLitresBetweenFull): Calibration
   ```
   clamp to `[0.7, 1.4]`. Rewrite `ui/refuel/RefuelViewModel.kt` to: use **fuel logged between the two most recent full
   refuels** (not lifetime totals) — needs `RefuelDao.fullRefuelsSince` (card 01) and OBD fuel between those timestamps
   (add a `SpeedBinDao`/`TripDao` query or compute from `trip` intervals). Show a localized warning ("הכיול הגיע לקצה — בדוק את הנתונים") when `Clamped`.
5. **Tests**:
   - bin remap: 0 km/h → 0, 4 km/h → 1, 50 km/h → 11;
   - `ColdStartLearner`: synthetic cold phase → expected extra liters; warm phase → none;
   - `RefuelCalibrator`: exact, clamped low, clamped high, insufficient.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
- Tests green; app compiles.
- A 4 km/h crawl sample lands in bin 1, not the idle bin (verify with a small test or the aggregator test).

Do **not** commit.