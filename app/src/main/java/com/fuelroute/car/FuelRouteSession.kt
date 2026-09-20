package com.fuelroute.car

import android.content.Intent
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

    override fun onCreateScreen(intent: Intent): Screen = DashboardScreen(
        carContext = carContext,
        engine = engine,
        fuelPriceRepository = fuelPriceRepository,
        vehicleRepository = vehicleRepository,
        settingsRepository = settingsRepository,
    )
}