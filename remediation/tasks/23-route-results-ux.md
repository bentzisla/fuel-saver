# Task 23 — Route results UX (map, duration, detail)

**User request:** #5 — results are inconvenient: the map only shows the auto-chosen route, tapping a route shows a
"מקטעים" table instead of a map/graph, and price is shown large but not the trip duration.
**Depends on:** none (builds on cards 06/08/12 route UI).
**Touches:** `ui/route/RouteScreen.kt`, `ui/route/RouteMap.kt`, `res/values/strings.xml`.

## Objective
Make results convenient: highlight the *selected* route on the map, show duration next to price, and show a map/graph
on route tap.

## Current state (read these)
- `RouteMap.kt` draws all polylines but colors `index == 0` (ranked cheapest) green and the rest low-contrast gray —
  the user's `selectedIndex` is ignored, and the map is a fixed 240dp strip that auto-fits bounds (so overlapping
  routes look like one).
- `RouteScreen.RouteCard` shows cost; `RouteDetailDialog` shows only a `LazyColumn` of `SegmentRow` ("מקטעים").

## Steps
1. Pass `selectedIndex` into `RouteMap` and color the **selected** route prominently, others muted; increase contrast
   and (optionally) number each polyline. Re-fit the camera to the selected route on selection change.
2. Add trip **duration** to each result card next to the price (and to `ResultsSummary`), with both ₪ and minutes
   prominent.
3. Replace/augment the detail dialog's segments table with a compact **map** of that single route (reuse `RouteMap`
   with a single-route overload) or a speed-vs-distance graph; keep the segment breakdown behind a toggle/expand.
4. Keep the "הזול ביותר"/"המהיר ביותר" badges + deltas (card 08) intact.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```
Manual (device): selecting different cards moves the map highlight; duration is visible; tapping a route shows a
map/graph.

## Done when
Map follows the selected route, duration shows next to price, and route tap shows a map/graph.

Do **not** commit.
