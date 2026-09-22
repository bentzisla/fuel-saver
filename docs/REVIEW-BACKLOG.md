# FuelRoute — Review & Improvement Backlog

Consolidated from six read-only audits (domain, OBD, routes/network, UI/UX, architecture/DI/DB/nav, holistic+competitor). Findings are deduplicated and prioritized. This is the input to the next implementation wave(s) — NOT yet scheduled work.

## Blocking / highest value (do first)

1. **Release-build API auth is broken.** `di/NetworkModule.kt` `AndroidAppHeaders` hardcodes the DEBUG signing cert SHA-1 and literal package `"com.fuelroute"`. A release/sideloaded APK presents a cert that does not match the API-key restriction → Routes/Places return 403, and the whole routing feature fails in the intended distribution mode. **Fix:** resolve the cert at runtime via `PackageManager.getPackageInfo(..., GET_SIGNING_CERTIFICATES)` + `BuildConfig.APPLICATION_ID`. *(found in routes, architecture, holistic)*
2. **`ObdEngine.runLoop` `finally` is not cancellation-safe** → Bluetooth socket leak, open trip never closed, up to 30 s of bin stats lost on every stop/disconnect. **Fix:** wrap the `finally` body in `withContext(NonCancellable)`. *(OBD H1)*
3. **`ObdEngine.start()`/`stop()` race** can run two logging loops concurrently and clobber `mutableLive`/bins. **Fix:** run-generation token + `cancelAndJoin`. *(OBD H2)*
4. **Cold-start learning is dead code** (`ColdStartLearner`/`ColdStartRepository` never wired; `coldStartLiters` always 0). **Fix:** record on trip close in `ObdEngine`, pass `effectiveExtraL` into `FuelModel.cost`, or remove the subsystem. *(domain H1, holistic H3)*
5. **`step.staticDuration ?: 0.0` collapses speed to 0 → ~2.4× fuel overestimate** when the API omits it. **Fix:** distance-proportional fallback; guard `t == 0 && distance > 0` and `durationSeconds == 0`. *(domain H2)*
6. **Calibration fit double-counts the active `fuelCorrection`** (`fitCorrection` returns absolute k that overwrites the correction → self-un-calibration). **Fix:** fit against uncorrected liters or apply multiplicatively. *(domain H3)*

## High-value correctness

7. **Ignition-off false positives** on clones/vehicles not answering PID 0C (RPM null → 60 s → stop). **Fix:** only arm `rpmNullSinceMs` when 0C is supported (or speed == 0). *(OBD H3)* — NOTE: the battery-voltage part of this is already fixed in W1.
8. **Init read timeout abandons a blocking read → overlapping commands / protocol desync**; 600 ms `ATZ` timeout too short for real clones. **Fix:** serialize commands with a Mutex / cancellable read; longer `ATZ` timeout. *(OBD H4)*
9. **ELM init requires "ELM327" banner**, rejecting recommended STN/OBDLink adapters. **Fix:** accept any non-error banner. *(OBD M3)*
10. **Route-level `tollInfo` with empty price shadows priced leg tolls.** **Fix:** fall through to leg sum. *(routes MED)*
11. **`ReverseGeocoder` uses legacy Geocoding API not in the restricted key set** (403, silent null). **Fix:** Places (New) or enable+restrict Geocoding. *(routes MED)*
12. **No retry/backoff; default timeouts map slowness to misleading "no network".** **Fix:** retry interceptor + explicit timeouts + distinct `Timeout` error. *(routes MED)*
13. **Places `placePrediction` non-nullable** breaks generic text suggestions (MissingFieldException → empty list). **Fix:** make nullable + mapNotNull. *(routes MED)*

## Persistence / data

