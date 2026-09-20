# FuelRoute - Remediation Plan

Fixes for the gaps found in the 2026-09-19 review of `PLAN.md` vs. the code. Ordered by dependency and by
"how much learned data is at risk per day of delay". Each item has the files it touches and a concrete done-when.
Tick the boxes as items land; keep this file in sync with `PLAN.md` section 8.

## Decisions (locked)

| # | Question | Decision |
|---|---|---|
| 1 | Repo location | **Move to `C:\dev\fuel`**, out of the OneDrive-linked profile tree. |
| 2 | Reset learned data after Phase 2 | **No forced reset.** The car is gasoline, so the hybrid bias (2.3) does not apply, and the dt-clamp bias (2.1) is small relative to total km. The bin remap (2.2) is a lossless migration. Keep the existing "איפוס למידה" button for the user to decide. Export (1.5) before upgrading regardless. |
| 3 | Multi-vehicle | **Now.** A `vehicle` Room table becomes the source of truth in Phase 1; VIN auto-switch follows in Phase 2. |
| 4 | Fuel type of the real car | **Gasoline 95.** Diesel lambda (2.4) and hybrid engine-off (2.3) move to the Deferred list; code paths stay but are not priority. |
| 5 | Test framework | **Keep JUnit 4.** Fix the docs (`AGENTS.md`, `PLAN.md`) that claim JUnit 5. |
| 6 | Sub-agent model | **`openrouter/deepseek/deepseek-v4.1-flash`** for `general` + `explore` agents (project `opencode.json`). Requires an opencode restart to take effect. |

## P0 - Urgent bugs (fix next, before Wave 2)

Two user-reported bugs jumped ahead of the wave plan. Both have a hardware/device acceptance gate, not just unit tests.

