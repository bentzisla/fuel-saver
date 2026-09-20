# Task 33 — Calibration UX (process + windows)

**User request (round 2):** #7 — improve the calibration process and windows.
**Depends on:** card 18 (`CalibrationScreen`/`CalibrationFitter`/overrides) + card 32 (curve basis).
**Touches:** `ui/debug/CalibrationScreen.kt`, `ui/debug/CalibrationViewModel.kt`, `ui/refuel/*` (refuel calibration),
`res/values/strings.xml`.

## Objective
Turn calibration from a cryptic debug screen into a guided, explainable flow the user can actually use.

## Current state (read these)
- `ui/debug/CalibrationScreen.kt` (card 18) edits 6 numeric override fields, resets, and fits a global correction vs
  linked trips via `CalibrationFitter`.
- `ui/refuel/RefuelViewModel.kt` (card 04) calibrates on a full refuel via `RefuelCalibrator` (Exact/Clamped/
  Insufficient) and warns when Clamped.

## Steps
1. **Explainability**: every override field shows its current default value and provenance ("ערך ברירת מחדל: …"), and
   the fit shows before/after MAPE with a "התאם" plus a clear rollback ("בטל שינוי").
2. **Guided flow**: a step-by-step view — (a) check how much linked data you have, (b) run the fit, (c) review the
   suggested factor + expected accuracy change, (d) apply or discard. Simplify the raw number-editing into
   "מומלץ / ברירת מחדל" presets plus optional advanced numeric editing behind an "מתקדם" expand.
3. **Rename/locate it better**: surface it from Settings under a friendly "כיול הדגם" (not buried), and link the
   refuel calibration (Expected vs actual after a full fill) into the same screen as the "מכיול תדלוק" result.
4. Keep `RefuelCalibrator`/`CalibrationFitter` pure and unchanged; this is UI polish only.

## Tests (JUnit 4)
No new domain logic; if you extract any mapping (e.g. accuracy→"low/med/high" label) put it in `domain/` and test it.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
Calibration is guided, explainable, has a clear apply/rollback, and is easy to reach and understand.

Do **not** commit.