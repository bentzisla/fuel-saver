# Task 29 — History: don't claim "חסכת" for routes never driven

**User request (round 2):** #3 — search history shows "חסכת" even though those routes were not actually taken.
**Depends on:** card 25 (combined rides) if it landed, else card 11 (DriveHistory).
**Touches:** `ui/history/HistoryScreen.kt`, `ui/history/HistoryViewModel.kt`, `data/history/DriveHistoryRepository.kt`,
`res/values/strings.xml`.

## Objective
Only claim a saving ("חסכת X ₪") for drives that actually happened (a linked OBD trip). Un-driven searches are just
searches, not savings.

## Current state (read these)
- `HistoryScreen` lists `route_search` rows and shows a "חסכת …" summary. Card 11 added the predicted-vs-actual join
  (`DriveHistoryRepository`), and each `DriveHistoryEntry` has `hasActual` (tripId + actualCost).
- The "חסכת" figure (`savedAmount`) is the search-time difference vs the fastest route — it is computed at search time
  regardless of whether the drive happened.

## Steps
1. Gate the "חסכת" claim on `hasActual`: for an entry with no linked trip, show it as a search ("חיפוש", predicted ₪)
   with a "לא נסעו במסלול זה" hint instead of a saving.
2. For linked drives, keep the predicted-vs-actual delta (card 11) and the saving, and label it clearly
   ("חיסכון מול המהיר", only when it actually applied).
3. Adjust the summary ("חסכת X ₪ בסה״כ") to sum only actual, linked drives.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
An un-driven search no longer shows "חסכת"; only linked drives contribute to the saving summary.

Do **not** commit.
