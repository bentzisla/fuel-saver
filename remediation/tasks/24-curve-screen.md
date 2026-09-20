# Task 24 — Curve screen polish + delete safety

**User request:** #6 — "עקומת הרכב שלי" is far from done: graph proportions are off, no numbers on the graph, and it's
too easy to accidentally delete car records.
**Depends on:** none (pure UI; builds on card 04 curve/bin work).
**Touches:** `ui/curve/CurveScreen.kt`, `ui/curve/CurveViewModel.kt`, `res/values/strings.xml`.

## Objective
A legible, well-proportioned curve with axis numbers, and delete flows that can't accidentally erase learned data.

## Current state (read these)
- `CurveScreen` renders the learned vs default curve via a custom Compose `Canvas`. The user reports the proportions
  are bad, there are no axis numbers, and it's too easy to delete car records.

## Steps
1. **Graph:** use a fixed/aspect-correct drawing area; draw speed (km/h) ticks on X and L/100km ticks on Y with
   gridlines; clamp the Y range so the curve isn't squashed against the top; label the learned curve, its uncertainty
   band, the manual curve, and the default curve; add a legend.
2. **Delete safety:** any "אפס למידה"/"מחק" action must show a confirmation dialog (clear Hebrew) before deleting
   bins/trips; deleting a whole vehicle requires a second explicit confirmation and is never a single tap. Disable the
   destructive button when there is nothing to delete.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```
Manual (device / `SimulatedObdTransport` demo): curve is legible with axis numbers; delete requires confirmation.

## Done when
Graph is proportioned and labelled; deleting car data requires explicit confirmation.

Do **not** commit.
