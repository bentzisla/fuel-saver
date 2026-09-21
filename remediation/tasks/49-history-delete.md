# Task 49 — History: delete a ride

**User request:** #4 — you cannot delete historic rides.
**Depends on:** none (runs after 48 because both touch `ui/history/*` + `data/history/DriveHistoryRepository.kt`).
**Touches:** `data/db/Daos.kt`, `data/history/DriveHistoryRepository.kt`, `ui/history/HistoryViewModel.kt`,
`ui/history/HistoryScreen.kt`, `res/values/strings_history.xml`.

## Objective
The user can delete any History entry (a drive and/or the route search behind it) with a confirmation dialog.

## Current state (read these)
- No delete exists: `TripDao`/`RouteSearchDao` have no delete-by-id, and `HistoryScreen` has no delete affordance.
- A History entry is one of: a linked ride (`searchId` + `tripId`), an unlinked drive (`tripId`, `searchId == null`), or
  an undriven search (`searchId`, `tripId == null`). Deleting should make sense for each.

## Steps
1. **DAOs.** Add `TripDao.deleteById(id: Long)` and `RouteSearchDao.deleteById(id: Long)`. (Deleting a trip leaves the
   search as an undriven search; deleting a search while a trip is linked to it should first clear the link — see
   step 2.)
2. **Repository.** Add `DriveHistoryRepository.delete(entry: DriveHistoryEntry)`, deleting:
   - `entry.tripId != null` → delete the trip (the linked search, if any, reverts to undriven).
   - `entry.searchId != null && entry.tripId == null` → delete the search (an undriven search).
   If deleting a search that still owns a linked trip, clear that trip's `routeSearchId`/`linkedAtMs` first (add a DAO
   helper `unlinkTrip(tripId)` or reuse `linkToRouteSearch(tripId, null, …)` pattern; `routeSearchId` is nullable).
3. **ViewModel.** Add `pendingDelete: DriveHistoryEntry?` + `requestDelete(entry)`, `confirmDelete()`,
   `cancelDelete()`; `confirmDelete` calls `repository.delete(...)` then `load()`.
4. **UI.** Add a delete affordance to `HistoryRideCard` — a small delete `IconButton` (top-right) that calls
   `requestDelete`, plus an `AlertDialog` confirmation (`history_delete_title` / `history_delete_message` /
   `history_delete_confirm` / `history_delete_cancel`). Ensure the delete tap does not also trigger the card's
   `onClick` detail (card 48): use a separate `IconButton` with its own click handler.
5. Add new Hebrew strings to `res/values/strings_history.xml`.

## Verify
```
# run by the orchestrator:
.\gradlew.bat testDebugUnitTest lintDebug --console=plain
```
Manual (device): delete a linked ride → it disappears and the search (if any) becomes undriven; delete an undriven
search → it disappears; delete is confirmed, never silent.

## Done when
Every History entry can be deleted with confirmation, and the underlying trip/search rows are cleaned up correctly.

Do **not** commit.
