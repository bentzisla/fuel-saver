# Task 22 — Live dashboard layout & sizing

**User request:** #4 — the fuel-consumption dashboard is not properly organized; windows/sections have wrong sizes.
**Depends on:** none (pure UI; builds on card 03's debug fields + card 07's keep-screen-on).
**Touches:** `ui/stats/StatsScreen.kt`, `res/values/strings.xml`.

## Objective
Reorganize the live dashboard into a clear, well-proportioned hierarchy instead of the current unstructured stack.

## Current state (read these)
- `ui/stats/StatsScreen.kt` renders the live dashboard + a debug card (Hz / battery / VIN from card 03). The user
  reports the sections are cramped and mis-sized.

## Steps
1. Redesign the live section with a clear hierarchy:
   - primary **speed** readout (very large),
   - a row of **instant consumption** (L/100km and L/h),
   - **trip ₪ + liters + distance** as a grouped card,
   - secondary chips: rpm, coolant, battery voltage, fuel level.
   Use consistent `Card`s, spacing, and typography; keep it RTL-correct and glanceable while driving.
2. Collapse the card-03 diagnostics (Hz, voltage, VIN) into a collapsible "אבחון" section rather than a prominent card.
3. Do not regress card 07's keep-screen-on toggle + large-text behavior.

## Verify
```
.\gradlew.bat assembleDebug --console=plain
```
Manual (device / `SimulatedObdTransport` demo): the layout reads cleanly on a phone screen.

## Done when
The dashboard has a clear visual hierarchy and correct sizing; nothing is cramped or mis-sized.

Do **not** commit.
