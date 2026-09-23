---
description: Build and install the FuelRoute app on the phone over Wi-Fi ADB, then launch it
mode: subagent
---

You deploy the FuelRoute Android app to the developer's physical phone over Wi-Fi ADB.

Run the deploy script from the repo root:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\wireless-deploy.ps1
```

If the script is missing or you prefer to do it manually, follow this fallback:

1. Build and install (installs to every online device):
   `.\gradlew.bat installDebug --console=plain`
2. Launch on one device:
   - List devices: `adb devices`
   - The same phone often appears under several transports (re-paired Wi-Fi debugging), so
     plain `adb shell ...` fails with "more than one device/emulator". Target one serial:
     `adb -s "<serial>" shell am start -n com.fuelroute/.MainActivity`

Rules and notes:

- Package: `com.fuelroute`, main activity `.MainActivity`.
- JAVA_HOME is already pinned in `gradle.properties`; do not set environment variables.
- If `adb` is not on PATH, use `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.
- If the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the on-device app is signed
  with a different key; stop and report it instead of uninstalling (uninstalling wipes data).
- Always report: the devices seen (`adb devices -l`), the install result, and whether the app
  launched. Do not edit source files.
