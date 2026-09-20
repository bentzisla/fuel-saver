# Remediation Runbook (for the orcheGator)

How to execute `REMEDIATION.md` using many cheap sub-agents. Each task is a self-contained card under
`remediation/tasks/`; a sub-agent reads **one card** and executes it. The orchestrator is the only one who
commits, runs the serial build gate, or touches shared files like the version catalog.

## Before dispatching anything (do yourself, serial)

1. **Phase 0.1 commit** — `git add -A; git commit -m "Import app sources"`.
2. **Phase 0.2 keystore backup** — copy `release/fuelroute.jks` + `keystore.properties` off-machine. Ask the user.
3. **Phase 0.3 move** — move the repo to `C:\dev\fuel` (delete `.gradle/`, `.kotlin/`, `build/`, `app/build/`, `.idea/`
   first), confirm `.\gradlew.bat assembleDebug --console=plain` passes there, and reopen the session in the new
   folder. If the move is skipped, tell the cards the repo root is still under `Documents\Programming\fuel`.

## Agent type

- **`general`** for every card (they write code). 
- **`explore`** only if a sub-agent needs to answer a "where is X / how does Y work" question first; cheaper than burning
  a `general` on discovery. Prefer one `explore` sweep up front over ad-hoc.

## Dispatching rules

- One card per sub-agent. Paste the card's path, not its full text ("read `remediation/tasks/NN-*.md` and do it").
- **Never run two Gradle builds at once.** Sub-agents may edit/analyze in parallel; only one may run
  `.\gradlew.bat ...` at a time. Nominate the *last* sub-agent in each wave (or yourself) to run the build gate for
  that wave.
- Sub-agents **never commit**; they leave changes in the working tree and report a short summary + which
  `Verify` commands passed.

## Waves (dispatch order)

```
P0 (parallel, disjoint):        00a-obd-real-capture   00b-multi-route      (urgent bugs; car/device gate)
Wave 1 (parallel, disjoint):   01-schema-migration    05-route-model-domain
Wave 2 (parallel):             02-vehicle-multivehicle 03-obd-engine-transport 04-learning-domain
                               (02 and 03 both need 01; 04 needs 02 for RefuelViewModel; 03 needs 02 for active vehicle)
Wave 3:                        06-routes-data-api       (needs 05 + 02)
Wave 4:                        07-service-auto-logging  (needs 03)
Wave 5:                        08-product-validation    (needs 01 + 02 + 06)
Wave 7 (parallel, disjoint):   10-schema-v5             12-nav-address-integrity   (Phase 8 product additions)
Wave 8 (parallel):             11-history-predicted-vs-actual  13-favorite-destinations
                               (11 needs 10 + 03; 13 needs 10, and 12 for exact placeId/coords)
Wave 9 (parallel):             14-zero-touch-obd-logging      15-android-auto-dashboard
                               (14 needs 07 + 03; 15 needs 03 + 02 + 07/14)
Wave 10:                       09-tests-docs-hook       (needs everything; also closes Phase 0.4 doc fixes)
Wave 11 (SEQUENTIAL):          16-retention  17-backup-export  18-debug-calibration   (Phase 9: 1.4 / 1.5 / 6.6)
Wave 12 (parallel, disjoint):  19-app-version  23-route-results-ux  24-curve-screen  26-android-auto-debug
Wave 13:                       20-notification-lingering   (urgent bug)
Wave 14:                       21-obd-connection-ux         (needs 20's connect timeout)
Wave 15 (parallel):            22-dashboard-layout  25-combined-rides
Wave 16 (parallel, disjoint):  27-route-graph  28-destination-clear  30-fuel-type-grade  32-curve-basis
Wave 17:                       29-history-saved-label    (needs 25 if it landed)
Wave 18:                       31-settings-reorg         (needs 17/18/20/21 content)
Wave 19:                       33-calibration-ux         (needs 18 + 32)
Wave 20 (parallel, disjoint):  36-curve-interactive  37-versioning  38-route-graph-minimize  39-toll-message  40-map-layout-fit  41-keyboard-block  42-android-auto-car
Wave 21:                       34-obd-disconnect-sticky   (must precede 35)
Wave 22:                       35-obd-connect-reliability (needs 34)
```