14. **Room: zero `@Index`** on tables written at 4 Hz and queried by `timestampMs`/`vehicleId`/`routeSearchId`/`departureTimeMs` → full scans. **Fix:** add indices in a v7 migration. *(architecture MED)*
15. **No Room `MigrationTestHelper` coverage.** **Fix:** add `room-testing` + tests per migration step. *(architecture MED)*
16. **`obd_sample` insert is one-row-per-250 ms** (write amplification). **Fix:** batch `insertAll` in a transaction. *(architecture MED)*
17. **No foreign keys / cascade** — vehicle delete orphans trips/refuels/samples/bins. **Fix:** `onDelete=CASCADE` (with v7) or explicit child deletes. *(architecture MED)*
18. **`TripDao.closeOpenTrips` overwrites `endedAtMs` with "now"**, corrupting crash-recovered trips. **Fix:** preserve checkpointed end time. *(OBD M1)*
19. **Refuel rows never persist `pricePerLiter`/`grade`.** **Fix:** pass `totalPrice/liters` + vehicle grade into `add()`. *(routes MED)*
20. **Backup restore is not idempotent for speed bins** (double-counts on re-import) and not transactional; backs up device-specific OBD state and drops `FuelModelOverrides`. **Fix:** stable export id + skip/replace; `withTransaction`; exclude device keys; include overrides. *(routes MED)*

## UX / UI / accessibility

21. **`runBlocking { saveManualDisconnect }` on main thread** (`StatsViewModel.disconnect/reset`). **Fix:** async persist within coroutine (ordering preserved). *(UI HIGH, architecture MED)*
22. **RTL speed graph axes disagree with the plotted curve.** **Fix:** force `LayoutDirection.Ltr` for charts or draw ticks in-Canvas. *(UI MED)*
23. **Hardcoded chart/status colors fail contrast + dark mode.** **Fix:** theme-derived tokens. *(UI MED)*
24. **Favorite chip trailing icons < 48 dp + nested click targets.** **Fix:** ≥48 dp single-semantic targets. *(UI HIGH)*
25. **Unconfirmed destructive action** (`FavoriteEditDialog` delete; `StatsScreen` "אפס"). **Fix:** confirm dialogs. *(UI HIGH/MED)*
26. **No in-place retry on route errors** (retry "action" is non-clickable text). **Fix:** clickable retry, keep stale results. *(routes MED, holistic M1)*
27. **Inconsistent feedback (Toast vs inline vs none); no SnackbarHost.** **Fix:** centralize a message channel. *(UI MED)*
28. **CurveScreen/VehicleScreen/CalibrationScreen can show infinite spinner or no error on load failure.** **Fix:** explicit error states + retry. *(UI HIGH)*
29. **Destination-aware trip linking not wired** (`autoLink` without destination) → mis-pairs on corridors. **Fix:** pass active destination. *(holistic M2)*
30. **Raw `obd_sample` is write-only** (nothing reads it; 90-day retention buys nothing). **Fix:** rebuild-bins path or drop raw persistence + privacy simplification. *(holistic H4)*

## Privacy / release / build

31. **`allowBackup=true` with empty rules** backs the full DB (VIN, coordinates) to cloud. **Fix:** exclude DB or add privacy toggle + disclosure card. *(architecture MED, holistic M5)*
32. **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `SYSTEM_ALERT_WINDOW`** are Play-restricted; sideload-fine. **Fix:** use settings deep-link for battery exemption. *(architecture MED)*
33. **`isMinifyEnabled = false`** despite prepared ProGuard. **Fix:** enable after validating reflective OBD path. *(architecture MED)*
34. **`BUILD_TIME` from `System.currentTimeMillis()`** breaks build cache/reproducibility. **Fix:** stable source. *(architecture LOW)*
35. **Dependencies ~12 months behind** (OkHttp 4.x, Retrofit 2.x). **Fix:** scheduled bump + dependency verification. *(architecture LOW)*

## Onboarding (holistic top-5)

36. **No first-run onboarding** for the two hard manual prerequisites (Google Cloud key + ELM327 pairing). **Fix:** setup/checklist screen modeled on `AndroidAutoHelpCard`. *(holistic H5)*
37. **Google eco-routing baseline** — surface "Google's eco pick vs FuelRoute's cheapest" (revisit `FUEL_EFFICIENT`). *(holistic PART B)*

## Competitor inspiration (adopt-next)

38. Configurable live dashboard tiles (Torque) — S/M.
39. CSV export of trips (Fuelio/aCar) — M.
40. Cost/km + efficiency trend + "most efficient speed" insight (Drivvo/Simply Auto) — S/M.
41. Refuel/maintenance reminders (Fuelio/Simply Auto) — M.
42. Auto-trip tagging/classification (Simply Auto) — L.
43. Full-tank economy + per-vehicle units (Fuelio) — S/M.

*Priority legend: S = small, M = medium, L = large.*
