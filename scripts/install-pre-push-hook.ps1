# Installs a pre-push hook that runs the unit tests + lint before every `git push`.
# Run from the repo root:  powershell -ExecutionPolicy Bypass -File .\scripts\install-pre-push-hook.ps1
$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$hooksDir = Join-Path $repoRoot ".git\hooks"

if (-not (Test-Path -LiteralPath $hooksDir)) {
    throw "No .git/hooks directory found under $repoRoot (is this a git repo?)"
}

$hook = @'
#!/bin/sh
# FuelRoute pre-push gate: run JVM unit tests + lint before pushing.
# Install via scripts/install-pre-push-hook.ps1
set -e
echo "[pre-push] Running unit tests + lint..."
./gradlew.bat testSideloadDebugUnitTest testPlayDebugUnitTest lintSideloadDebug lintPlayDebug --console=plain
'@

$hookPath = Join-Path $hooksDir "pre-push"
Set-Content -LiteralPath $hookPath -Value $hook -Encoding ASCII -NoNewline

Write-Host "Installed pre-push hook at $hookPath"
Write-Host "To skip the gate for a single push:  git push --no-verify"