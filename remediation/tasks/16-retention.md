# Task 16 — Sample retention (90-day auto-cleanup)

**REMEDIATION item:** 1.4.
**Depends on:** none (schema v5 + all DAOs already exist). Runs first in the Wave 11 group.
**Touches:** `data/settings/SettingsRepository.kt`, `data/db/Daos.kt` (read-only — `deleteOlderThan` already exists),
new `service/RetentionWorker.kt`, `di/AppModule.kt` (WorkManager provider), `ui/settings/SettingsScreen.kt` +
`SettingsViewModel.kt`, `res/values/strings.xml`.

## Objective
Raw OBD samples (`obd_sample`) grow forever. Add a configurable retention window (default 90 days) and a daily
WorkManager job that prunes old rows — also run on service stop so it never waits for the next daily tick.

## Current state (read these first)
- `data/db/Daos.kt` — `ObdSampleDao.deleteOlderThan(cutoffMs: Long): Int` **already exists** (and `ObdEngine` already
  calls it once with a hardcoded `SAMPLE_RETENTION_MS = 90 days`). Do not re-add it.
- `data/settings/SettingsRepository.kt` — `AppSettings`/`DataStoreSettingsRepository` with `saveX(...)` per key. No
  `retentionDays`.
- `di/AppModule.kt` — Hilt module; `FuelRouteApp.kt` is `@HiltAndroidApp`.
- `androidx.work:work-runtime-ktx:2.11.2` is **already added** to `gradle/libs.versions.toml` (alias
  `androidx-work-runtime-ktx`) and `app/build.gradle.kts` (`implementation(libs.androidx.work.runtime.ktx)`). Do not
  touch the version catalog.

## Steps

1. **`AppSettings.retentionDays`** (default 90). Add to `AppSettings`, the DataStore read, and a
   `saveRetentionDays(Int)` (store as `intPreferencesKey` or reuse a long key). Expose the setting in Settings UI as a
   number/slider (e.g. 30/60/90/180/365), Hebrew label + description.
2. **`RetentionWorker`** — a `CoroutineWorker` that obtains `ObdSampleDao` via a Hilt `@EntryPoint`
   (`@EntryPoint @InstallIn(SingletonComponent::class) interface RetentionWorkerEntryPoint { fun obdSampleDao(): ObdSampleDao }`)
   — the same pattern `car/FuelRouteCarAppService` already uses — and calls
   `deleteOlderThan(now - retentionDays*86400000L)`. Do **not** add `androidx.hilt:hilt-work` / `@HiltWorker`; a plain
   worker + entry point keeps the dependency surface minimal.
3. **Schedule daily.** In `AppModule` provide `WorkManager.getInstance(context)`. A small `RetentionScheduler`
   (injectable) enqueues a `PeriodicWorkRequest` (24 h) with `ExistingPeriodicWorkPolicy.KEEP` on app start
   (`FuelRouteApp` or a one-time bootstrap) and re-enqueues when `retentionDays` changes. Keep it idempotent.
4. **Run on service stop.** Call the same prune (via the entry point or a shared `RetentionRepository.prune()`)
   from `ObdLoggingService` when logging stops, so a just-finished drive is already pruned.

## Tests (JUnit 4)
- Extract the retention-window computation into a pure function (`domain/.../RetentionPolicy.kt`, e.g.
  `cutoffMs(now, retentionDays)` + `applyRetentionDays` clamps to a sane min/max) and test it. The worker/scheduler
  itself is thin Android glue — cover the pure part and verify `deleteOlderThan` wiring via a MockK DAO.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
- Setting persists; changing it re-schedules the job.
- `RetentionWorker` is visible in `adb shell dumpsys jobscheduler` (WorkManager registers under jobscheduler) — or
  report not-run if no device.
- `ObdEngine`'s hardcoded `SAMPLE_RETENTION_MS` is folded to read the new setting (keep behavior identical otherwise).

Do **not** commit.
