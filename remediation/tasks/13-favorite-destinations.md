# Task 13 — Favorite destinations

**User request:** #3 — "add favorite destinations".
**REMEDIATION items:** 8.3.
**Depends on:** card 10 (`favorite_destination` table + `FavoriteDestinationDao`). Best run **after** card 12 so
favorites store the exact `placeId`/coordinates instead of an ambiguous label.
**Touches:** `data/places/FavoritesRepository.kt` (new), `ui/route/RouteScreen.kt`, `ui/route/RouteViewModel.kt`,
`ui/favorites/*` (new, optional manage screen), `res/values/strings.xml`.

## Objective
Let the user pin the handful of places they actually drive to (בית, עבודה, ...) and pick one in a single tap, instead
of retyping an address or hunting through the 10-entry recents list.

## Current state (read these first)
- `data/places/PlacesHistoryRepository.kt` — `RecentPlace(label, placeId, latitude, longitude)` in DataStore,
  capped at `MAX_ENTRIES = 10`, deduped by `placeId` then `label`. This is **recents**, not favorites: it is
  auto-populated, evicts silently, and has no ordering or naming. Do not overload it.
- `ui/route/RouteScreen.kt` already renders a recents row (`state.history`) driven by `onRecentSelected`.
- `RouteUiState` has `destination`, `destinationPlaceId`, `destinationLocation`.
- Card 10 adds `favorite_destination(id, label, placeId, latitude, longitude, sortOrder, createdAtMs)` + DAO
  (Room, so favorites are durable and included in the 1.5 backup/export).

## Steps

1. **`data/places/FavoritesRepository.kt`** wrapping `FavoriteDestinationDao`:
   - `val favorites: Flow<List<FavoriteDestination>>` (ordered by `sortOrder`, then `createdAtMs`)
   - `suspend fun add(label: String, placeId: String?, lat: Double?, lng: Double?)` — dedupe by `placeId` when
     present, else by normalized `label`; assign `sortOrder = max + 1`.
   - `suspend fun rename(id: Long, label: String)` — so a place can be saved as "בית" rather than the raw address.
   - `suspend fun remove(id: Long)`, `suspend fun move(id: Long, newSortOrder: Int)`
   - `suspend fun isFavorite(placeId: String?, label: String): Boolean`
   Keep the domain type in `domain/model/` or alongside the repo, free of Room annotations.
2. **Star toggle on the route screen.** Next to the resolved destination (and on each recents chip), show a
   star/outline-star that calls `add`/`remove`. Use the exact `placeId` + coordinates currently in `RouteUiState`
   (post-card-12 these are reliable) so navigating from a favorite is unambiguous.
3. **Favorites row.** Above/replacing the recents row in `RouteScreen`, render favorites as tappable chips
   (label only, star-filled). Tapping one fills the destination **and** its `placeId`/coordinates, then triggers
   `compute()`. Keep recents visible below, minus anything already favorited.
4. **Rename + manage.** A long-press (or an overflow item) opens a small dialog to rename or delete. If the list grows
   past ~6, add a simple `ui/favorites/FavoritesScreen.kt` reachable from the Route tab with reorder (move up/down is
   sufficient — no drag-and-drop needed) and delete-with-confirm.
5. **Empty state.** With no favorites, show a one-line hint ("סמן יעד בכוכב כדי לשמור אותו") instead of an empty row.
6. **Backup.** If card 1.5 (`BackupRepository`) has landed, include `favorite_destination` in the export/import JSON
   and dedupe on import by `placeId`/label. If it has not, leave a `TODO(1.5)` comment naming the table.

## Tests (JUnit 4, MockK for the DAO)
- `add` dedupes by `placeId`, and by normalized label when `placeId` is null (trim + case/whitespace-insensitive).
- `add` assigns an increasing `sortOrder`; `move` reorders deterministically.
- `rename` keeps the coordinates intact.
- `isFavorite` matches on `placeId` even when the label differs (renamed favorite).

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
- A destination can be starred, appears as a chip, and one tap re-runs the search for it.
- A favorite can be renamed to "בית" and still navigates to the right coordinates.
- Favorites survive an app restart (Room, not in-memory) and are not evicted by the 10-entry recents cap.

Do **not** commit.
