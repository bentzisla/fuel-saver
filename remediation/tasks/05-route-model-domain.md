# Task 05 — Route model (pure domain) correctness

**REMEDIATION items:** Phase 3 — 3.1, 3.2, 3.4, 3.6, 3.7, 3.9 (and ranking default 6.5).
**Depends on:** none (pure Kotlin, no Android). Start in parallel with card 01.
**Touches:** `domain/fuel/*`, `domain/ranking/*`, `domain/model/Route.kt`, `domain/model/RouteCost.kt`,
`domain/model/FuelType.kt`. Do **not** touch `data/` or `ui/` (card 06 rewires those).

## Objective
Fix the fuel model so route ranking reflects real fuel cost: length-weighted congestion, time normalization (no double
count), cold-start term, and named constants. This card changes signatures that card 06 must adopt.

## Current state
- `domain/model/Route.kt`: `CongestionLevel(speedFactor)`, `RouteSegment(distanceMeters, staticDurationSeconds,
  trafficDurationSeconds?, congestion)`, `Route(..., tollCost: Double?, encodedPolyline)`.
- `domain/fuel/FuelModel.kt`: `effectiveSpeed = d/vfree * congestion.speedFactor`, `stopGo = idle * (traffic - free) * 0.5`;
  relies on the mapper's `trafficDurationSeconds`.
- `domain/model/FuelType.kt`: `GASOLINE, DIESEL, HYBRID, ELECTRIC`.

## Steps

1. **`domain/fuel/CongestionModel.kt`** (new, pure):
   - `data class CongestionInterval(val startM: Double, val endM: Double, val level: CongestionLevel)`.
   - `fun weightedSpeedFactor(intervals: List<CongestionInterval>, startM: Double, endM: Double): Double` =
     mean of `level.speedFactor` weighted by overlap length; 1.0 when no overlap/NORMAL-only.
   - `fun dominantLevel(...): CongestionLevel` (for display only).
2. **`domain/model/Route.kt`**:
   - Add to `RouteSegment` a per-step congestion factor: rename/replace `trafficDurationSeconds` with
     `val congestionFactor: Double = 1.0` (keep `congestion: CongestionLevel` for display).
   - Add `enum class TrafficResolution { PER_SEGMENT, ROUTE_AVERAGE, NONE }` and `Route.trafficResolution`.
   - Change `Route.tollCost` to hold a `TollCost(amount: Double, currency: String)`? Simpler: add
     `val tollUnknown: Boolean = false` alongside `tollCost: Double?` (card 06 sets it when the API omitted price).
3. **`domain/fuel/ModelConstants.kt`** (new): move every magic number here with provenance comments + `TODO(calibrate)`:
   `SLOW_FACTOR 0.55`, `JAM_FACTOR 0.25`, `STOP_GO_WEIGHT 0.5`, `CONFIDENCE_K_KM 20` (already in CurveBlender — reference),
   `MAX_EXTRAPOLATION_KMH 12.5`, `COLD_START_DEFAULT_L 0.15`, `IDLE_LPH_DEFAULT 0.8`, `DEFAULT_FUEL_PRICE 7.0`,
   `DEFAULT_VALUE_PER_MINUTE 0.5`. Make `CongestionLevel.speedFactor` derive from these (or keep enum but add a comment).
4. **`domain/fuel/FuelModel.kt`** — new two-phase cost:
   - Input: `Route` (with per-segment `congestionFactor`) + curve + `idleLitersPerHour` + optional `coldStartLiters`.
   - For each segment: `t_raw = staticDurationSeconds / congestionFactor`; then `scale = route.durationSeconds /
     sum(t_raw)`; `t_i = t_raw * scale`; `v_eff = distanceKm / (t_i / 3600)`; `base = d * curve(v_eff)/100`;
     `stopGo = idle * max(0, (t_i - staticDurationSeconds)/3600) * STOP_GO_WEIGHT`.
   - `fuelLiters = sum(base + stopGo) + (coldStartLiters ?: 0)`.
   - Normalization invariant holds by construction: `sum(t_i) == route.durationSeconds`.
5. **Cold start** (3.6): add `coldStartLiters: Double = 0.0` param to `FuelModel.cost`. Do not wire the source; card 06/08 pass it.
6. **Remove `FuelType.ELECTRIC`** from `domain/model/FuelType.kt`; update `DefaultCurve` (drops any ELECTRIC branch) so it
   compiles. `HYBRID` default curve stays.
7. **`RouteRanker`**: default `valuePerMinute = 0.5` (6.5) instead of 0.0.

## Tests (JUnit 4, `domain/` package)
- `Σ t_i == route.durationSeconds` for several mixes (invariant).
- `TRAFFIC_JAM` segment costs more fuel than `NORMAL` for equal distance.
- `NONE` resolution + factors=1 equals pure uniform time scaling.
- `weightedSpeedFactor`: 50/50 mix returns the mean; empty → 1.0.
- cold-start liters added exactly once.
- ranking: with `valuePerMinute` a fast-but-pricier route can rank above a slow cheaper one.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
```

## Done when
- Tests green. This card compiles on its own (domain only); card 06 consumes the new `FuelModel`/`Route` shape.

Report the exact new signatures (`FuelModel.cost`, `Route`, `RouteSegment`) so card 06 knows what to wire to.

Do **not** commit.