# Task 25 — Combined "ride" view (recommended route + actual measurement)

**User request:** #7 — the app doesn't create rides that combine the route the app recommended with what was actually
measured.
**Depends on:** card 11 (TripLinker/DriveHistoryRepository) + card 14 (auto-logging).
**Touches:** `ui/history/*`, `data/history/*`, `data/obd/TripRecorder.kt` / `ObdEngine.kt` (link wiring),
`domain/history/*`, `res/values/strings.xml`.

## Objective
Every logged drive should surface as a single "ride" combining the recommended route with the actual measured outcome —
and History should show those combined rides clearly, not two disjoint lists.

## Current state (read these)
- Card 11 added `TripLinker.autoLink` (30-min window), `linkLatestUnlinkedTrip`, `DriveHistoryRepository` (in-memory
  join), and a "יצאתי במסלול הזה" button.
- The user reports rides aren't being created as a combined recommended+actual entity.

## Steps
1. Verify/fix the auto-link wiring end-to-end: confirm `TripRecorder.end`/`ObdEngine` actually calls `autoLink` on trip
   close with the correct `tripStartMs`, and that a trip starting shortly after a search links automatically.
2. Make the History screen present each linked pair as one "ride" card: origin→destination, predicted ₪/L/min vs
   actual ₪/L/min, delta, and a clear state label ("ממתין לנתוני OBD" / "מקושר" / "ללא חיזוי").
3. Widen/improve the match rule if too strict (match by destination `placeId`/label when available, fall back to
   most-recent within the window), and expose an explicit "קשר נסיעה" action on unlinked rides so the user can pair
   them by hand.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat lintDebug --console=plain
```
Manual (device/car): one search + one logged drive → History shows a combined ride with both predicted and actual.

## Done when
A search followed by a logged drive produces one combined "ride" in History showing predicted vs actual.

Do **not** commit.
