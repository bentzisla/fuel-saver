package com.fuelroute.car

import android.annotation.SuppressLint
import android.content.Intent
import android.util.Log
import androidx.car.app.AppInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.fuelroute.BuildConfig
import com.fuelroute.data.obd.ObdEngine
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Android Auto entry point (card 15). The car screen is a pure *view*: cards 07/14 own the OBD
 * connection and logging foreground service, and this service only observes the singleton
 * [ObdEngine]. Disconnecting Android Auto therefore never stops logging.
 *
 * ## Android Auto bring-up checklist (verify in this order)
 *
 * The static configuration lives in `AndroidManifest.xml` + `res/xml/automotive_app_desc.xml` and is
 * intentionally minimal. When the app is missing from the car launcher, check in this order:
 *
 * 1. **Service declaration** — `FuelRouteCarAppService` must be `android:exported="true"` with an
 *    intent filter for action [CarAppService.SERVICE_INTERFACE] plus exactly one supported
 *    category. This app uses `androidx.car.app.category.POI` (see [CarAppService.CATEGORY_POI_APP]):
 *    the screen is a fuel/OBD dashboard built from `PaneTemplate`/`MessageTemplate`, has no
 *    turn-by-turn flow, and POI is one of the categories Android Auto surfaces in its app launcher.
 *    Do **not** switch to `category.NAVIGATION` unless a real navigation flow (NavigationTemplate +
 *    navigation intents) is added — declaring it without one is a host mismatch, not a shortcut.
 * 2. **Projected support** — `<meta-data android:name="com.google.android.gms.car.application"
 *    android:resource="@xml/automotive_app_desc" />` must be present and the resource must contain
 *    `<uses name="template" />` (the modern Car App Library declaration for templated apps).
 * 3. **API level** — `androidx.car.app.minCarApiLevel` must be declared. `1` is valid: the library's
 *    range is `[CarAppApiLevels.getOldest()..getLatest()] == [1..8]` in 1.7.0. Missing meta-data
 *    throws at session time.
 * 4. **Phone-side gate** — Android Auto → developer settings → "Unknown sources" must be on.
 * 5. **Trusted source (the 2026 gate)** — Android Auto only runs apps installed from a trusted store
 *    (Google Play / ONE store) in a real vehicle. A manually installed (sideloaded) APK can be
 *    filtered out even with "Unknown sources" enabled; logcat then shows
 *    `CAR.AUTH: ... isPackageAllowed = false` and `Finsky: PlayGearheadService <pkg>, app owners
 *    empty`. This is an Android Auto platform decision, not a manifest bug. Use the **Desktop Head
 *    Unit (DHU)** for local validation (see `AGENTS.md`) and a DEBUG build, which accepts every host.
 *
 * Distribution note: this is for personal sideloaded use only (Android Auto → Developer settings →
 * "Unknown sources"). A generic vehicle-data dashboard is not an approvable Google Play car-app
 * category; do not promise Play distribution.
 */
class FuelRouteCarAppService : CarAppService() {

    /**
     * Earliest lifecycle marker (card 42): the phone bound the car service. If this never shows in
     * `adb logcat -s FuelRoute:*`, Android Auto never selected the app at all — almost always because
     * "Unknown sources" is off, the app is not in the car launcher, or Android Auto's trusted-source
     * gate filtered a sideloaded APK (see the class checklist). The static self-check below is logged
     * here so the exact configuration Android Auto reads is visible without adb tools.
     */
    override fun onCreate() {
        super.onCreate()
        logBringUpConfiguration()
    }

