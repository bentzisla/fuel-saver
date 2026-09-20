# Task 39 — Tolls: stop the always-red "אגרה לא ידועה" (or show "אין אגרה")

**User request (round 3):** #5 — "אגרה לא ידועה" is always shown in red; if there's no toll, show nothing or "אין אגרה".
**Depends on:** card 06 (tollUnknown) + 27 (toll display).
**Touches:** `data/routes/RoutesError.kt` if needed, `ui/route/RouteScreen.kt`, `domain/model/Route.kt` (if a state is
missing), `res/values/strings.xml`.

## Objective
The toll label must reflect reality: show "אגרה לא ידועה" only when the API truly couldn't determine tolls; otherwise
show "אין אגרה" (neutral, not red), and hide the badge entirely when there is no toll.

## Current state (read these)
- `Route.tollCost: Double?` + `tollUnknown: Boolean` (card 06). Card 27 renders toll as "ללא אגרה" when 0, "—" when
  unknown. The user still sees a red "אגרה לא ידועה" badge in some path (likely `RouteErrorCard`/card badge) even on
  toll-free routes.

## Steps
1. Find every path that renders the red "אגרה לא ידועה" text and make it state-aware:
   - toll present → show the amount (neutral),
   - no toll (`tollCost == null && !tollUnknown`) → show "אין אגרה" in neutral color, or nothing,
   - `tollUnknown == true` → show "אגרה לא ידועה" (amber/red) only then.
2. Verify the mapper sets `tollUnknown` only when `tollInfo.estimatedPrice` is absent AND the request asked for tolls
   (i.e. not simply "no toll road"). If the mapper currently over-reports `tollUnknown`, fix it in `RoutesMapper`.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```
Manual: a toll-free route shows "אין אגרה" or nothing (not a red badge).

## Done when
"אגרה לא ידועה" appears only when tolls are genuinely unknown; otherwise "אין אגרה" or nothing.

Do **not** commit.