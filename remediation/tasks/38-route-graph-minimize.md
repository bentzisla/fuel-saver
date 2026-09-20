# Task 38 — Route page: the extra graph is unnecessary (collapse/explain)

**User request (round 3):** #4 — the route page now shows a new graph that wasn't there before and doesn't feel
necessary. Minimize it by default or explain it.
**Depends on:** card 27 (added the on-page route graph).
**Touches:** `ui/route/RouteScreen.kt`, `res/values/strings.xml`.

## Objective
Don't force the speed-vs-distance graph onto the results page: collapse it by default behind a clearly-labelled
toggle, with a one-line explanation of what it shows.

## Current state (read these)
- Card 27 renders `RouteSpeedGraph` directly on the results page in a card for the selected route, with tap-to-expand.

## Steps
1. **Collapse by default.** The on-page graph becomes a collapsed row/toggle ("הצג גרף מהירות") rather than an always-
   visible card. The detail dialog keeps the graph available when opened.
2. Add a short description ("גרף מהירות לפי מרחק לאורך המסלול — מראה איפה המסלול איטי/פקוק").
3. Keep the card info (time/distance/L/₪/toll/ETA) unchanged.

## Verify
```
.\gradlew.bat assembleDebug --console=plain
```

## Done when
The graph is collapsed/explained, not shown by default on the results page.

Do **not** commit.