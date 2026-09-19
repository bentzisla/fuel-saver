# Task 00b — Fix single-route results (request alternatives + handle one-route case)

**REMEDIATION item:** P0-B (urgent, before Wave 2).
**Depends on:** none. File-disjoint from 00a. Keep this card **additive and small** — card 06 will do the fuller routes
rewrite later and must absorb your changes cleanly, so do not redesign the mapper or ViewModel.
**Touches:** `data/routes/RoutesDtos.kt`, `data/routes/RoutesService.kt`, `data/routes/RoutesRepository.kt`,
`ui/route/RouteViewModel.kt`, `ui/route/RouteScreen.kt`, `res/values/strings.xml`.

## Objective
The result list already renders every route (`RouteScreen` uses `itemsIndexed(state.results)`), so the user seeing "one
route" means Google is returning a single route despite `computeAlternativeRoutes = true`. Make the app ask harder for
alternatives and make the single-route case explicit instead of silent.

## Current state (read these first)
- `data/routes/RoutesDtos.kt` — `ComputeRoutesRequest(origin, destination, travelMode, routingPreference,
  computeAlternativeRoutes = true, languageCode, units, extraComputations)`. It has **no `departureTime` and no
  `requestedReferenceRoutes`**.
- `data/routes/RoutesService.kt` — the Retrofit interface (single `computeRoutes` call).
- `data/routes/RoutesRepository.kt` — builds `ComputeRoutesRequest` from waypoints.
- `ui/route/RouteViewModel.kt` — `compute()` maps `routes` → ranked list; no route-count logging, no one-route state.
- `ui/route/RouteScreen.kt` — renders `itemsIndexed(state.results)`; no explicit one-route message.

## Steps

1. **Log the raw count.** In `RoutesRepository.getAlternatives` (or `RouteViewModel.compute`), `Log.d("FuelRoute",
   "routes returned: " + list.size + " labels=" + routeLabels)`. This confirms on-device whether Google returns 1.
2. **Ask for a comparison route.** Add to `RoutesDtos.ComputeRoutesRequest`:
   - `val requestedReferenceRoutes: List<String> = listOf("FUEL_EFFICIENT")` (serialized via kotlinx — add the field to
     the `@Serializable` data class with default).
   - `val departureTime: String? = null` (ISO-8601 UTC, e.g. `2026-09-19T12:00:00Z`).
   Then in `RoutesRepository`, pass `departureTime` from a new simple "now (UTC)" default (`Instant.now().truncatedTo...
   ` or leave null for now if you want to avoid touching RouteViewModel) — the field defaults keep the existing call
   compiling. (The user-facing time picker stays with card 06.)
   Note: a `requestedReferenceRoutes` entry is returned as a route carrying the `FUEL_EFFICIENT` label; `RouteScreen`
   already renders that label via `routeLabelRes()`.
3. **Explicit one-route state.** Add `routeCountMessage: String?` (or reuse a `RouteUiState` field) set in `compute()`:
   - size == 0 → `strings.route_no_routes`
   - size == 1 → `strings.route_single` ("נמצא מסלול אחד בלבד — אין חלופות להשוואה")
   - else → null.
   Render it in `RouteScreen` above the result cards. Add the strings to `res/values/strings.xml`.
4. **Tests** (JUnit 4, `app/src/test/.../data/routes/`): a serialized `ComputeRoutesRequest` round-trips through the DTO
   with `requestedReferenceRoutes` and `departureTime` set (use `kotlinx.serialization.json.Json.encodeToString`), and
   the `routeCountMessage` logic via a tiny pure helper (extract the size→message mapping into an object so it is
   testable without the ViewModel).

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
- `departureTime` + `requestedReferenceRoutes` appear in the outbound `computeRoutes` JSON (unit-test the DTO).
- A single-route response shows the explicit "נמצא מסלול אחד בלבד" message; a multi-route response shows 2-3 cards.
- Note: the FULL multi-route strategy (waypoint corridors, error taxonomy) remains with card 06 — don't preempt it.

Do **not** commit.