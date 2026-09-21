<#
.SYNOPSIS
    Bumps versionName / versionCode in app/build.gradle.kts.

.DESCRIPTION
    Implements the FuelRoute versioning policy (see README.md):
      * bugfix              -> -Patch : 0.2.0 -> 0.2.1
      * feature / wave      -> -Minor : 0.2.1 -> 0.3.0 (patch resets to 0)
    versionCode is always recomputed from versionName as (minor * 100 + patch),
    so the two fields can never drift apart. The rewrite is exact-once and
    written back as ASCII, keeping the repo path ASCII-safe and the diff minimal.

.PARAMETER Minor
    New feature or a batch of changes (a wave/phase). Resets patch to 0.

.PARAMETER Patch
    Bugfix only. Increments patch.

.PARAMETER DryRun
    Print the new version without writing app/build.gradle.kts.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\bump-version.ps1 -Patch
    powershell -ExecutionPolicy Bypass -File .\scripts\bump-version.ps1 -Minor -DryRun
#>
[CmdletBinding()]
param(
    [switch]$Minor,
    [switch]$Patch,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

if ($Minor -eq $Patch) {
    throw 'Specify exactly one of -Minor or -Patch. Usage: .\scripts\bump-version.ps1 -Patch'
}

$repoRoot   = Split-Path -Parent $PSScriptRoot
$gradleFile = Join-Path $repoRoot 'app\build.gradle.kts'

if (-not (Test-Path -LiteralPath $gradleFile)) {
    throw "Could not find $gradleFile (keep scripts/ next to app/)."
}

$text = [System.IO.File]::ReadAllText($gradleFile)

$nameRegex = [regex]'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"'
$nameMatch = $nameRegex.Match($text)
if (-not $nameMatch.Success) {
    throw 'Could not find a semver versionName = "X.Y.Z" in app/build.gradle.kts.'
}

# NOTE: PowerShell variable names are case-insensitive, so these must NOT be named
# $minor/$patch — that would collide with the -Minor/-Patch switch parameters.
$majorNum = [int]$nameMatch.Groups[1].Value
$minorNum = [int]$nameMatch.Groups[2].Value
$patchNum = [int]$nameMatch.Groups[3].Value

if ($Minor) {
    $minorNum++
    $patchNum = 0
}
else {
    $patchNum++
}

$newName = '{0}.{1}.{2}' -f $majorNum, $minorNum, $patchNum
$newCode = $minorNum * 100 + $patchNum

# Replace each field exactly once; Regex.Replace(..., count) keeps it minimal and deterministic.
$newText = ([regex]'versionName\s*=\s*"\d+\.\d+\.\d+"').Replace($text, ('versionName = "{0}"' -f $newName), 1)
$newText = ([regex]'versionCode\s*=\s*\d+').Replace($newText, ('versionCode = {0}' -f $newCode), 1)

if ($DryRun) {
    Write-Host ("[dry-run] versionName -> {0}, versionCode -> {1}" -f $newName, $newCode)
    return
}

$ascii = New-Object System.Text.ASCIIEncoding
[System.IO.File]::WriteAllText($gradleFile, $newText, $ascii)

Write-Host ("Bumped version: {0} (versionCode {1})" -f $newName, $newCode)
