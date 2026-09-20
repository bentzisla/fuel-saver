# Task 36 — Curve graph: make it interactive (fewer on-curve numbers)

**User request (round 3):** #2 — the graph is much better, but the on-curve numbers make it hard to read when there are
many points; consider an interactive graph.
**Depends on:** card 32 (which added per-bin km labels) + card 24 (graph/axes).
**Touches:** `ui/curve/CurveScreen.kt`, `res/values/strings.xml`.

## Objective
Remove the always-on per-point labels (they clutter dense curves) and provide a tap/drag crosshair or per-bin
inspection instead.

## Current state (read these)
- `CurveChart` (card 32) draws up to `MAX_KM_LABELS` per-bin "N ק״מ" labels above the densest learned bins.
- The curve + uncertainty band + axis ticks/numbers (card 24) are drawn on a Canvas.

## Steps
1. **Remove the always-on per-bin km labels.** Keep the bin points (circle sized by km) but drop the text labels.
2. **Add interaction.** On tap/drag over the plot, show a crosshair + a small info chip for the nearest speed bin:
   "מהירות, ל׳/100, ק״מ בפח, דגימות, ביטחון %". Use `pointerInput` + `detectTapGestures`/`detectDragGestures` and
   expose the nearest-bin value. If drag is impractical on a static image, a tap-to-inspect is sufficient.
3. Keep the axis numbers (those are wanted); only the per-point clutter is removed.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```
Manual (device/demo): the curve is uncluttered; tapping shows a bin info chip.

## Done when
The curve is clean (no per-point text) and interactive (tap/drag shows bin details).

Do **not** commit.