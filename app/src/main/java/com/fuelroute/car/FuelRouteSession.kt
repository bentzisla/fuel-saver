package com.fuelroute.car

import android.content.Intent
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.Session
import com.fuelroute.data.obd.ObdEngine
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository

/**
 * One car session. It never owns the OBD connection — it only hands the singleton repositories
 * to [DashboardScreen], which observes [ObdEngine.live].
 */
class FuelRouteSession(
    private val engine: ObdEngine,
    private val fuelPriceRepository: FuelPriceRepository,
    private val vehicleRepository: VehicleRepository,
    private val settingsRepository: SettingsRepository,
) : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        // Confirms in `adb logcat -s FuelRoute:*` that the Android Auto host bound to the app and
        // asked it for its first screen (card 26's DHU/device check).
        Log.i(TAG, "car session created")
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