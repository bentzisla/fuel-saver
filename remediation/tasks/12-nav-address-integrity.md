# Task 12 — Fix destination corruption when handing off to Google Maps / Waze

**User request:** #2 — picking an address and sending it to the external nav app changes it
("יצחק שדה, הרצליה" arrives as "יצחק שדה, תל אביב").
**REMEDIATION items:** 8.2 (urgent user-facing bug; independent of the other cards — can run immediately).
**Depends on:** none. (Card 10 adds `route_search.destinationPlaceId/Lat/Lng`; this card works without it, but if
card 10 has landed, persist them too.)
**Touches:** `nav/NavigationLauncher.kt`, `ui/route/RouteScreen.kt`, `ui/route/RouteViewModel.kt`,
`data/places/PlacesRepository.kt` + `PlacesDtos.kt` (place details / coordinates), `data/places/PlacesHistoryRepository.kt`.

## Root cause (already diagnosed — do not re-investigate from scratch)
Two defects compound:

1. **The city is thrown away at selection time.** `PlaceSuggestion` carries `mainText` ("יצחק שדה") *and*
   `secondaryText` ("הרצליה, ישראל"), but `RouteViewModel.onDestinationSelect()` does
   `destination = suggestion.mainText` — dropping the city. The `placeId` is kept in state but never used for the
   hand-off.
2. **The hand-off is by free text, so the other app re-geocodes it.** `nav/NavigationLauncher.openGoogleMaps()` puts
   the bare label in `?destination=<text>` and `openWaze()` in `waze://?q=<text>`. Google Maps / Waze then resolve
   that ambiguous street name themselves and pick the wrong city. A street named "יצחק שדה" exists in several cities,
   so the result is effectively random.

The fix is to stop sending prose and start sending an unambiguous identifier: a `place_id` or explicit coordinates.

## Steps

1. **Carry the identity, not just a label.** Introduce a small type (e.g. `nav/NavDestination.kt` or reuse
   `RouteWaypoint`): `label: String`, `placeId: String?`, `lat: Double?`, `lng: Double?`. Thread it from
   `RouteUiState` (which already has `destinationPlaceId` / `destinationLocation`) into `RouteDetailDialog` →
   `NavigationLauncher`, replacing the current `destination: String` parameter.
2. **Keep the full display label.** In `onDestinationSelect`, store the complete text
   (`mainText` + ", " + `secondaryText` minus a trailing ", ישראל"/", Israel") for display and as a *last-resort*
   fallback query. Do the same for `onRecentSelected`. This alone fixes the common case.
3. **Resolve coordinates for the chosen place.** `PlaceSuggestion` has no lat/lng today. Add a Places **Place Details**
   call (`GET /v1/places/{placeId}` with `X-Goog-FieldMask: location,formattedAddress,displayName`, or include
   `location` in the autocomplete-follow-up) exposed as
   `PlacesRepository.details(placeId): PlaceDetails?` with `latitude`, `longitude`, `formattedAddress`.
   Call it when a suggestion is selected (not on every keystroke) and cache it in `RouteUiState`.
   If the details call fails, fall back to the full label — never crash, never block navigation.
4. **Google Maps: send `destination_place_id`.** The Maps URL API accepts
   `https://www.google.com/maps/dir/?api=1&destination=<label>&destination_place_id=<id>&travelmode=driving`.
   `destination_place_id` wins over the text, which removes the ambiguity. Prefer, in order:
   `destination_place_id` → `destination=<lat>,<lng>` → `destination=<full label>`.
   Do the same for the origin (`origin_place_id`) when we have one; keep the existing `waypoints` behaviour.
5. **Waze: send coordinates.** Waze re-geocodes `?q=` too. Use `waze://?ll=<lat>,<lng>&navigate=yes` when
   coordinates are known (fall back to `?q=<full label>&navigate=yes`, then to Google Maps as today).
   Note: Waze has no place-id concept — coordinates are the only exact form.
6. **Persist the identity with the search.** If card 10 has landed, write `destinationPlaceId`/`destinationLat`/
   `destinationLng` on the `route_search` row and store lat/lng on `RecentPlace` (the field already exists) so a
   re-navigate from history/recents is exact too.

## Tests (JUnit 4, pure — extract URI building out of the Android call)
Move URI construction into a pure function (e.g. `NavigationUris.googleMaps(dest, origin, waypoints)` /
`NavigationUris.waze(dest)`) so it can be tested without a `Context`:
- place-id present → URL contains `destination_place_id=` and `travelmode=driving`;
- only coordinates → `destination=32.1234,34.8567` (Locale-independent formatting — use `Locale.US`);
- only label → the full "street, city" label is URL-encoded, **not** just the street;
- Waze with coordinates → `ll=` form; Waze without → `q=` form;
- a label containing Hebrew + comma round-trips through encoding correctly.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
- Selecting "יצחק שדה, הרצליה" and tapping navigate opens Google Maps **and** Waze on the Herzliya address.
- The destination field in the app shows the city, not just the street.
- A destination reused from recents/history navigates to the same exact place.
- Hebrew labels with commas are not mangled in the URL.

Do **not** commit.
