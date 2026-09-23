# Publishing FuelRoute on Google Play

## What is ready
| Item | State |
|---|---|
| Signed Android App Bundle | `.\gradlew.bat bundlePlayRelease` -> `app/build/outputs/bundle/playRelease/app-play-release.aab` (signed with the key in `keystore.properties`) |
| Flavors | `play` (Play build) and `sideload` (personal build, everything on). Same applicationId `com.fuelroute` |
| R8 / resource shrinking | On for release. The Android Auto host allow-list resource is kept (`res/raw/keep.xml`) |
| Play-restricted permissions | Removed from the `play` flavor: `SYSTEM_ALERT_WINDOW` (floating overlay), `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| Android Auto | **Kept in both flavors** (see below) |
| targetSdk / 16 KB pages | targetSdk 36; APK verified 16 KB aligned (`zipalign -P 16`) |
| Privacy policy | `privacy-policy.md` (Hebrew + English), linked from Settings -> About |
| Data safety answers | `data-safety.md` |
| Store listing text | `store-listing.md` |

## You must do (cannot be automated)
1. **Play Console developer account** (one-time fee); create the app `com.fuelroute`. Enable **Play App Signing** and upload the `.aab`. Your `keystore.properties` key becomes the *upload key*: back up `release/fuelroute.jks` and `keystore.properties` off this PC. Losing it means a reset request to Google.
2. **Host the privacy policy** at a public URL and enter it in the Play listing. The in-app default link is
   `https://github.com/bentzisla/fuel-saver/blob/master/docs/play/privacy-policy.md` (works only if the repo is public).
   Otherwise host it (e.g. GitHub Pages) and set `PRIVACY_POLICY_URL=...` in `local.properties`.
3. **Google Maps API key restriction.** Play re-signs the app with Google's key, so add the *App signing key SHA-1* shown in Play Console (Setup -> App signing) to the API key's Android restriction, next to the current one (upload key SHA-1: `6E:EC:A5:E3:1D:4D:01:D3:39:29:04:2D:D1:1D:AC:CC:6E:59:FA:5C`), package `com.fuelroute`. Otherwise Routes/Places return 403 in the Play-installed app. (The app sends the *installed* certificate in `X-Android-Cert`, so no code change is needed.)
4. **Store listing assets:** 512x512 icon, 1024x500 feature graphic, 2-8 phone screenshots (take them with test data, not your real trips or addresses).
5. **Forms:** Data safety (`data-safety.md`), Content rating (utility, no user content, no ads), Target audience 18+, Ads: none, **Foreground service declaration** for `connectedDevice` and the Location declaration (texts below).

## Android Auto
Why Android Auto never worked in the car: it only runs apps installed from a trusted store. The phone's logs show `getInstaller: com.fuelroute [null]` (the app was sideloaded), so the host filters it no matter what the manifest says. The fix is to install from Google Play.
- **Internal testing track** (up to 100 testers, no store review) is the quickest route: upload the AAB there, add your Google account as a tester and install from the Play opt-in link. A Play-installed app is trusted. This is my understanding of Google's rules and is not yet confirmed in your car.
- The car screen is a POI-category `PaneTemplate` dashboard. Closed/open/production tracks go through Google's car-app quality review, and a generic OBD dashboard may be rejected there. That is a review risk, not a technical one; the internal track remains available.
- Desktop Head Unit test (no car needed): `scripts/run-dhu.ps1` (its header lists the one-time phone steps).
- Changes made for reliability: the car screen falls back to a plain title on hosts older than Car API 7, the "connect" button never starts the demo drive, and a refused foreground-service start no longer crashes the car session.

## Declaration texts
**Foreground service `connectedDevice`:** "The app reads live engine data from a Bluetooth OBD-II adapter chosen by the user and records fuel consumption while the user drives. The service runs only while the adapter is connected, shows a persistent notification and stops when the user disconnects or the adapter is lost."
**Location:** "Used only to set the current position as the route origin when the user taps 'use my location'. Not collected in the background and never stored on a server."
**Bluetooth:** connects to the user's OBD-II adapter; `BLUETOOTH_SCAN` is declared `neverForLocation`.

## Release checklist
1. Bump the version: `powershell scripts/bump-version.ps1 -Patch` (versionCode must increase for every upload) and add a `Changelog.kt` entry.
2. `.\gradlew.bat testSideloadDebugUnitTest testPlayDebugUnitTest lintSideloadDebug lintPlayDebug bundlePlayRelease`
3. Upload the `.aab` to Internal testing, then Production when satisfied. Also upload `app/build/outputs/mapping/playRelease/mapping.txt` for readable crash stacks.
4. Personal build with the overlay and battery prompt: `.\gradlew.bat installSideloadDebug` (or `assembleSideloadRelease`).

## Known Play risks
- Android Auto category review (above).
- The RFCOMM fallback uses a greylisted reflective call (`createRfcommSocket`); it is not blocked.
- Play may ask why `RECEIVE_BOOT_COMPLETED` is declared: it re-arms OBD auto-connect after a reboot.
- Route and place text is sent to Google's Routes and Places APIs (declared in the data safety form).
- Not verified on a device: a route search in the minified (R8) build. Try one search in the release build before uploading.
