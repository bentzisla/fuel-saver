package com.fuelroute.car

import android.content.Context
import android.util.Log
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
 * Distribution note: this is for personal sideloaded use only (Android Auto → Developer settings →
 * "Unknown sources"). A generic vehicle-data dashboard is not an approvable Google Play car-app
 * category; do not promise Play distribution.
 */
class FuelRouteCarAppService : CarAppService() {

    /**
     * Earliest lifecycle marker (card 42): the phone bound the car service. If this never shows in
     * `adb logcat -s FuelRoute:*`, Android Auto never selected the app at all — almost always because
     * "Unknown sources" is off or the app is not in the car launcher (see the Settings help card).
     */
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "car service created (host may bind)")
    }

    override fun createHostValidator(): HostValidator {
        return if (BuildConfig.DEBUG) {
            // Debug/DHU only: sideloaded development builds are tested against the Desktop Head
            // Unit, which is not a signed host, so accept any host so the DHU can connect.
            Log.i(TAG, "car host validator: allow-all (debug build)")
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            Log.i(TAG, "car host validator: release allow-list (${ALLOWED_HOSTS.size} entries)")
            releaseHostValidator(applicationContext)
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
                Log.w(TAG, "car host rejected by validator: ${host.packageName}")
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

    private companion object {
        const val TAG = "FuelRoute"

        /**
         * Release allow-list: the real Android Auto and Android Automotive OS hosts, by package
         * name + SHA-256 signing-certificate digest.
         *
         * Do **not** use `androidx.car.app.R.array.hosts_allowlist_sample` here. That is the Car
         * App Library's *sample* allow-list; it only matches the sample host, so the real host is
         * rejected and the dashboard never appears (card 26's "not showing" bug). These digests
         * are the public signing certificates of the production hosts; update them if Google
         * rotates the host certificates.
         */
        val ALLOWED_HOSTS: List<Pair<String, String>> = listOf(
            // Android Auto (projected) host.
            "com.google.android.projection.gearhead" to
                "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf",
            "com.google.android.projection.gearhead" to
                "70811a3eacfd2e83e18da9bfede52df16ce91f2e69a44d21f18ab66991130771",
            "com.google.android.projection.gearhead" to
                "1975b2f17177bc89a5dff31f9e64a6cae281a53dc1d1d59b1d147fe1c82afa00",
            // Android Automotive OS (built-in) templates host.
            "com.google.android.apps.automotive.templates.host" to
                "c241ffbc8e287c4e9a4ad19632ba1b1351ad361d5177b7d7b29859bd2b7fc631",
            "com.google.android.apps.automotive.templates.host" to
                "dd66deaf312d8daec7adbe85a218ecc8c64f3b152f9b5998d5b29300c2623f61",
            "com.google.android.apps.automotive.templates.host" to
                "50e603d333c6049a37bd751375d08f3bd0abebd33facd30bd17b64b89658b421",
        )

        /** Builds the real (non-sample) host allow-list used by release builds. */
        fun releaseHostValidator(context: Context): HostValidator =
            HostValidator.Builder(context).apply {
                ALLOWED_HOSTS.forEach { (packageName, digest) ->
                    addAllowedHost(packageName, digest)
                }
            }.build()
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
