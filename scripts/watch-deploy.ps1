<#
.SYNOPSIS
    Watches APK output and auto-installs it over Wi-Fi ADB every time a new build
    lands (Android Studio, `gradlew assembleDebug`/`assembleRelease`, etc.).

.DESCRIPTION
    Keeps a wireless ADB connection alive and polls the APK output. Whenever an
    APK's timestamp/size changes and then stays stable for one poll, it runs
    `adb install -r -d` and (optionally) launches the app.

    By default it watches the DEBUG APK. Use -BuildType release (or -All to watch
    both). NOTE: debug and release are signed with different keystores, so
    switching between them on the same device requires `adb uninstall com.fuelroute`
    first; the script prints that hint if it hits a signature mismatch.

    The first run walks you through Android 11+ "Wireless debugging" pairing and
    remembers the connection address, so later runs reconnect automatically.

.EXAMPLE
    # Watch the debug APK (default):
    powershell -ExecutionPolicy Bypass -File .\scripts\watch-deploy.ps1

    # Watch the release APK:
    powershell -ExecutionPolicy Bypass -File .\scripts\watch-deploy.ps1 -BuildType release

    # Watch both debug and release outputs:
    powershell -ExecutionPolicy Bypass -File .\scripts\watch-deploy.ps1 -All

    # Auto-launch after each install:
    powershell -ExecutionPolicy Bypass -File .\scripts\watch-deploy.ps1 -Launch

    # Install the current APK once and exit (no watching):
    powershell -ExecutionPolicy Bypass -File .\scripts\watch-deploy.ps1 -Once -BuildType release

    # Non-interactive: supply connection details directly
    powershell -ExecutionPolicy Bypass -File .\scripts\watch-deploy.ps1 `
        -PairHostPort 192.168.1.20:37111 -PairCode 123456 -HostPort 192.168.1.20:42137
#>
[CmdletBinding()]
param(
    [string]$Serial,        # Target a specific adb serial instead of auto-detecting.
    [string]$HostPort,      # Wireless-debugging connect address, e.g. 192.168.1.20:42137
    [string]$PairHostPort,  # Pairing address for `adb pair`, e.g. 192.168.1.20:37111
    [string]$PairCode,      # 6-digit pairing code shown on the phone.
    [string]$Apk,           # Override the watched APK path (takes precedence over -BuildType/-All).
    [ValidateSet('debug', 'release')]
    [string]$BuildType = 'debug',
    [switch]$All,           # Watch BOTH debug and release APKs.
    [switch]$Launch,        # Launch MainActivity after each successful install.
    [switch]$Once,          # Install the current APK once and exit.
    [int]$PollMs = 1500     # Poll interval (ms). Lower = snappier, higher = less overhead.
)

$ErrorActionPreference = 'Stop'

$repoRoot    = Split-Path -Parent $PSScriptRoot
$packageName = 'com.fuelroute'

# Remember the last working connect address so re-runs don't need to re-pair.
$stateDir  = Join-Path $env:LOCALAPPDATA 'FuelRoute'
$stateFile = Join-Path $stateDir 'adb-connect.txt'

function Find-Adb {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' }
    $candidates += Join-Path (Join-Path $env:LOCALAPPDATA 'Android\Sdk') 'platform-tools\adb.exe'
    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath $c)) { return $c }
    }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw 'adb not found. Install platform-tools or add adb to PATH.'
}

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

function Invoke-InteractiveConnect {
    Write-Host ''
    Write-Host '== Wireless debugging setup (Android 11+) ==' -ForegroundColor Yellow
    Write-Host '1. Phone: Settings -> Developer options -> Wireless debugging -> ON'
    Write-Host '2. Tap "Pair device with pairing code" -> note the IP:port + 6-digit code.'
    $pairAddr = Read-Host '   Pairing address (IP:port)  [Enter to skip if already paired]'
    if ($pairAddr) {
        $pairCode = Read-Host '   Pairing code (6 digits)'
        & $adb pair $pairAddr $pairCode
        if ($LASTEXITCODE -ne 0) { throw "adb pair failed (exit $LASTEXITCODE). Check the address and code." }
        Write-Host ("[{0}] Paired." -f (Get-Date -Format 'HH:mm:ss')) -ForegroundColor Green
    }
    Write-Host '3. Back on the Wireless debugging screen, note "IP address & Port" (a DIFFERENT port).'
    $connectAddr = Read-Host '   Connect address (IP:port)'
    if (-not $connectAddr) { throw 'Connect address is required.' }
    & $adb connect $connectAddr 2>$null | Out-Null
    return $connectAddr
}

function Connect-Device {
    if ($Serial) {
        $d = @(Get-DeviceStates | Where-Object { $_.Serial -eq $Serial -and $_.State -eq 'device' })
        if ($d.Count -eq 0) { throw "Device '$Serial' is not connected (check `adb devices`)." }
        return $Serial
    }

    $ready = @(Get-DeviceStates | Where-Object { $_.State -eq 'device' })
    if ($ready.Count -ge 1) {
        Write-Host ("[{0}] Connected to {1}." -f (Get-Date -Format 'HH:mm:ss'), $ready[0].Serial) -ForegroundColor Green
        return $ready[0].Serial
    }

    $remembered = $null
    if (Test-Path -LiteralPath $stateFile) { $remembered = (Get-Content -LiteralPath $stateFile -Raw).Trim() }

    if (-not $HostPort -and $remembered) {
        Write-Host ("[{0}] Reconnecting to {1}..." -f (Get-Date -Format 'HH:mm:ss'), $remembered) -ForegroundColor Yellow
        & $adb connect $remembered 2>$null | Out-Null
        $ready = @(Get-DeviceStates | Where-Object { $_.State -eq 'device' })
        if ($ready.Count -ge 1) { return $ready[0].Serial }
    }

    if ($PairHostPort -and $PairCode) {
        Write-Host ("[{0}] Pairing with {1}..." -f (Get-Date -Format 'HH:mm:ss'), $PairHostPort) -ForegroundColor Yellow
        & $adb pair $PairHostPort $PairCode
        if ($LASTEXITCODE -ne 0) { throw "adb pair failed (exit $LASTEXITCODE)." }
    }

    if ($HostPort) {
        $connectAddr = $HostPort
        & $adb connect $connectAddr 2>$null | Out-Null
    }
    else {
        $connectAddr = Invoke-InteractiveConnect
    }

    Start-Sleep -Milliseconds 500
    $ready = @(Get-DeviceStates | Where-Object { $_.State -eq 'device' })
    if ($ready.Count -ge 1) {
        $s = $ready[0].Serial
        if (-not (Test-Path -LiteralPath $stateDir)) { New-Item -ItemType Directory -Path $stateDir | Out-Null }
        Set-Content -LiteralPath $stateFile -Value $s -Encoding ASCII
        Write-Host ("[{0}] Connected to {1}." -f (Get-Date -Format 'HH:mm:ss'), $s) -ForegroundColor Green
        return $s
    }

    $unauth = @(Get-DeviceStates | Where-Object { $_.State -eq 'unauthorized' })
    if ($unauth.Count -ge 1) {
        throw 'Device shows "unauthorized" - accept the debugging prompt on the phone, then re-run.'
    }
    throw "Could not connect to a device. Try manually: adb connect $connectAddr"
}

function Get-WatchedApks {
    if ($Apk) {
        if (-not (Test-Path -LiteralPath $Apk)) { throw "APK not found: $Apk" }
        return @((Resolve-Path -LiteralPath $Apk).Path)
    }
    if ($All) {
        return @(
            (Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'),
            (Join-Path $repoRoot 'app\build\outputs\apk\release\app-release.apk')
        )
    }
    return @((Join-Path $repoRoot "app\build\outputs\apk\$BuildType\app-$BuildType.apk"))
}

function Install-Apk {
    param([string]$serial, [string]$apk, [switch]$launch)

    Write-Host ("[{0}] Installing {1} on {2}..." -f (Get-Date -Format 'HH:mm:ss'), (Split-Path -Leaf $apk), $serial) -ForegroundColor Cyan
    $out = & $adb -s $serial install -r -d $apk 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host ("[{0}] Installed successfully." -f (Get-Date -Format 'HH:mm:ss')) -ForegroundColor Green
        if ($launch) {
            & $adb -s $serial shell am start -n "$packageName/.MainActivity" 2>$null | Out-Null
        }
        return $true
    }

    Write-Host ("[{0}] Install failed (adb exit {1}):" -f (Get-Date -Format 'HH:mm:ss'), $LASTEXITCODE) -ForegroundColor Red
    Write-Host (($out | Out-String).Trim())

    if (($out | Out-String) -match 'signatures do not match|INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
        Write-Host '  Hint: debug and release APKs use different keystores.' -ForegroundColor Yellow
        Write-Host '  To switch, uninstall the existing app first (this CLEARS app data):' -ForegroundColor Yellow
        Write-Host ("    adb uninstall $packageName") -ForegroundColor Yellow
    }
    return $false
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
$adb = Find-Adb

if ($Once -and $All) {
    throw '-Once and -All are mutually exclusive. Pick one with -BuildType (or -Apk).'
}

$watchedApks = @(Get-WatchedApks)

if ($Once) {
    $target = $watchedApks[0]
    if (-not (Test-Path -LiteralPath $target)) {
        $task = 'assemble' + $BuildType.Substring(0, 1).ToUpperInvariant() + $BuildType.Substring(1)
        throw "APK not found: $target (build it first: gradlew $task)"
    }
    & $adb start-server 2>$null | Out-Null
    $serial = Connect-Device
    Install-Apk -serial $serial -apk $target -launch:$Launch | Out-Null
    return
}

& $adb start-server 2>$null | Out-Null
$serial = Connect-Device

# Baseline each APK so we only react to *new* builds, not files already on disk.
$baseline  = @{}
$candidate = @{}
foreach ($p in $watchedApks) {
    $item = Get-Item -LiteralPath $p -ErrorAction SilentlyContinue
    $baseline[$p]  = if ($item) { '{0}|{1}' -f $item.LastWriteTimeUtc.Ticks, $item.Length } else { $null }
    $candidate[$p] = $null
}

Write-Host ''
Write-Host ("[{0}] Watching for new builds of:" -f (Get-Date -Format 'HH:mm:ss')) -ForegroundColor Yellow
foreach ($p in $watchedApks) { Write-Host ("       {0}" -f (Split-Path -Leaf $p)) }
Write-Host '       Build as normal (Android Studio / gradlew). New APKs install automatically.'
Write-Host '       Press Ctrl+C to stop.'
Write-Host ''

while ($true) {
    foreach ($p in $watchedApks) {
        $item = Get-Item -LiteralPath $p -ErrorAction SilentlyContinue
        $sig = if ($item) { '{0}|{1}' -f $item.LastWriteTimeUtc.Ticks, $item.Length } else { $null }

        if ($sig -and $sig -ne $baseline[$p]) {
            if ($sig -eq $candidate[$p]) {
                # Stable across two consecutive polls -> treat as a finished build.
                if (Install-Apk -serial $serial -apk $p -launch:$Launch) {
                    $baseline[$p] = $sig
                }
                $candidate[$p] = $null
            }
            else {
                $candidate[$p] = $sig
            }
        }
    }

    Start-Sleep -Milliseconds $PollMs
}