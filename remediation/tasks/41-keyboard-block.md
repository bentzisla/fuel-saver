# Task 41 — Keyboard: black block above keyboard hiding the route inputs

**User request (round 3):** #7 — a big black block appears above the keyboard, hiding part of the screen and the text
being typed.
**Depends on:** none (Compose inset/IME bug).
**Touches:** `ui/route/RouteScreen.kt`, `MainActivity.kt` / `ui/theme/Theme.kt` (window insets), `res/values/strings.xml`.

## Objective
Fix the Compose window-inset/IME handling so the keyboard doesn't produce a black (unpainted) area above it that hides
the input.

## Root cause (likely)
`RouteScreen`'s `LazyColumn` uses `.imePadding()` but the screen isn't consuming/insetting the system bars or the edge-
to-edge window insets correctly, so the area behind `imePadding()` (the IME space) is unpainted black and the focused
`OutlinedTextField` can end up scrolled under it. (Could also be a manifest `android:windowSoftInputMode="adjustResize"`
mismatch.)

## Steps
1. Enable proper edge-to-edge insets in `MainActivity` (`WindowCompat.setDecorFitsSystemWindows(window, false)` +
   `enableEdgeToEdge()`) or `Theme`, and ensure content draws a background behind the IME.
2. Fix `RouteScreen`: use `Modifier.imePadding()` on the right container AND `Modifier.navigationBarsPadding()`/
   `systemBarsPadding()` so the list/content is padded, not just moved; ensure `bringIntoView` on the focused field so
   it isn't hidden.
3. Check `AndroidManifest.xml` for `android:windowSoftInputMode` (prefer `adjustResize` for Compose).
4. Set `android:windowSoftInputMode="adjustResize"` + `android:edgeToEdge` styling if missing.

## Verify
```
.\gradlew.bat assembleDebug --console=plain
```
Manual (device): keyboard opens without a black block, typed text stays visible.

## Done when
No black block above the keyboard; the focused text field stays visible while typing.

Do **not** commit.