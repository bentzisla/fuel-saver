# wireless-deploy.ps1
# Builds, installs (over Wi-Fi ADB) and launches the FuelRoute debug APK on the phone.
#
# The phone is paired over wireless debugging and may appear under several adb transport
# entries (re-pairing appends " (2)" etc.). `installSideloadDebug` installs to every online device;
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
    # Resolve a single target serial BEFORE building so the pre-install backup pulls from the
    # same device we later install to.
    $serials = @()
    foreach ($line in (& $adb devices)) {
        if ($line -match '^(\S.*?)\s+device\s*$') { $serials += $Matches[1] }
    }
    $serial = $serials | Select-Object -First 1

    # ==== DATABASE BACKUP (data-loss safety net) ====
    # Before any install, snapshot the app's Room database off-device so a botched schema
    # migration can be recovered. Skipped when the app is not installed yet.
    if ($serial) {
        $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $backupDir = Join-Path $root ".\build\db-backups"
        New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
        Write-Host "==> Backing up app database before install ..."
        # PowerShell 5.1 has no -AsByteStream; use cmd /c redirection for a byte-exact copy.
        $dbPath = Join-Path $backupDir "fuelroute-$stamp.db"
        $walPath = Join-Path $backupDir "fuelroute-$stamp.db-wal"
        cmd /c "`"$adb`" -s `"$serial`" exec-out run-as com.fuelroute cat databases/fuelroute.db > `"$dbPath`" 2>nul"
        cmd /c "`"$adb`" -s `"$serial`" exec-out run-as com.fuelroute cat databases/fuelroute.db-wal > `"$walPath`" 2>nul"
        if (Test-Path $dbPath) {
            Write-Host "   saved to $backupDir (fuelroute-$stamp.db)"
        } else {
            Write-Host "   (no database to back up - app not installed or not run yet)"
            Remove-Item -LiteralPath $dbPath -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $walPath -Force -ErrorAction SilentlyContinue
        }
    }

    Write-Host "==> Building + installing (installSideloadDebug) ..."
    & .\gradlew.bat installSideloadDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "installSideloadDebug failed (exit $LASTEXITCODE)" }

    if ($NoLaunch) {
        Write-Host "Done (installed; launch skipped)."
        return
    }

    $serials = @()
    foreach ($line in (& $adb devices)) {
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
