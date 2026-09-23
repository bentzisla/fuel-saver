<#
.SYNOPSIS
    Starts the Android Auto Desktop Head Unit (DHU) against the connected phone so the FuelRoute
    car screen can be tested without a car.

.DESCRIPTION
    One-time setup:
      1. sdkmanager --install "extras;google;auto"   (installs desktop-head-unit.exe)
      2. On the phone: Android Auto -> tap "Version" 10 times to enable developer mode, then the
         three-dot menu -> "Start head unit server". Also enable Developer settings ->
         "Unknown sources" (needed for a sideloaded build; the DHU accepts debug builds).
    Then run this script with the phone connected over USB or wireless adb.
    Watch the app side with:  adb logcat -s FuelRoute:* CarApp.Val:*
    Expected lines: "car bring-up ... selfDiscovered=true", "car session created",
    "car first template served".
#>
$ErrorActionPreference = 'Stop'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$dhu = Join-Path $sdk 'extras\google\auto\desktop-head-unit.exe'
if (-not (Test-Path $dhu)) { throw "DHU not installed. Run: sdkmanager --install `"extras;google;auto`"" }
& $adb forward tcp:5277 tcp:5277
& $dhu