**`16/17/18` must run sequentially** — all three touch `ui/settings/SettingsScreen.kt`, `SettingsViewModel.kt`,
`data/settings/SettingsRepository.kt`, and `di/AppModule.kt`, so parallel dispatch would clobber shared files. Order:
16 (retention) → 17 (backup) → 18 (calibration). 16 adds `androidx.work` (the orchestrator already added
`work-runtime-ktx:2.11.2` to the version catalog).

Phase 10 (cards 19-26) is user-reported UX/bug work. Ordering notes:
- **20 (notification) is the urgent bug** — run it first, before 21.
- **20 → 21 sequential**: both touch `ObdEngine` + `ObdLoggingService`. 21's clean retry/reset builds on 20's
  connect timeout.
- **22 (ui/stats) and 25 (ui/history) are disjoint** and can parallelize; neither touches `ObdEngine` after 20/21.
- **Wave 12's four cards touch `res/values/strings.xml`** in different packages — the orchestrator reconciles
  `strings.xml` after the wave (or dispatches with a "minimize string edits" note) before committing.

Phase 11 (cards 27-33) is the second round of UX feedback. Ordering notes:
- **Wave 16's four cards are mostly disjoint** (route graph, destination clear, fuel-grade model, curve basis) but
  `27-route-graph` and `28-destination-clear` both touch `ui/route/*`; if dispatching them in parallel, tell 28 to
  limit itself to the input rows and 27 to the results/graph region, and reconcile after.
- **30 (fuel-grade)** touches `ui/vehicle/*` + `ui/settings/*` + `data/price/*` — keep it out of any wave that also
  touches `ui/settings` (so it must NOT run with 31). It is fine to run in Wave 16 (no other card there touches
  `ui/settings`).
- **31 (settings reorg) must run alone** after everything that added Settings content (17/18/19/20/21); it rewrites
  `SettingsScreen` wholesale.
- **33 (calibration UX) depends on 18+32** and touches `ui/debug/*` + `ui/refuel/*` — run it after 32.

### Git branching / versioning (adopted for Phase 11 onward)
- **Branch per phase:** `feature/phase-NN-...` off `master`; the orchestrator commits each card on the branch and
  merges back to `master` when the phase gate is green. Do not commit directly to `master` during a phase.
- **Version:** bump `versionName`/`versionCode` in `app/build.gradle.kts` at phase end (patch/minor per scope), and the
  Settings footer (card 19) picks it up automatically.

#### Versioning policy (card 37)
`MAJOR.MINOR.PATCH`. `MAJOR` stays `0` for this personal app (reserved for a breaking rewrite); only the orchestrator
moves it. The bump happens at the end of a phase (or a card, when a card ships alone) and is part of that phase's
commit — sub-agents never bump or commit.

- **bugfix → patch:** `0.2.0` → `0.2.1`. A card whose scope is only fixing a bug (e.g. 34, 35).
- **feature / wave of features → minor:** `0.2.1` → `0.3.0`, patch resets to `0`. A card that adds product scope, or a
  whole wave/phase of mixed work (the default for a phase).
- **`versionCode` is monotonic:** always `minor * 100 + patch` (so `0.3.1` → `301`). Android rejects a code that does
  not strictly increase, so never reuse or lower it — recomputing it from `versionName` keeps the two fields in sync.

Bump with the helper (from the repo root, PowerShell 5.1):

```
powershell -ExecutionPolicy Bypass -File .\scripts\bump-version.ps1 -Patch              # bugfix: 0.2.0 -> 0.2.1
powershell -ExecutionPolicy Bypass -File .\scripts\bump-version.ps1 -Minor              # feature/wave: 0.2.1 -> 0.3.0
powershell -ExecutionPolicy Bypass -File .\scripts\bump-version.ps1 -Patch -DryRun      # preview only, no write
```

`scripts/bump-version.ps1` reads `versionName`/`versionCode` from `app/build.gradle.kts`, requires exactly one of
`-Minor`/`-Patch`, rewrites both fields exactly once, and writes the file back as ASCII. It is deterministic (the same
starting version always yields the same result) and safe to re-run: each invocation advances the version by exactly one
step, so run it once per phase/card end. The Settings footer (card 19) reads the values through `BuildConfig` and needs
no further edit.

