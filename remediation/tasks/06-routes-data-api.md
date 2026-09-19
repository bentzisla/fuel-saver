# Task 06 — Routes data + API + caching + errors + UI wiring

**REMEDIATION items:** Phase 4 (4.1–4.6) and the mapper side of Phase 3 (3.3, 3.5, 3.8).
**Depends on:** card 05 (new `Route`/`FuelModel`/`CongestionModel` shapes) and card 02 (active vehicle + no `"default"`).
**Touches:** `data/routes/*`, `ui/route/*` (and adds a header interceptor in `di/NetworkModule.kt`).

## Objective
Wire the corrected domain model to the real API: departure time, vehicle modifiers, route-level traffic fallback,
unknown-toll handling, caching, and a real error taxonomy (no raw exception messages).

## Current state
- `data/routes/RoutesDtos.kt`: `ComputeRoutesRequest` lacks `departureTime`/`routeModifiers`; `distanceMeters` is `Double`;
  `parseDurationSeconds` silently returns 0 on garbage.
- `data/routes/RoutesMapper.kt` uses route-level `trafficScale` + `dominantCongestion` (replaced by card 05 model).
- `data/routes/RoutesRepository.kt` hits the network every call; no cache; `BuildConfig.MAPS_API_KEY` in a query param.
- `ui/route/RouteViewModel.kt`: no departure time; `error = e.message`.

## Steps

1. **DTO** (`RoutesDtos.kt`):
   - `distanceMeters`: `Int` (route, leg, step).
   - `ComputeRoutesRequest`: add `departureTime: String?` (ISO-8601 UTC), `routeModifiers: RouteModifiersDto?`
     (`vehicleInfo { emissionType }`, `tollPasses`, `avoidTolls`), `requestedReferenceRoutes: List<String>?`.
   - Keep `MoneyDto.currencyCode`; add a `tollUnknown` derivation point (in mapper).
2. **Mapper rewrite** (`RoutesMapper.kt`) to the card-05 domain:
   - Build per-leg `List<CongestionInterval>` from leg `speedReadingIntervals`; if a leg has none, fall back to
     `route.travelAdvisory.speedReadingIntervals` mapped over the **route** polyline → set resolution `ROUTE_AVERAGE`;
     else `PER_SEGMENT`; if still none → `NONE` (congestionFactor 1.0 everywhere).
   - Per-step `congestionFactor = CongestionModel.weightedSpeedFactor(...)`; `congestion` = `dominantLevel(...)` for color.
   - `tollCost` + `tollUnknown = (estimatedPrice == null)`; keep currency.
   - `parseDurationSeconds` **throws** `RoutesParseException` on malformed input (no silent 0).
3. **`RoutesError`** sealed (`data/routes/RoutesError.kt`): `NoNetwork, Quota(429), Forbidden(403), NoRoute,
   SingleRouteOnly, Invalid(message), Parse(cause), Unknown(cause)` + a `fun from(t: Throwable): RoutesError`.
4. **`CachingRoutesRepository`** decorator wrapping `GoogleRoutesRepository`: in-memory LRU keyed on
   `(originKey, destinationKey, departureTime bucket 10 min)`, TTL 10 min; expose a `refresh()` bypass.
5. **Headers interceptor** in `di/NetworkModule.kt`: add `X-Android-Package: com.fuelroute` and
   `X-Android-Cert: <SHA-1>` (fill from `.\gradlew.bat signingReport`; store the cert string as a private constant).
6. **`RouteViewModel`**: departure-time state (default now, past clamped to now); pass `emissionType` from active
   vehicle `fuelType`; call `CachingRoutesRepository`; map errors through `RoutesError` into `RouteUiState.error`
   (add a `RoutesError?`; render a Hebrew string + suggested action in `RouteScreen`); single-route case → distinct
   message; store `departureTimeMs` + selected route index in `RouteSearch` (extend `RouteSearch` + repo accordingly).
7. **UI** (`RouteScreen.kt`): date/time picker (default "עכשיו"), "רענן" bypass button, `tollUnknown` badge
   "אגרה לא ידועה" on relevant cards, `trafficResolution` label.

## Tests
- MockWebServer (or a fake `RoutesService`): request body contains `departureTime` and `routeModifiers`; the
  `X-Android-Package`/`X-Android-Cert` headers are sent.
- Mapper: malformed duration throws; route-level fallback sets `ROUTE_AVERAGE`; null toll sets `tollUnknown`.
- Cache: second call within TTL does not hit the service.
- `RoutesError.from` maps `HttpException(429)/(403)`, `IOException`, empty routes.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
- Tests green; app builds.
- Same route at two different departure times returns different `duration` in the UI, and the error screen shows a
  friendly message (not `HTTP 403`).

Do **not** commit.