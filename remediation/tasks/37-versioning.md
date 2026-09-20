# Task 37 — Versioning: per-feature/fix minor bump (semver)

**User request (round 3):** #3 — versions aren't progressing; each feature/bugfix should bump appropriately.
**Depends on:** none.
**Touches:** `app/build.gradle.kts`, `scripts/` (a bump helper), and MAY update `remediation/RUNBOOK.md`.

## Objective
Adopt an explicit versioning policy and a tiny helper so every merged change advances the version deterministically.

## Policy (decide and encode)
- **MAJOR.minor.patch** where, for this personal app: **minor** = new feature / a batch of meaningful changes (a phase
  or a card group); **patch** = a bugfix. Your note "each feature or bugfix should be a minor bump" is more granular
  than classic semver; we'll follow the user's intent but keep it usable:
  - bugfix → bump **patch** (0.2.0 → 0.2.1),
  - new feature / a wave of features → bump **minor** (0.2.1 → 0.3.0),
  - `versionCode` = monotonically increasing int (e.g. `minor*100 + patch`).
  Record this policy in `remediation/RUNBOOK.md` so every future card bumps at its end.

## Steps
1. Add a `scripts/bump-version.ps1` that reads the current `versionName`/`versionCode` from `app/build.gradle.kts`,
   takes `-Minor` or `-Patch`, and rewrites both fields. Keep it ASCII-safe and idempotent.
2. Document the policy + the script in `remediation/RUNBOOK.md` (the orchestrator runs it at phase/card end).
3. No runtime code change; card 19 already renders the version.

## Verify
```
powershell -ExecutionPolicy Bypass -File .\scripts\bump-version.ps1 -Patch
```
then confirm `app/build.gradle.kts` shows the next patch, and revert the bump in your working tree (don't leave it
committed by this card).

## Done when
`bump-version.ps1` exists and the versioning policy is documented; versioning is tracked per change.

Do **not** commit.