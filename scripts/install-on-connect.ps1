param(
    [switch]$Launch,
    [int]$PollSeconds = 2
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$gradlew  = Join-Path $repoRoot 'gradlew.bat'

function Find-Adb {
    $full = Join-Path (Join-Path $env:LOCALAPPDATA 'Android\Sdk') 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $full) { return $full }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw 'adb not found. Install platform-tools or add adb to PATH.'
}

$adb = Find-Adb

function Get-DeviceStates {
    $states = @()
    $out = & $adb devices 2>$null
    foreach ($raw in $out) {
        $line = $raw.Trim()
        if ($line -match '^([^\s]+)\s+(device|unauthorized|offline)\s*$') {
            $states += [pscustomobject]@{ Serial = $matches[1]; State = $matches[2] }
        }
    }
    return $states
}

function Invoke-Install([string]$serial) {
    Write-Host ("[{0}] {1} connected (device). Building + installing..." -f (Get-Date -Format 'HH:mm:ss'), $serial) -ForegroundColor Cyan
    Push-Location $repoRoot
    try {
        & $gradlew installDebug --console=plain
        if ($LASTEXITCODE -ne 0) {
            Write-Host ("[{0}] Build/install failed (gradle exit {1})." -f (Get-Date -Format 'HH:mm:ss'), $LASTEXITCODE) -ForegroundColor Red
            return
        }
    }
    finally {
        Pop-Location
    }

    if ($Launch) {
        & $adb -s $serial shell am start -n com.fuelroute/.MainActivity 2>$null | Out-Null
    }
    Write-Host ("[{0}] Installed on {1}." -f (Get-Date -Format 'HH:mm:ss'), $serial) -ForegroundColor Green
}

& $adb start-server 2>$null | Out-Null

Write-Host ("[{0}] Watching for a phone. Plug it in when ready... (Ctrl+C to stop)" -f (Get-Date -Format 'HH:mm:ss')) -ForegroundColor Yellow

$hasDevice = $false
while ($true) {
    $devices = @(Get-DeviceStates)
    $ready   = @($devices | Where-Object { $_.State -eq 'device' })
    $unauth  = @($devices | Where-Object { $_.State -eq 'unauthorized' })

    if ($ready.Count -ge 1) {
        if (-not $hasDevice) {
            Invoke-Install $ready[0].Serial
            $hasDevice = $true
        }
    }
    else {
        if ($unauth.Count -ge 1) {
            Write-Host ("[{0}] Phone detected but authorization pending - accept the USB debugging prompt on the phone." -f (Get-Date -Format 'HH:mm:ss')) -ForegroundColor Yellow
        }
        $hasDevice = $false
    }

    Start-Sleep -Seconds $PollSeconds
}