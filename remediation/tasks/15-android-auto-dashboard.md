# Task 15 — Android Auto: live OBD dashboard on the car screen

**User request:** #5 — "full Android Auto support, showing the OBD dashboard on the car's screen, especially live fuel
usage and live cost per hour".
**REMEDIATION items:** 8.5.
**Depends on:** card 03 (`LiveObdState` with fuel rate + `sampleRateHz`), card 02 (active vehicle), card 07/14
(the logging service must already run on its own — the car screen is a *view*, never the thing that starts logging).
**Touches:** new `car/` package (`FuelRouteCarAppService.kt`, `FuelRouteSession.kt`, `DashboardScreen.kt`),
`AndroidManifest.xml`, `res/xml/automotive_app_desc.xml` (new), `domain/obd/LiveCostCalculator.kt` (new, pure),
`res/values/strings.xml`.
**Shared file warning:** this card needs **new Gradle dependencies**. Per `remediation/RUNBOOK.md` the orchestrator
owns `gradle/libs.versions.toml` — request the entries instead of inventing conflicting ones, or add them and say so
explicitly in your report.

## Read this before you start — real constraints (do not design around wishes)
1. **Distribution.** Google Play only accepts car apps in a fixed set of categories (navigation, parking, EV charging,
   media, messaging, POI, IOT). A generic vehicle-data dashboard is **not** an approvable category, so this feature is
   for **personal sideloaded use**: the phone must have Android Auto → Developer settings → "Unknown sources" enabled.
   Do not promise Play distribution anywhere in the UI or docs. Record this in `PLAN.md` section 9 (risks).
2. **Templates only — no custom Compose UI.** The car screen is built from Car App Library templates
   (`PaneTemplate`, `GridTemplate`, `MessageTemplate`, ...). You cannot render arbitrary Compose there.
3. **Refresh is throttled.** The host rate-limits `invalidate()`; a 4 Hz gauge is impossible. Target ~**1 update/second**
   and coalesce `LiveObdState` (which updates every 250 ms) before pushing. Design the numbers to be readable at 1 Hz
   (smoothed, not jittery).
4. **Testing without a car** uses the **Desktop Head Unit (DHU)**, which is a desktop app — it does *not* need the
   Android emulator (virtualization is disabled on this machine, per `AGENTS.md`, so DHU is the only option):
   `sdkmanager --install "extras;google;auto"`, enable Android Auto developer mode + head-unit server on the phone,
   `adb forward tcp:5277 tcp:5277`, then run `desktop-head-unit.exe`.

## Steps

1. **Dependencies + manifest.**
   - `androidx.car.app:app` and `androidx.car.app:app-projected` (latest stable) via the version catalog.
   - `AndroidManifest.xml`:
     ```xml
     <service android:name=".car.FuelRouteCarAppService" android:exported="true">
       <intent-filter><action android:name="androidx.car.app.CarAppService"/>
         <category android:name="androidx.car.app.category.NAVIGATION"/></intent-filter>
     </service>
     <meta-data android:name="com.google.android.gms.car.application"
                android:resource="@xml/automotive_app_desc"/>
     <meta-data android:name="androidx.car.app.minCarApiLevel" android:value="1"/>
     ```
     `res/xml/automotive_app_desc.xml` → `<automotiveApp><uses name="template"/></automotiveApp>`.
     Use the `NAVIGATION` category (this app *is* a routing app, and that category permits the richer templates);
     note `POI` as the fallback if the host rejects it.
2. **`domain/obd/LiveCostCalculator.kt` (pure, fully unit-tested)** — this is the actual product value:
   - `costPerHour(fuelRateLph, pricePerLiter): Double` = `fuelRateLph * pricePerLiter` → ₪/h
   - `costPerKm(fuelRateLph, speedKmh, pricePerLiter): Double?` = null when `speedKmh < 1`
   - `litersPer100Km(fuelRateLph, speedKmh): Double?` = null when `speedKmh < 1` (idle is ₪/h, not L/100)
   - `tripCost(fuelL, pricePerLiter): Double`
   - a small smoother (e.g. EMA with a named constant in `ModelConstants`) so the car display doesn't flicker.
   Keep it Android-free so it is testable on the JVM.
3. **`car/FuelRouteCarAppService`** (`createHostValidator` — use
   `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` **only** in debug, the real allow-list otherwise) returning a
   `FuelRouteSession` whose `onCreateScreen` is `DashboardScreen`.
4. **`car/DashboardScreen`** — a `PaneTemplate` with large, glanceable rows, Hebrew labels:
   - **"צריכה כרגע"** — L/100 km while moving, L/h at idle
   - **"עלות לשעה"** — ₪/h (the headline number the user asked for)
   - **"עלות הנסיעה"** — ₪ so far this trip
   - **"מהירות"** / **"סה\"כ ק\"מ"** — secondary rows
   Collect `ObdEngine.live` in the `Session` lifecycle scope, throttle to 1 Hz
   (`sample(1.seconds)`/`conflate`), and call `invalidate()` only when a displayed value actually changed.
   Not connected → a `MessageTemplate` with the reason from `LiveObdState.lastError` and a "התחבר" action that starts
   the logging service (a single tap, parked-safe).
5. **Lifecycle hygiene.** The car screen must **not** own the OBD connection: it observes the singleton engine /
   foreground service from cards 07/14. Disconnecting Android Auto must never stop logging, and the screen must
   release its collector in `onStop` (no leaked coroutine, no wake-lock of its own).
6. **Driver distraction.** No scrolling lists of live data, no text input, no more than the template's allowed row
   count; everything readable at a glance. Follow the templated-app UX requirements.

## Tests (JUnit 4)
- `LiveCostCalculator`: ₪/h at idle (speed 0, non-zero L/h); ₪/km and L/100 null below 1 km/h; exact arithmetic for a
  known rate/price pair; smoother converges and never returns NaN for null/zero inputs.
- The throttling/"changed?" predicate: identical consecutive states produce no `invalidate()`.
- (Car templates themselves are host-driven; cover them with the DHU check below, not unit tests.)

## Verify
```
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat lintDebug --console=plain
```

## Done when
- DHU shows the dashboard with live values while `SimulatedObdTransport` runs (no car needed for this check).
- ₪/h and L/100 km update about once a second and stay readable (no flicker, no stale zeros).
- Disconnecting/reconnecting Android Auto does not stop or restart logging.
- Report explicitly whether the DHU check ran, and the exact manifest/deps you added.

Do **not** commit.
