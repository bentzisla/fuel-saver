package com.fuelroute.car

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

    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) {
            // Debug/DHU only: accept any host so the Desktop Head Unit can connect.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // Release allow-list: Android Auto (gearhead) with the official signing digests.
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            CarEntryPoint::class.java,
        )
        return FuelRouteSession(
            engine = entryPoint.obdEngine(),
            fuelPriceRepository = entryPoint.fuelPriceRepository(),
            vehicleRepository = entryPoint.vehicleRepository(),
            settingsRepository = entryPoint.settingsRepository(),
        )
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