# FuelRoute — Implementation Plan (v0.4/0.5 wave)

Orchestrator-authored plan covering 5 bug fixes and 6 feature requests. Each work item is a git
branch, a subagent, a version bump, and an in-app changelog entry.

## Work items → branches

| # | Branch | Scope | Type | Files touched |
|---|--------|-------|------|---------------|
| W1 | `feature/obd-reconnect-fix` | 30s disconnect + auto-reconnect | bug #1,#2 | `service/`, `data/obd/`, `domain/obd/` |
| W2 | `feature/nav-handoff` | Google Maps chosen path + Waze honesty | bug #3,#4 | `nav/`, `ui/route/`, tests |
| W3 | `feature/android-auto` | Android Auto bring-up | bug #5 | `car/`, `AndroidManifest.xml`, `automotive_app_desc.xml`, Settings AA help |
| W4 | `feature/history-enhancements` | manual cost entry + bulk delete + merge/split | feat #1,#2,#3 | `ui/history/`, `data/history/`, `data/db/`, tests |
| W5 | `feature/obd-favorites` | latest device on top + favorites | feat #4,#5 | `data/obd/`, `data/db/`, `ui/stats/`, settings |
| W6 | `feature/manual-curve` | manual vehicle-curve values | feat #6 | `ui/curve/`, `ui/vehicle/`, `data/vehicle/`, tests |

## Waves

- **Baseline (orchestrator):** in-app changelog infrastructure (`Changelog.kt` + About section).
- **Wave 1 (parallel):** W1, W2, W3, W6 — disjoint file sets, run concurrently via git worktrees.
- **Wave 2 (sequential):** W5 — adds `favorite_obd_device` table (Room migration 6→7).
- **Wave 3 (sequential):** W4 — adds manual-cost override columns (Room migration 7→8).
- **Final:** full `testDebugUnitTest` + `lintDebug` + `assembleDebug` + `installDebug`.

W4 and W5 both touch the DB schema, so they are intentionally serialised to keep the Room
`exportSchema` migrations linear (no destructive migration).

## Version / changelog policy

Every merged work item bumps `versionCode` (+1) and `versionName` (minor), and appends a Hebrew
entry to `Changelog`. The orchestrator performs the bump at merge time (not the subagents) so
parallel branches never edit `build.gradle.kts` or the changelog.

Planned sequence: 0.4.0 (obd) → 0.4.1 (nav) → 0.4.2 (car) → 0.4.3 (curve) → 0.4.4 (favorites)
→ 0.5.0 (history).

## Definition of done (every branch)

1. Unit tests added under `app/src/test/` (JUnit4/MockK), fixtures where applicable.
2. `.\gradlew.bat testDebugUnitTest` green in the worktree.
3. `.\gradlew.bat lintDebug` green.
4. `domain/` stays free of Android imports; Room changes ship as non-destructive migrations.
5. UI strings in `res/values/strings.xml` (Hebrew primary).