    @SuppressLint("PrivateResource")
    override fun createHostValidator(): HostValidator {
        return if (BuildConfig.DEBUG) {
            // Debug/DHU only: sideloaded development builds are tested against the Desktop Head
            // Unit, which is not a signed host, so accept any host so the DHU can connect.
            Log.i(TAG, "car host validator: allow-all (debug build)")
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // Release: use the Car App Library's own `hosts_allowlist_sample` array instead of a
            // copy-pasted digest list. Despite the "sample" name, this array is the production
            // Android Auto / Android Automotive host allow-list; it is read straight from the
            // resolved `androidx.car.app:app:1.7.0` AAR via HostValidator.Builder.addAllowedHosts.
            // Tracking the library means a Google host-certificate rotation is picked up by bumping
            // the Car App Library — a hard-coded list silently rejects the real host when that
            // happens.
            //
            // Lint flags this array as private (it is not listed in the AAR's public.txt), which is
            // why `@SuppressLint("PrivateResource")` is deliberate: in this non-namespaced,
            // non-shrinking build the resource is merged and resolvable at runtime, and this is the
            // pattern the Car App Library's own samples use. `res/raw/keep.xml` keeps it if resource
            // shrinking is ever enabled.
            Log.i(TAG, "car host validator: library hosts_allowlist_sample (release build)")
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            CarEntryPoint::class.java,
        )
        // Best-effort host diagnostics (card 42). The library validates the host before asking for a
        // session, so reaching here normally means the host was accepted; logging both outcomes makes
        // a rejected/mismatched host visible in `adb logcat -s FuelRoute:*`.
        val host = hostInfo
        if (host == null) {
            Log.w(TAG, "car session requested but hostInfo is null")
        } else {
            val accepted = runCatching { createHostValidator().isValidHost(host) }.getOrDefault(false)
            if (accepted) {
                Log.i(TAG, "car host accepted: ${host.packageName} (uid=${host.uid})")
            } else {
                // The Car App Library logs the exact digest to add under the "CarApp.Val" tag; point
                // at it so a release build that rejects the real host is one logcat line from fixed.
                Log.w(
                    TAG,
                    "car host rejected by validator: ${host.packageName}; " +
                        "install a DEBUG build (allow-all) or check `adb logcat -s CarApp.Val:*` " +
                        "for the digest to allow-list",
                )
            }
        }
        return FuelRouteSession(
            engine = entryPoint.obdEngine(),
            fuelPriceRepository = entryPoint.fuelPriceRepository(),
            vehicleRepository = entryPoint.vehicleRepository(),
            settingsRepository = entryPoint.settingsRepository(),
            hostPackage = host?.packageName,
        )
    }

    /**
     * Logs the static values Android Auto uses for discovery so a missing launcher entry can be
     * told apart from a rejected host. The self-discovery query reuses the host's own intent
     * ([CarAppService.SERVICE_INTERFACE]) against this package, so `selfDiscovered=false` is a
     * manifest bug, while `selfDiscovered=true` with no later "car session created" line points at
     * the phone-side gates (Unknown sources / trusted source) instead.
     */
    private fun logBringUpConfiguration() {
        val build = if (BuildConfig.DEBUG) "debug" else "release"
        val minApi = declaredMinCarApiLevel()
        val categories = runCatching { selfCategories() }.getOrDefault(emptyList())
        val discovered = categories.isNotEmpty()
        Log.i(
            TAG,
            "car bring-up: build=$build minCarApiLevel=$minApi selfDiscovered=$discovered " +
                "categories=$categories",
        )
        if (minApi < 0) {
            Log.w(
                TAG,
                "car bring-up: ${AppInfo.MIN_API_LEVEL_METADATA_KEY} missing/invalid — the host " +
                    "throws when a session is requested",
            )
        }
        if (!discovered) {
            Log.w(
                TAG,
                "car bring-up: Android Auto cannot discover this app — check the CarAppService " +
                    "intent-filter (action ${CarAppService.SERVICE_INTERFACE} + a supported " +
                    "category) in AndroidManifest.xml",
            )
        }
        if (!BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "car bring-up: RELEASE build — a rejected host is silent here; re-test with a " +
                    "DEBUG build (allow-all) and check `adb logcat -s CarApp.Val:*`",
            )
        }
    }

    /**
     * Reads the declared `androidx.car.app.minCarApiLevel` directly from the manifest meta-data.
     * Avoids [AppInfo.retrieveMinCarAppApiLevel], which is `@VisibleForTesting`. aapt stores a
     * numeric `android:value` as an int, so the typed getter is correct here.
     */
    private fun declaredMinCarApiLevel(): Int {
        val metaData = applicationInfo.metaData ?: return -1
        // aapt stores a numeric android:value as an int, which is what the manifest declares.
        return runCatching { metaData.getInt(AppInfo.MIN_API_LEVEL_METADATA_KEY, -1) }
            .getOrDefault(-1)
    }

    /** Reads the categories on our own [CarAppService] intent filter, exactly as the host sees it. */
    @Suppress("DEPRECATION")
    private fun selfCategories(): List<String> {
        val intent = Intent(CarAppService.SERVICE_INTERFACE).setPackage(packageName)
        return packageManager.queryIntentServices(intent, 0)
            .flatMap { info ->
                val filter = info.filter ?: return@flatMap emptyList()
                (0 until filter.countCategories()).map { filter.getCategory(it) }
            }
    }

    private companion object {
        const val TAG = "FuelRoute"
    }
}

/** Hilt access to the singletons the car screen observes. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CarEntryPoint {
    fun obdEngine(): ObdEngine
    fun fuelPriceRepository(): FuelPriceRepository
    fun vehicleRepository(): VehicleRepository
    fun settingsRepository(): SettingsRepository
}
