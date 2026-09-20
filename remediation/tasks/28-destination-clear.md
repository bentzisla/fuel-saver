# Task 28 — Destination "נקה" (clear) button

**User request (round 2):** #2 — the destination text field is missing a clear button.
**Depends on:** none (trivial UI).
**Touches:** `ui/route/RouteScreen.kt`, `res/values/strings.xml`.

## Objective
A one-tap clear on the destination field (and origin if it lacks one), so the user doesn't have to backspace a long
address.

## Current state
- `RouteViewModel` has `onDestinationChange` (clears placeId/coords on edit) but there is no explicit clear action for
  the destination. The origin has `onClearOriginLocation` for the current-location case but the text fields lack a
  trailing clear (X/נקה) affordance.

## Steps
1. Add a trailing clear icon (X) to the destination `OutlinedTextField` that clears `destination`,
   `destinationPlaceId`, `destinationLocation`, and suggestions via a `viewModel.clearDestination()`.
2. Add the same clear affordance to the origin field when it holds a typed/selected value.
3. Hebrew content-description "נקה".

## Verify
```
.\gradlew.bat assembleDebug --console=plain
```

## Done when
Both origin and destination fields have a working clear button.

Do **not** commit.
