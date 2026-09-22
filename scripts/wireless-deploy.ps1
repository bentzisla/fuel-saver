# wireless-deploy.ps1
# Builds, installs (over Wi-Fi ADB) and launches the FuelRoute debug APK on the phone.
#
# The phone is paired over wireless debugging and may appear under several adb transport
# entries (re-pairing appends " (2)" etc.). `installDebug` installs to every online device;
# the launch step targets the first online device by serial.
param(
    [switch]$NoLaunch
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# adb on PATH is preferred; otherwise fall back to the SDK platform-tools location.
$adb = "adb"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $candidate = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $candidate) { $adb = $candidate }
    else { throw "adb not found on PATH or under $env:LOCALAPPDATA\Android\Sdk\platform-tools" }
}

Push-Location $root
try {
    Write-Host "==> Building + installing (installDebug) ..."
    & .\gradlew.bat installDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "installDebug failed (exit $LASTEXITCODE)" }

    if ($NoLaunch) {
        Write-Host "Done (installed; launch skipped)."
        return
    }

    $serials = @()
    foreach ($line in (& $adb devices)) {
        # A device line is "<serial><whitespace>device". The serial may itself contain a
        # space (re-paired transports), so capture everything before the final state word.
        if ($line -match '^(\S.*?)\s+device\s*$') { $serials += $Matches[1] }
    }
    if ($serials.Count -eq 0) {
        Write-Warning "Installed, but no online adb device found to launch."
        return
    }
    $serial = $serials | Select-Object -First 1
    Write-Host "==> Launching on [$serial] ..."
    & $adb -s "$serial" shell am start -n com.fuelroute/.MainActivity
    Write-Host "Done."
}
finally {
    Pop-Location
}
