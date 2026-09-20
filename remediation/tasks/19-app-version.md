# Task 19 — App version display in Settings

**User request:** #8 — show which version/build the user is running.
**Depends on:** none.
**Touches:** `ui/settings/SettingsScreen.kt`, `res/values/strings.xml`, `app/build.gradle.kts` (a BuildConfig field).

## Objective
A footer in Settings that shows the version name + build timestamp, so the user can tell what build is installed.

## Current state
- `app/build.gradle.kts`: `versionName = "0.1.0"`, `versionCode = 1`, `buildFeatures { buildConfig = true }`.
- No version/build-date string anywhere in the UI.

## Steps
1. Add a build-time constant: in `app/build.gradle.kts`
   `buildConfigField("String", "BUILD_TIME", "\"${System.currentTimeMillis()}\"")`.
2. Add a Settings footer `Card` (or a plain text block at the bottom, below the auto-save note) showing:
   `גרסה: <BuildConfig.VERSION_NAME> (<BuildConfig.VERSION_CODE>)` and `נבנה ב־: <formatted BUILD_TIME>`.
   Format the epoch millis to a locale date/time with `DateFormat`.
3. Hebrew strings.

## Verify
```
.\gradlew.bat assembleDebug --console=plain
```
Manual (device, if attached): Settings footer shows version + build date.

## Done when
Settings shows the version and build date.

Do **not** commit.
