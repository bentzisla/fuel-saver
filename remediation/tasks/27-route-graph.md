# Task 27 — Route graph & results page usability

**User request (round 2):** #1 — the per-route graph is not well built/readable; make the route page more usable.
**Depends on:** card 23 (which added the first `RouteSpeedGraph` + numbered map badges).
**Touches:** `ui/route/RouteScreen.kt`, `ui/route/RouteMap.kt`, `res/values/strings.xml`.

## Objective
A cleaner, more readable route comparison: a proper per-route graph, and a results page that is genuinely convenient to
compare routes.

## Current state (read these)
- `RouteSpeedGraph` (card 23) is a raw speed-vs-distance `Canvas` with gridlines and congestion dots, shown inside the
  detail dialog. The user finds it hard to read.
- `RouteMap` (card 23) highlights the selected route with a numbered badge; results are ranked cards.

## Steps
1. **Rebuild the graph** to be readable: a labelled Y axis (km/h) and X axis (km) with unit ticks, a smooth speed line
   with a clear congestion color band under it (green→red), a legend, and a fixed, generous height. Show the graph for
   the selected route directly on the results page (not buried in a dialog), with a tap-to-expand for full detail.
2. **Route page usability**: on each route card show, compactly: total time, distance, predicted L, predicted ₪, toll
   (or "ללא אגרה"), and the ETA ("הגעה ב־HH:MM"). Keep the cheapest/fastest badges + deltas. Add a one-line "למה
   המסלול הזה?" hint (e.g. "חוסך ₪2 לעומת המהיר, +8 דק׳").
3. Make the map taller and always show all alternatives legibly (distinct colors, not just selected-vs-muted).

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```
Manual (device): results are glanceable; the graph is readable with labelled axes.

## Done when
The route graph is clearly readable and the results page shows time/distance/liters/₪/toll/ETA compactly.

Do **not** commit.
