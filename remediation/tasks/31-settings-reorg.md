# Task 31 — Reorganize/simplify the (too large) Settings screen

**User request (round 2):** #5 — Settings has become too large and complex.
**Depends on:** cards 17 (backup), 18 (calibration), 20/21 (OBD), all of which added Settings content.
**Touches:** `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`, `res/values/strings.xml`.

## Objective
Group the now-large Settings into a clean structure instead of one long scrolling column.

## Current state (read these)
- `SettingsScreen` is a single `Column` with: fuel price + pin, value/minute, nav app, auto-connect + `AutoLoggingSection`
  (intro card + status card), show-overlay, keep-screen-on, retention chips, battery-optimization card, the ELM note,
  and the version footer. Card 17 added backup export/import; card 18 added calibration entry; card 19 added version.

## Steps
1. Group into labeled sections (e.g. with section headers or a top-level `FilterChip`/tab-less grouping):
   - **דלק ומחיר** (price, pin, grade, value/minute)
   - **ניווט** (nav app)
   - **רישום OBD** (auto-connect + status, overlay, keep-screen-on, retention)
   - **נתונים וגיבוי** (export/import, calibration entry)
   - **אודות** (version footer)
2. Collapse the secondary/advanced items into expandable rows and move the `AutoLoggingSection` intro card behind the
   top-level "רישום OBD" section (still visible when relevant).
3. Keep every existing setting reachable and behaviourally identical; only the presentation changes.

## Verify
```
.\gradlew.bat assembleDebug --console=plain
```

## Done when
Settings is grouped into clear, collapsed-able sections and no setting is lost or harder to reach.

Do **not** commit.