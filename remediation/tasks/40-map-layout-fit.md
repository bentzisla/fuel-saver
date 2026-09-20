# Task 40 — Route map layout fit on the page

**User request (round 3):** #6 — the map (now with all routes + numbers) is great but doesn't fit well on the page.
**Depends on:** card 27 (map height 320dp + colors/numbers).
**Touches:** `ui/route/RouteScreen.kt`, `ui/route/RouteMap.kt`, `res/values/strings.xml`.

## Objective
Make the map integrate cleanly with the page instead of looking bolted-on.

## Current state (read these)
- `RouteMap` (card 27) is a 320 dp strip above the results `LazyColumn`; badge markers + colors added. It likely
  fights the list for vertical space and the camera fit may clip.

## Steps
1. **Camera fit:** add generous padding to `newLatLngBounds` (e.g. 120) so routes/badges aren't clipped at the edges;
   keep the map zoomed to the selected route with all alternatives visible.
2. **Layout:** make the map height responsive (e.g. `weight`-based within a constrained header, or a max height that
   shrinks when the results list is long), so it doesn't crowd the cards.
3. **Visual integration:** consistent corner radius / separation, and ensure it doesn't scroll away oddly inside the
   LazyColumn (consider keeping it pinned above the list as a header, not a scroll item).

## Verify
```
.\gradlew.bat assembleDebug --console=plain
```
Manual (device): the map and results look balanced on a phone screen; nothing is clipped or oversized.

## Done when
The map sits naturally on the page with fitting camera bounds and balanced height.

Do **not** commit.