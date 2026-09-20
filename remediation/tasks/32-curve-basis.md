# Task 32 — Curve screen: show what the curve is based on

**User request (round 2):** #6 — "עקומת הרכב שלי" should show more about what the curve is based on.
**Depends on:** card 24 (graph/legend).
**Touches:** `ui/curve/CurveScreen.kt`, `ui/curve/CurveViewModel.kt`, `res/values/strings.xml`.

## Objective
Surface the data behind the curve: how much learned vs manual vs default is contributing, how many samples/km per bin,
and the confidence weighting.

## Current state (read these)
- `CurveScreen` (card 24) draws learned/manual/default/effective lines with an uncertainty band sized by per-bin km.
- `CurveBlender` in `domain` exposes the confidence weight `w = km/(km+20)`; `LearnedCurve` holds per-bin `km`/`samples`.

## Steps
1. Add a summary block above/below the graph showing, per vehicle: total learned km, sample count, idle L/h,
   calibration factor, and the current blend split ("60% מהנקודות מבוססות על למידה, השאר על ברירת מחדל").
2. On the graph, show per-bin confidence (km) as a small label or tooltip on selected bins, and a "מה הבסיס?" help
   line explaining the three sources (learned/manual/default) and the w=km/(km+20) weighting.
3. Add an explicit "איכות הנתונים" indicator (e.g. learned km grand total → low/medium/high).

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
The curve screen clearly shows how much learned data backs the curve and what the curve is based on.

Do **not** commit.