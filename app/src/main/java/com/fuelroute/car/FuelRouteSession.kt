package com.fuelroute.car

import android.content.Intent
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.lifecycleScope
import com.fuelroute.data.obd.ObdEngine
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository
import kotlinx.coroutines.launch

/**
 * One car session. It never owns the OBD connection — it only hands the singleton repositories
 * to [DashboardScreen], which observes [ObdEngine.live].
 */
class FuelRouteSession(
    private val engine: ObdEngine,
    private val fuelPriceRepository: FuelPriceRepository,
    private val vehicleRepository: VehicleRepository,
    private val settingsRepository: SettingsRepository,
    private val hostPackage: String? = null,
) : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        // Confirms in `adb logcat -s FuelRoute:*` that the Android Auto host bound to the app and
        // asked it for its first screen (cards 26/42's DHU/device check). The host's Car App API
        // level (card 45) tells whether it can render the API-7 `Header` used by DashboardScreen:
        // a value < 7 explains a screen that appears but fails to render, without any other signal.
        val apiLevel = runCatching { carContext.carAppApiLevel }.getOrDefault(-1)
        Log.i(
            TAG,
            "car session created (screen requested, host=${hostPackage ?: "unknown"}, " +
                "hostCarApiLevel=$apiLevel)",
        )
        // Persist a user-visible "car app last-seen" marker (card 42) so the Settings screen can show
        // whether the phone ever reached the car host — without adb.
        lifecycleScope.launch {
            runCatching {
                settingsRepository.saveCarLastSeen(System.currentTimeMillis())
                hostPackage?.let { settingsRepository.saveCarLastHost(it) }
            }.onFailure { Log.w(TAG, "failed to persist car last-seen", it) }
        }
        return DashboardScreen(
            carContext = carContext,
            engine = engine,
            fuelPriceRepository = fuelPriceRepository,
            vehicleRepository = vehicleRepository,
            settingsRepository = settingsRepository,
        )
    }

    private companion object {
        const val TAG = "FuelRoute"
    }
}