- [x] **P0-A — Real OBD dongle connects but no data is read.** Status reaches "Connected" and sampling spins, but the
      dashboard stays blank. Top causes (in order): (1) `BluetoothClassicTransport.readUntilPrompt` reads with
      non-blocking `InputStream.available()` polling + a 2 s deadline, which returns empty on the real SPP socket;
      (2) the ELM protocol never locks (`ATSP0` auto-detect loops `SEARCHING...`/`NO DATA`) and that failure is invisible
      (`SEARCHING...` is not in `PidParser`'s error markers); (3) init replies (`ATZ`/`ATE0`) are ignored, so a failed
      init goes unnoticed. Fix = raw-traffic logging, blocking read + `socket.soTimeout`, init validation, protocol lock,
      and a surfaced `lastError`. See `remediation/tasks/00a-obd-real-capture.md`.
      **Gate: with the dongle on the car, `adb logcat` shows `41 0D XX` and the dashboard shows live speed.**
      -> Code + unit tests landed (blocking read, init validation, raw-traffic logging, parser markers). Car gate pending.
- [x] **P0-B — Only one route is offered.** The result list renders every route (`RouteScreen` uses
      `itemsIndexed(state.results)`), so this means Google returns a single route even with `computeAlternativeRoutes =
      true`. Fix = log the raw `routes.size`, request `requestedReferenceRoutes: ["FUEL_EFFICIENT"]` (+ `departureTime`),
      and show an explicit "נמצא מסלול אחד בלבד" state instead of silently showing one card.
      See `remediation/tasks/00b-multi-route.md`.
      **Gate: a real query returns >= 2 ranked routes, or the app clearly explains why only one exists.**
      -> Code + unit tests landed (`requestedReferenceRoutes` + `departureTime` + explicit one-route message). Device gate pending.

## Dependency graph

```
Phase 0 -> Phase 1 -> { Phase 2 || Phase 3 } -> Phase 4 -> Phase 5 -> Phase 6 -> Phase 7 (interleaved from Phase 1 on)
```

All schema changes go into **one migration, v3 -> v4, in Phase 1**, so the v4 schema is designed up front to
include columns that Phases 2, 3 and 6 populate later.

Estimated effort: ~15-17 working days sequential, ~11-12 with Phases 2 and 3 in parallel.
Phases 0 and 1 (~2.5 days) should not wait.

---

## Phase 0 - Stop the bleeding (0.5 day)

- [x] **0.1 Commit everything.** Verify `.gitignore` excludes `local.properties`, `keystore.properties`, `*.jks`, `release/`.
      `git add -A`; commit "Import app sources".
      Done when: `git status` clean, `git log` shows the app.
      -> Commit `eafe282` (116 files). Verified no secrets in `HEAD`.
- [ ] **0.2 Back up the release keystore off-machine.** `release/fuelroute.jks` + `keystore.properties` -> password manager /
      encrypted archive. Record the SHA-1 next to it.
      Done when: a restore from the backup copy signs an APK.
      **MANUAL (off-machine copy pending).** Values captured for the record:
      - `release/fuelroute.jks` SHA256 = `F4F96B786CEAE5B49B506E1E78F894518EFC7CFAFB96A8F3C28D979A2F11E5FE`
      - `keystore.properties` SHA256 = `5418C3FEBB751CF71DD0D2416A53D10FE528D290E4F1CA3A18918DF71125F76D`
      - release cert SHA1 = `6E:EC:A5:E3:1D:4D:01:D3:39:29:04:2D:D1:1D:AC:CC:6E:59:FA:5C` (alias `fuelroute`)
      - debug cert SHA1 = `E2:AB:20:64:E9:A8:2F:93:10:3D:77:16:5B:C3:F9:07:0D:E2:2E:60` (for API-key restriction)
- [x] **0.3 Move the repo to `C:\dev\fuel`.** Delete `.gradle/`, `.kotlin/`, `build/`, `app/build/`, `.idea/` before moving
      (they hold absolute paths). Reopen OpenCode / Android Studio in the new folder. Exclude the old location from OneDrive
      if it must stay.
      Done when: `.\gradlew.bat assembleDebug --console=plain` succeeds from `C:\dev\fuel`.
      -> Move done by user; caches were carried over but the build is green (`assembleDebug` + `signingReport`).
- [x] **0.4 Fix `AGENTS.md`.** Canonical path `C:\dev\fuel`; `compileSdk`/`targetSdk` = 36 (SDK has `platforms;android-36`,
      `build-tools;36.0.0`); JUnit **4**; mention `SimulatedObdTransport`, `tools/elm327_emulator.py`, `scripts/`,
      `install-on-connect.bat`.
      Done when: every claim in `AGENTS.md` matches `gradle/libs.versions.toml` and `app/build.gradle.kts`.
      -> `AGENTS.md` env note updated to `C:\dev\fuel`; SDK/JUnit4/transport notes already present (verified against files).

---

## Phase 1 - Data durability and multi-vehicle foundation (2.5 days) - *before the next real drive*

- [x] **1.1 Real migrations.**
      `AppDatabase.kt`: `exportSchema = true`, `version = 4`. `DatabaseModule.kt`: remove `fallbackToDestructiveMigration()`,
      add `MIGRATION_3_4`. Commit `app/schemas/` (the `room.schemaLocation` KSP arg already points there).
      v4 schema, all at once:
      - new table `vehicle(id TEXT PK, name, fuelType, ratedCombinedL100, engineDisplacementL, tankCapacityL,
        fuelRateCorrection, manualCurve, vin TEXT NULL, createdAtMs)`;
      - `trip`: `endedAtMs` nullable, add `isOpen INTEGER NOT NULL DEFAULT 0`, `routeSearchId INTEGER NULL` (6.2),
        `coldStartFuelL REAL NOT NULL DEFAULT 0` (2.9);
      - `speed_bin_stats`: `UPDATE ... SET binIndex = binIndex + 1 WHERE binIndex > 0` (2.2);
      - new table `learning_extras(vehicleId TEXT PK, coldStartExtraL, coldStartCount, updatedAtMs)`;
      - `route_search`: add `selectedRouteIndex INTEGER`, `departureTimeMs INTEGER NULL`, `tollUnknown INTEGER NOT NULL DEFAULT 0`;
      - `refuel`: add `pricePerLiter REAL`, `grade TEXT NOT NULL DEFAULT '95'`.
      Done when: install a v3 build, log a simulator drive, install v4 -> data intact; `MigrationTestHelper` test passes on device.
      -> v4 schema + `MIGRATION_3_4` + `app/schemas/` committed; build + unit tests green. Device migration test (androidTest) is 7.2.
- [x] **1.2 `vehicle` table + active vehicle.**
      `data/vehicle/VehicleRepository` backed by Room (`VehicleDao`), `activeVehicleId` in DataStore.
      One-time bootstrap on startup (`AppStartup`/Hilt initializer): if `vehicle` is empty, insert a row from the old
      DataStore profile (minting a UUID if `vehicle_id` was blank), set it active, and repoint orphaned rows:
      `UPDATE speed_bin_stats/trip/refuel SET vehicleId = :id WHERE vehicleId = 'default'`.
      Delete the three `"default"` constants (`ObdEngine.kt`, `RouteViewModel.kt`, `RefuelViewModel.kt`); every consumer
      reads `activeVehicleId`. `VehicleScreen` gets a vehicle list (add / select / delete with confirmation).
      Done when: fresh install -> demo drive -> save profile -> curve still shows the km; two vehicles keep separate curves.
- [x] **1.3 Checkpointed persistence.**
      `ObdEngine.runLoop`: upsert bins every 30 s, not only in `finally`. Insert the `trip` row with `isOpen = 1` on
      `TripTransition.Started`, update totals at each checkpoint, close on `Ended`. On engine start, close orphaned open trips.
      Fold the duplicated `tripDao.insert` blocks into a `TripRecorder`.
      Done when: `adb shell am kill com.fuelroute` mid-drive loses <= 30 s of learning; MockK-DAO unit test with virtual time
      verifies the cadence.
- [ ] **1.4 Retention.**
      `retentionDays` (default 90) in `AppSettings` + Settings UI. Add `androidx.work` + Hilt worker `RetentionWorker`
      (daily) calling `ObdSampleDao.deleteOlderThan`; also run on service stop.
      Done when: job visible in `adb shell dumpsys jobscheduler`; rows older than the cutoff are gone.
- [ ] **1.5 Export / import.**
      `data/backup/BackupRepository`: kotlinx-JSON of `vehicle` + `speed_bin_stats` + `trip` + `refuel` + `route_search` +
      `learning_extras` + settings. Export via `ACTION_CREATE_DOCUMENT`, import via `ACTION_OPEN_DOCUMENT` with merge semantics
      (bins summed, trips/refuels deduped by `vehicleId + timestamp`). Buttons in Settings.
      Add `backup_rules.xml` / `data_extraction_rules.xml` covering the DB and DataStore files.
      Done when: export -> `adb shell pm clear com.fuelroute` -> import -> curve identical.

---

## Phase 2 - Learning correctness (2.5 days) - *parallel with Phase 3*

- [x] **2.1 Sample gaps are dropped, not clamped.** Pass raw `dt` from `ObdEngine`; `SpeedBinAggregator` rejects `dt > 2 s`
      for bins **and** trip totals. Remove `.coerceIn(0.0, 2.0)`.
      Test: a 40 s gap adds 0 km and 0 L.
- [x] **2.2 Idle vs creeping.** Bin 0 = `speed < 1` only; moving bins `floor(v/5) + 1`, center `(i-1)*5 + 2.5`.
      Update `speedToBinIndex`, `binIndexToSpeedKmh`, `LearnedCurve` (skip bin 0 only), `CurveScreen` labels.
      Test: a 4 km/h sample lands in bin 1; existing tests updated.
- [x] **2.5 Supported-PID negotiation.** After init send `0100`/`0120`/`0140`/`0160`, build the set, poll only supported PIDs.
      Expose `supportedPids` and measured `sampleRateHz` in `LiveObdState`; show both on Stats.
      Test: simulator without `5E` -> no `015E` sent; Hz displayed.
- [x] **2.6 ELM init hardening.** Validate `ATZ` reply contains `ELM327`; add `ATAT1`, `ATST32`, `ATDPN`; init failure ->
      `ObdStatus.Error(reason)`. `ATRV` every 10 s -> `batteryVoltage`.
      Test: a fake answering `?` to `ATZ` yields Error, not Connected.
- [x] **2.7 Reconnect / backoff.** `ObdTransport.sendCommand` returns `Result<String>` instead of `""`. `ObdEngine`: >= 5
      consecutive failures -> disconnect -> backoff 2, 4, 8 ... 60 s for up to 3 min (trip-end window) -> give up.
      `BluetoothClassicTransport.connect`: secure -> `createInsecureRfcommSocketToServiceRecord` -> reflection
      `createRfcommSocket(1)`.
      Test: transport that dies after N calls -> status cycles Connected -> Connecting -> Connected, one trip recorded.
- [x] **2.8 Ignition-off detection.** `rpm == null` / `NO DATA` for 60 s, or voltage < 11.5 V -> force trip end, disconnect,
      stop the service (do not spin forever on a powered dongle).
      Test: simulator "engine off" script ends the session.
- [x] **2.9 Cold-start learning.** `domain/learning/ColdStartLearner.kt`: for samples with coolant < 60 C accumulate
      `fuel - warmCurve(v) * d` into `learning_extras.coldStartExtraL` (running mean per trip start). Default 0.15 L until learned.
      Test: synthetic cold phase -> expected extra liters.
- [x] **2.10 VIN (mode `09 02`) with multi-frame assembly** (CAN `0:`/`1:` line-prefixed and legacy 3-line formats).
      Read once after init; store `vehicle.vin`. If the VIN matches a known vehicle, switch `activeVehicleId` automatically;
      if unknown, prompt "רכב חדש? / קשר לרכב קיים".
      Test: two recorded VIN responses parse to the correct 17 chars.
- [x] **2.11 `RefuelCalibrator` in domain.** Pure, tested. Use only OBD fuel logged **between the two most recent full refuels**,
      not lifetime totals. Emit `Clamped` when hitting `[0.7, 1.4]`; show it in `RefuelScreen`.
      Tests: exact, clamped, insufficient data.

Deferred (car is gasoline; keep the code paths, low priority):
- **2.3 Hybrid engine-off**: when `speed > 0 && !engineRunning` accumulate distance with `fuelL = 0` instead of dropping.
- **2.4 Diesel lambda**: PID `0x44`; MAF path for `DIESEL` uses `AFR = 14.5 * lambda`, returns `null` without it (forces `5E`).

---

## Phase 3 - Route model correctness (2-3 days) - *parallel with Phase 2*

- [x] **3.1 Congestion into domain.** New `domain/fuel/CongestionModel.kt` (pure). `RoutesMapper` only emits
      `List<CongestionInterval(startM, endM, level)>` per leg **and** per route; no modeling in the data layer.
- [x] **3.2 Length-weighted factor per step.** `speedFactor = sum(len_i * f_i) / sum(len_i)`; the dominant level is kept for
      display color only. Replaces `dominantCongestion`.
- [x] **3.3 Route-level fallback + resolution flag.** If leg intervals are empty, use `route.travelAdvisory.speedReadingIntervals`
      mapped on the route polyline. Add `trafficResolution: PER_SEGMENT | ROUTE_AVERAGE | NONE` to `Route`; result card shows
      "נתוני תנועה: לפי מקטע / ממוצע / אין".
- [x] **3.4 Time normalization (replaces the double count).**
      ```
      t_raw_i = static_i / speedFactor_i
      scale   = route.duration / sum(t_raw_i)
      t_i     = t_raw_i * scale
      v_eff_i = d_i / t_i
      stopGo  = idleLph * max(0, t_i - static_i) * w
      ```
      Delete the `trafficScale`-based `trafficDurationSeconds` and `fallbackForTraffic` from the mapper; `NONE` is simply all
      factors = 1, so normalization alone spreads the delay.
      Tests: `sum(t_i) == route.duration` for any input; JAM > NORMAL for equal distance; `NONE` equals uniform scaling;
      fixture route yields 5-10 L/100.
      -> Domain normalization landed (card 05); mapper `trafficScale`/`fallbackForTraffic` cleanup remains with card 06.
- [x] **3.5 Tolls.** `tollCost: Money?` with currency; `tollUnknown = (estimatedPrice == null)`. UI badge "אגרה לא ידועה";
      unknown-toll routes are never auto-highlighted as cheapest. Add `routeModifiers.vehicleInfo.emissionType` from `fuelType`.
      Check the `TollPass` enum for Israeli passes; add a setting only if one exists.
- [x] **3.6 Cold-start term in prediction.** `FuelModel.cost(..., coldStart: Boolean)` adds `coldStartExtraL` once per route.
      Auto-detect: cold if no OBD trip ended in the last 2 h; manual toggle in results.
      -> `FuelModel.cost(..., coldStartLiters)` signature landed; auto-detect + manual toggle are card 06/08.
- [x] **3.7 Remove `FuelType.ELECTRIC`** from the enum and UI (stored value already falls back to GASOLINE). Re-add only with a
      kWh model.
- [x] **3.8 Fail loudly on parse.** `parseDurationSeconds` throws `RoutesParseException` on malformed input;
      `distanceMeters: Int`. Surfaced through 4.5.
- [x] **3.9 `domain/fuel/ModelConstants.kt`.** Every magic number (`SLOW 0.55`, `JAM 0.25`, `STOP_GO_WEIGHT 0.5`,
      `VOLUMETRIC_EFFICIENCY 0.85`, `CONFIDENCE_K_KM 20`, `MAX_EXTRAPOLATION_KMH 12.5`, `COLD_START_DEFAULT_L 0.15`) with a
      provenance comment and `TODO(calibrate)`. Debug-only DataStore overrides for 6.6.
      -> Constants + provenance landed; debug DataStore overrides remain with 6.6.

---

## Phase 4 - Routes API request, caching, errors (1-2 days)

- [x] **4.1 `departureTime`** (ISO-8601 UTC) in `ComputeRoutesRequest`; date/time picker in `RouteScreen`, default "עכשיו";
      clamp past -> now. Stored in `route_search.departureTimeMs`.
      Done when: same route at 07:00 vs 22:00 returns different `duration`.
- [x] **4.2 `routeModifiers`** (emissionType, tollPasses if applicable, optional `avoidTolls`).
      Done when: MockWebServer test sees them in the request body.
- [x] **4.3 `requestedReferenceRoutes: ["FUEL_EFFICIENT"]`** behind a debug setting; if returned, label it and log the
      comparison with our pick.
- [x] **4.4 `CachingRoutesRepository` decorator.** In-memory LRU keyed on (origin key, destination key, departure rounded to
      10 min), TTL 10 min; "רענן" bypass button.
      Test: second call within TTL does not hit the service.
- [x] **4.5 Error taxonomy.** `sealed class RoutesError { NoNetwork, Quota, Forbidden, NoRoute, SingleRouteOnly, Invalid(msg),
      Parse, Unknown }` mapped from `HttpException` / `IOException`; Hebrew strings with a suggested action; single-route case
      shows "נמצא מסלול אחד בלבד - אין חלופות להשוואה". Remove the raw `e.message` from `RouteViewModel`.
- [x] **4.6 Make the key restriction apply to REST.** Send `X-Android-Package: com.fuelroute` and `X-Android-Cert: <SHA-1>`
      on Routes and Places calls (Maps Platform honors Android-restricted keys for web services when these headers are present).
      Set a hard daily quota cap on the Routes SKU in Cloud Console. Document in `PLAN.md` sections 2 and 9.
      Done when: 403 without the headers, 200 with them, on the restricted key.

---

## Phase 5 - Automatic logging and service robustness (2 days)

- [x] **5.1 `BluetoothAclReceiver`** for `ACTION_ACL_CONNECTED`, filtered to `lastDeviceAddress`, guarded by `autoConnect`
      -> `ObdLoggingService.start`. (Bluetooth broadcasts that require `BLUETOOTH_CONNECT` are an allowed background-FGS
      trigger; `connectedDevice` is the right type on API 34+.)
      Done when: phone locked, dongle powers up -> notification appears without opening the app.
- [x] **5.2 `PARTIAL_WAKE_LOCK`** (`FuelRoute:obd`) held while Connected, 4 h timeout renewed at each checkpoint, released on stop.
      Done when: 20 min screen-off -> sample rate unchanged.
- [x] **5.3 Battery-optimization exemption prompt** (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) with rationale, from Stats.
      Done when: `adb shell dumpsys deviceidle whitelist` lists the package.
- [x] **5.4 `PermissionGate` composable.** `BLUETOOTH_CONNECT`/`SCAN`, `POST_NOTIFICATIONS`, location (route tab only).
      Denied -> rationale + settings deep-link; permanently denied -> disabled features explained, no crash.
      Done when: denying each permission leaves the app usable.
- [x] **5.5 Service lifecycle.** `START_REDELIVER_INTENT`; notification tap deep-links to the live dashboard; stale indicator
      when the last sample is > 10 s old.
      Done when: killing the process restarts the service and reconnects.
- [x] **5.6 Dashboard while driving.** Keep-screen-on toggle, large-text layout, zero required interaction. One-line note in
      Settings: a permanently plugged-in ELM327 drains the car battery.

---

## Phase 6 - Unreachable features, validation, product defaults (3 days)

- [x] **6.1 `ui/history/HistoryScreen`** reading `route_search`: list + "חסכת X ₪ סה"כ". Reachable from the Route tab.
- [x] **6.2 Predicted vs actual.** On trip start, link to a `route_search` from the last 30 min (auto) or via a
      "יצאתי במסלול הזה" button that arms the link. Show per-trip error and rolling MAPE on Stats - this is the app's
      accuracy metric.
      Done when: after one linked drive Stats shows "דיוק חיזוי: ±N%".
- [x] **6.3 `tankCapacityL`**: refuel sanity check ("ליטרים > נפח מיכל?") and remaining range from PID `2F`.
- [x] **6.4 `data/price/FuelPriceRepository`.** Price + `grade` (95/98/diesel) + `manuallyPinned`; a full refuel updates the
      price only when not pinned. Verify a data.gov.il dataset for the regulated 95 price exists before adding a monthly
      WorkManager fetch.
- [x] **6.5 Ranking defaults.** `valuePerMinute` default 0.5 ₪/min; results always show both "הזול ביותר" and
      "המהיר ביותר" badges plus the delta in ₪ and minutes.
- [ ] **6.6 Debug calibration screen.** Edit `ModelConstants` overrides at runtime; "fit stop-go weight / congestion factors
      against linked trips" (least squares over 6.2 data).
- [x] **6.7 Multi-vehicle polish.** Vehicle switcher in the top bar; per-vehicle stats; VIN auto-switch from 2.10 surfaced
      as a toast ("זוהה: <name>").

---

## Phase 7 - Tests, fixtures, documentation (2 days, interleaved from Phase 1 on)

- [x] **7.1 Fixtures.** `app/src/test/resources/fixtures/routes/`: real TA->JLM response with 3 alternatives; one with
      `tollInfo`; one single-route; one with route-level-only `speedReadingIntervals`.
      `fixtures/obd/`: recorded ELM sessions (init, VIN multi-frame, `SEARCHING...`, dropout).
      `FakeObdTransport` becomes a **file replay** (`command -> response, delayMs`) as `PLAN.md` 5.6 describes;
      `SimulatedObdTransport` stays for demos.
      -> Done: 4 route JSON fixtures + `fixtures/obd/ta-jerusalem-session.txt`; `FakeObdTransport` sequential-replay mode + `parseScript`; `testutil/Fixtures.kt`.
- [x] **7.2 New tests.** `TripDetector`, `RouteRanker`, `CongestionModel`, normalization invariant, `RoutesCache`, error
      mapping, `RefuelCalibrator`, VIN assembly, aggregator (dt / bin remap), `ColdStartLearner`, `VehicleRepository`
      bootstrap, migration 3->4 (androidTest, physical device).
      -> Done on JVM: `TripDetectorTest`, `RouteRankerTest`, `RouteInsightsTest`, `CongestionModelTest`, `RoutesErrorTest`, `CachingRoutesRepositoryTest`, `RefuelCalibratorTest`, `PidParserVinTest`, `SpeedBinAggregatorTest`/`SpeedBinStatsTest`, `ColdStartLearnerTest`, `VehicleRepositoryTest`, `RoutesFixtureTest`, `FakeObdTransportReplayTest` (218 tests total). `migration 3->4` androidTest remains device-only (skipped, no emulator).
- [x] **7.3 Gate.** Git pre-push hook running `.\gradlew.bat testDebugUnitTest lintDebug --console=plain`; commit a lint baseline.
      -> Hook installed at `.git/hooks/pre-push` via `scripts/install-pre-push-hook.ps1`; lint clean.
- [x] **7.4 Docs.** Rewrite `PLAN.md` section 1 table (JUnit 4, no Vico), section 6 tree (actual files), section 8 milestones
      -> status checklist; add **section 10 "מגבלות המודל וקבועים"** (every constant, source, calibration status); extend
      section 9 with: keystore loss, REST key restriction, FGS start rules, OneDrive, destructive migration.
      Update `AGENTS.md` (path, SDK, backup/export commands, `SimulatedObdTransport`, hook).
      -> Done: PLAN.md §1 (JUnit4/no-Vico/no-secrets-plugin/SDK36), §6 tree rewritten to actual files, §8 status note, §9 risks extended, §10 added; AGENTS.md (car/ layer, pre-push hook, Android Auto/DHU note).

---

## Phase 8 - Product additions (user requests, 2026-09-20)

Five features requested after the P0/Wave-1 fixes. They are **additive product scope**, not review findings, and each
has its own task card. All schema changes go into **one migration, v4 -> v5, in 8.0** (same rule as Phase 1) because
v4 is already installed on the user's device with real learned data.

- [x] **8.0 Schema v4 -> v5.** `route_search`: `selectedPredictedCost/Liters/Minutes`, `pricePerLiterAtSearch`,
      `destinationPlaceId/Lat/Lng`. `trip`: `actualCost`, `pricePerLiterAtTrip`, `linkedAtMs`. New table
      `favorite_destination`. New DAOs + `MIGRATION_4_5`; no destructive fallback.
      See `remediation/tasks/10-schema-v5.md`.
      Done when: `app/schemas/...AppDatabase/5.json` emitted; build + tests green; existing device data survives.
      -> Done: `5.json` committed; non-destructive migration; build + tests green.
- [x] **8.1 Route history: predicted vs. actual price.** *(user request 1; completes 6.1 + 6.2)* Persist the route the
      user actually chose, auto-link a closed OBD trip to its `route_search` (30 min window, manual override), record
      `actualCost = fuelL * pricePerLiter` at trip close, and show predicted ₪ vs. actual ₪ + delta + rolling
      "דיוק חיזוי: ±N%" in `HistoryScreen`. Pure `TripMatcher` + `PredictionAccuracy`.
      See `remediation/tasks/11-history-predicted-vs-actual.md`.
      Done when: one search + one logged drive shows both prices and the delta.
      -> Done (auto-link + actualCost + HistoryScreen). Manual "יצאתי במסלול הזה" button not yet wired to RouteScreen.
- [x] **8.2 Destination corruption on nav hand-off.** *(user request 2 - bug)* "יצחק שדה, הרצליה" arrives in the nav app
      as "יצחק שדה, תל אביב". Two causes: `onDestinationSelect` keeps only `mainText` (drops the city), and
      `NavigationLauncher` hands off **free text** that Maps/Waze re-geocode. Fix = keep the full label, resolve the
      place's coordinates, send `destination_place_id` (Maps) and `ll=` (Waze).
      See `remediation/tasks/12-nav-address-integrity.md`.
      **Gate: picking a Herzliya address navigates to Herzliya in both Google Maps and Waze.**
      -> Code + pure `NavigationUris` tests landed (full label + place_id/ll=). Device gate not re-run.
- [x] **8.3 Favorite destinations.** *(user request 3)* Room-backed `favorite_destination` (durable, not the 10-entry
      recents cache), star toggle on the route screen, one-tap chips, rename ("בית") + delete/reorder, included in the
      1.5 backup.
      See `remediation/tasks/13-favorite-destinations.md`.
      -> Done (Room-backed, star toggle, chips, rename/delete/reorder, empty state, backup TODO(1.5)).
- [x] **8.4 Zero-touch OBD logging.** *(user request 4; completes Phase 5, extends card 07)* Auto-connect before any
      device was picked (bonded ELM name match), re-arm after reboot and after Bluetooth toggles, stop cleanly on
      ACL disconnect / ignition off, `autoConnect` on by default, and surface *why* nothing was recorded.
      See `remediation/tasks/14-zero-touch-obd-logging.md`.
      **Gate: phone locked and app never opened -> powering the dongle records a trip; engine off -> service stops.**
      -> Code + unit tests landed (ObdDeviceMatcher, BootReceiver, debounce). Car acceptance gate not run.
- [x] **8.5 Android Auto live dashboard.** *(user request 5)* Car App Library `CarAppService` + `PaneTemplate` showing
      live consumption, **₪ per hour**, and trip cost, fed by the existing engine (the car screen never owns the
      connection). Pure `LiveCostCalculator`.
      Constraints, locked: templates only (no Compose), host-throttled refresh (~1 Hz, not 4 Hz), and **Play Store will
      not approve a generic vehicle-dashboard category** -> personal sideload with Android Auto "Unknown sources".
      Tested with the Desktop Head Unit (no emulator needed).
      See `remediation/tasks/15-android-auto-dashboard.md`.
      -> Done (CarAppService + PaneTemplate + LiveCostCalculator). DHU check not run (no car/emulator).

---

## Phase 9 - Data lifecycle & calibration (closes 1.4, 1.5, 6.6)

Three original-plan items that had no task card. Added 2026-09-20, run as one final wave (sequenced — all three touch
`ui/settings` + `di/`).

- [ ] **9.1 Retention (1.4).** `retentionDays` setting (default 90) + `androidx.work` daily `RetentionWorker`
      (`ObdSampleDao.deleteOlderThan`), also run on service stop. See `remediation/tasks/16-retention.md`.
- [ ] **9.2 Export/import (1.5).** `BackupRepository` (kotlinx JSON of all tables except `obd_sample` + settings +
      price), via `ACTION_CREATE_DOCUMENT`/`ACTION_OPEN_DOCUMENT`, merge semantics, `backup_rules.xml`. Includes
      `favorite_destination`. See `remediation/tasks/17-backup-export.md`.
- [ ] **9.3 Debug calibration (6.6).** Runtime `ModelConstants` overrides (DataStore) + `ui/debug/CalibrationScreen`
      + a global fuel-correction fit over linked trips (`CalibrationFitter`). Segment-level fit deferred (needs stored
      per-route geometry). See `remediation/tasks/18-debug-calibration.md`.

---

## Manual steps outside the codebase

- Cloud Console: second API key for Routes/Places REST with Android restriction + `X-Android-*` headers, hard daily quota cap,
  budget alert (see 4.6).
- Keystore backup location recorded in the password manager (see 0.2).
- OneDrive exclusion for the old path if it is not deleted (see 0.3).
