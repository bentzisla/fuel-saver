# Task 44 — Show tolls when the route actually has them

**User request:** #6 — Route doesn't show tolls even when there are tolls.
**Depends on:** none.
**Touches:** `data/routes/RoutesDtos.kt`, `data/routes/RoutesService.kt`, `data/routes/RoutesMapper.kt`,
`ui/route/RouteScreen.kt`, `res/values/strings_tolls.xml` (create this file; do NOT touch `res/values/strings.xml`).

## Objective
When Google returns `travelAdvisory.tollInfo.estimatedPrice`, the result card must show the toll amount clearly and
include it in the total. Today the user reports tolls never appear even on toll roads (e.g. Route 6).

## Current state (read these)
- `RoutesDtos.ROUTES_FIELD_MASK` ends with `routes.travelAdvisory.tollInfo.estimatedPrice`. `extraComputations` defaults
  to `listOf("TRAFFIC_ON_POLYLINE", "TOLLS")`. `RoutesRequestFactory` sets `routeModifiers.vehicleInfo.emissionType`
  from the active vehicle (so tolls should be computed).
- `RoutesMapper.toRoute` parses `tollInfo?.estimatedPrice?.firstOrNull()?.let { it.units + it.nanos / 1e9 }`, sets
  `tollCost` (0.0 when `tollInfo == null`, null when present-but-empty) and `tollUnknown`. `FuelModel.cost` adds
  `route.tollCost ?: 0.0` into `totalCost`.
- `RouteCard` already renders a "אגרה" InfoColumn and a `route_toll_unknown` line; these are unit-tested
  (`RoutesMapperTest`, `RoutesFixtureTest`, `FuelModelTest`) and pass. So the parsing/display is believed correct and
  the failure is almost certainly upstream — the request is not actually producing `tollInfo`.

## Steps
1. **Confirm the live request.** Check `RoutesService` (`X-Goog-FieldMask: $ROUTES_FIELD_MASK`) and
   `ComputeRoutesRequest`. Two likely culprits to fix:
   - The field mask may need the **parent** `routes.travelAdvisory.tollInfo` (not just `...tollInfo.estimatedPrice`) to
     actually return the object. Adjust `ROUTES_FIELD_MASK` to include `routes.travelAdvisory.tollInfo` (and keep the
     `estimatedPrice` path if you prefer). Also confirm `routes.distanceMeters`/`routes.duration` etc. remain.
   - `MoneyDto.currencyCode` has a default `"ILS"`; the mask does not request it — that is fine for the amount, but
     confirm the API isn't dropping `estimatedPrice` because `currencyCode` was filtered. If needed, request
     `routes.travelAdvisory.tollInfo.estimatedPrice.currencyCode` too.
2. **Verify end-to-end without a car** by extending `RoutesFixtureTest` / `RoutesMapperTest` with a fixture whose JSON
   mirrors exactly what the field mask returns (or use the existing `with-tolls.json`). Assert the domain `Route` has a
   non-zero `tollCost`. If `with-tolls.json` has no `tollInfo` at the top level, that is the smoking gun: fix the mask
   and re-assert.
3. **Make the toll unmissable in `RouteCard`.** When `cost.route.tollCost > 0`, show a dedicated line
   `route_toll_amount` ("אגרה: ₪ %1$s") in a distinct colour (secondary/primary), not buried in a small InfoColumn.
   Keep `route_toll_unknown` for the unknown case and `route_toll_free` for genuinely toll-free routes. Ensure
   `totalCost` visibly includes the toll (it already does via `FuelModel`, but add a `route_total_includes_toll` hint
   when toll > 0 if you think it helps).
4. Add all new Hebrew strings to `res/values/strings_tolls.xml`.

## Verify
```
# run by the orchestrator:
.\gradlew.bat testDebugUnitTest lintDebug --console=plain
```
Manual (device): a Route-6 route shows "אגרה: ₪ …" on its card and the total is fuel + toll.

## Done when
A route with `tollInfo.estimatedPrice` shows the toll amount prominently and in the total, backed by a passing
end-to-end fixture test.

Do **not** commit.