### Phase 12 ordering note
- **20 is the big parallel wave**; its 7 cards are file-disjoint EXCEPT:
  - `38-route-graph-minimize`, `39-toll-message`, `40-map-layout-fit`, `41-keyboard-block` all touch
    `ui/route/RouteScreen.kt`. Do NOT run those four in parallel with each other. From that group, only `38` is
    range-independent from `41` (38 = results/graph region, 41 = input/ime region) — see the per-card "limit edits to"
    notes. To be safe the orchestrator may split Wave 20 into two sub-waves.
  - `39` also touches `RoutesMapper`/`RoutesError` (data layer) — keep it apart from any `data/routes` edit (none in
    this wave).
- **34 → 35 sequential**: both edit `data/obd/*`; 35 builds on 34's sticky-disconnect + timeout.
- **Gradle parallelism warning (learned the hard way):** running several `gradlew` builds concurrently corrupts the
  shared Kotlin/KSP incremental caches. Sub-agents may EDIT in parallel, but only ONE may run `gradlew` at a time — the
  orchestrator runs the serial gate after each wave; never let N sub-agents build simultaneously.

**`09-tests-docs-hook` moved to last** (was Wave 6): it must also cover the Phase 8 cards' tests, fixtures and docs.

Phase 8 (cards 10-15) is **additive product scope** from the user, not review findings. Two ordering rules matter:
- **Only card 10 may touch `Migrations.kt`/`AppDatabase.kt`** while it is open — v4 is already installed on the user's
  device with real learned data, so v5 must be one non-destructive migration (same rule Phase 1 used for v4).
- **`12-nav-address-integrity` is a user-visible bug and depends on nothing** — it can be dispatched immediately, in
  parallel with anything, ahead of its wave if desired.
- `15-android-auto-dashboard` needs **new dependencies** (`androidx.car.app`); the orchestrator owns
  `gradle/libs.versions.toml`, so coordinate that entry rather than letting the sub-agent invent one.

`00a` (OBD transport/engine) and `00b` (routes DTO/UI) are file-disjoint and can run in parallel. They are the urgent
fixes, so dispatch P0 **first**, before Wave 1/Wave 2. `00a`'s transport/parser changes are the foundation that card 03
later extends; `00b`'s additive DTO/UI changes are absorbed by card 06. Note the project now pins the `general` +
`explore` sub-agent model to `openrouter/deepseek/deepseek-v4.1-flash` — this only takes effect after an opencode
restart.

`05-route-model-domain` is pure Kotlin (no Android) and is deliberately disjoint from everXthing else, so it can
always start in parallel with Wave 1.

## Optional parallelism within Wave 2

`03-obd-engine-transport` and `04-learning-domain` are file-disjoint (domain/obd + data/obd vs domain/learning +
domain/model + ui/curve). They may run concurrently. `02-vehicle-multivehicle` touches `ui/refuel/RefuelViewModel.kt`
and the `"default"` constants inside `data/obd/ObdEngine.kt` — launch `02` **before** `03` and `04`, or explicitly
instruct `03`/`04` to not touch `ObdEngine.kt`'s vehicle id line until `02` lands.

## Build gate (run once per wave, serial)

```
.\gradlew.bat testDebugUnitTest lintDebug --console=plain
```

Fix compile errors by re-dispatching the relevant card with the error text. Do not proceed to the next wave until
green unless the failure is pre-existing and unrelated (record it).

## Contract seams (the risky hand-offs between cards)

- **01 -> 02/03/04/08**: the `vehicle` table and `learning_extras` table, and the `trip` columns
  (`isOpen`, `routeSearchId`, `coldStartFuelL`), exist only after card 01.
- **03 -> 05/06/07**: `ObdTransport.sendCommand` becomes `Result<String>`; `ElmProtocol` gains PID negotiation +
  VIN parsing. Cards 05/06 do not use OBD, so only card 03's own tests verify this.
- **05 -> 06**: `FuelModel.cost(...)` signature changes (adds cold-start and drops `trafficDurationSeconds` in favor of a
  per-segment `CongestionFactor`). Card 06 rewires `RoutesMapper` + `RouteViewModel` to the new `Route`/`FuelModel`.
- **01 -> 06 -> 08**: `RouteSearch` gains `selectedRouteIndex`, `departureTimeMs`, `tollUnknown`.

## Finishing

Run the full build gate once, then commit per card (or per completed wave) with a message referencing the card id and
REMEDIATION item numbers. Do not commit secrets; `local.properties`, `keystore.properties`, `*.jks`, `release/` are
gitignored.