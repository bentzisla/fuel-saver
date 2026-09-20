# Task 17 — Backup / export / import

**REMEDIATION item:** 1.5.
**Depends on:** schema v5 (all tables) + card 13 (favorites). Runs after card 16 in the Wave 11 group.
**Touches:** new `data/backup/BackupRepository.kt` (+ `BackupDtos.kt`), `data/db/Daos.kt` (add one-shot `getAll()`
queries), `ui/settings/SettingsScreen.kt` + `SettingsViewModel.kt`, `di/AppModule.kt`, `AndroidManifest.xml`,
new `res/xml/backup_rules.xml` + `res/xml/data_extraction_rules.xml`, `res/values/strings.xml`.

## Objective
Let the user export their learned data to a JSON file (via `ACTION_CREATE_DOCUMENT`) and import/merge it back
(via `ACTION_OPEN_DOCUMENT`), so the learned curve survives a reinstall or a phone migration.

## Current state (read these first)
- Room has: `vehicle`, `speed_bin_stats`, `trip`, `refuel`, `route_search`, `learning_extras`, `obd_sample`,
  `favorite_destination`. `obd_sample` is **excluded** from backup (raw samples are disposable — retention prunes them).
- `data/db/Daos.kt` has per-table DAOs but only `recent(limit)`/`getForVehicle(vehicleId)`/`get(vehicleId)` style
  reads — no one-shot "dump the whole table" for `trip`, `refuel`, `route_search`, `speed_bin_stats`,
  `learning_extras`. (`vehicle` has `getAll(): Flow`, `favorite_destination` has `observeAll(): Flow`.)
- `data/settings/SettingsRepository.kt` holds `AppSettings`; `FuelPriceRepository` holds price/grade/pin.
- Settings screen (`ui/settings/`) is the natural home for the two buttons.

## Steps

1. **`BackupDtos.kt`** — `@Serializable` snapshots mirroring the Room entities + settings + price (avoid referencing
   Room entities directly; map to/from plain data classes). Version the payload (`schemaVersion = 1`).
2. **`BackupRepository`** — `suspend fun export(): String` (kotlinx JSON) and
   `suspend fun import(json: String): ImportResult` (report counts added/merged/skipped). Cover: `vehicle`,
   `speed_bin_stats`, `trip`, `refuel`, `route_search`, `learning_extras`, `favorite_destination`, settings, price.
   **Merge semantics:** bins summed (per `vehicleId`+`binIndex`), trips/refuels deduped by `vehicleId` + timestamp,
   favorites deduped by `placeId`/label, vehicles upserted by id. Never delete local data that the file lacks.
3. **DAOs** — add one-shot `suspend fun getAll(): List<XEntity>` for `trip`, `refuel`, `route_search`,
   `speed_bin_stats`, `learning_extras`, and use `.first()` for `vehicle`/`favorite_destination` flows. (Export only —
   no new write paths needed beyond what exists.)
4. **UI** — two buttons in Settings ("ייצוא גיבוי" / "ייבוא גיבוי"). Export uses `ActivityResultContracts.CreateDocument`
   (MIME `application/json`, default name `fuelroute-backup-YYYYMMDD.json`) and writes the JSON to the returned Uri.
   Import uses `OpenDocument` and reads the Uri. Show a Hebrew result toast/snackbar with counts; confirm before import.
5. **Auto-backup rules** — `res/xml/backup_rules.xml` + `res/xml/data_extraction_rules.xml` excluding the API key/BuildConfig
   and (optionally) `obd_sample`; reference them from `AndroidManifest.xml`
   (`android:fullBackupContent` / `android:dataExtractionRules`). Keep `local.properties` out (already gitignored).

## Tests (JUnit 4)
- Round-trip: export a populated in-memory set → `import()` → same data (use MockK DAOs).
- Merge: importing twice does not duplicate (dedupe by `vehicleId`+timestamp for trips/refuels; bins sum correctly).
- Corrupt/empty JSON → `ImportResult` error, no partial writes.

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

## Done when
- Export → `adb shell pm clear com.fuelroute` → import → the learned curve/stats are identical (or report the
  not-run device check).
- Backup round-trips and dedupes in unit tests.

Do **not** commit.
