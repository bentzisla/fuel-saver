package com.fuelroute.data.vehicle

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.SpeedPoint
import com.fuelroute.domain.model.VehicleProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

interface VehicleRepository {
    val profile: Flow<VehicleProfile>
    suspend fun save(profile: VehicleProfile)
    suspend fun updateFuelRateCorrection(value: Double)
}

@Singleton
class DataStoreVehicleRepository @Inject constructor(
    @Named("vehicle") private val dataStore: DataStore<Preferences>,
) : VehicleRepository {

    private object Keys {
        val ID = stringPreferencesKey("vehicle_id")
        val NAME = stringPreferencesKey("vehicle_name")
        val FUEL_TYPE = stringPreferencesKey("vehicle_fuel_type")
        val RATED_COMBINED = doublePreferencesKey("vehicle_rated_combined_l100")
        val ENGINE_DISPLACEMENT = stringPreferencesKey("vehicle_engine_displacement_l")
        val TANK_CAPACITY = stringPreferencesKey("vehicle_tank_capacity_l")
        val CORRECTION = doublePreferencesKey("vehicle_fuel_rate_correction")
        val MANUAL_CURVE = stringPreferencesKey("vehicle_manual_curve")
    }

    override val profile: Flow<VehicleProfile> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            VehicleProfile(
                id = prefs[Keys.ID].orEmpty(),
                name = prefs[Keys.NAME].orEmpty(),
                fuelType = prefs[Keys.FUEL_TYPE]
                    ?.let { runCatching { FuelType.valueOf(it) }.getOrNull() }
                    ?: FuelType.GASOLINE,
                ratedCombinedL100 = prefs[Keys.RATED_COMBINED] ?: DEFAULT_RATED_L100,
                engineDisplacementL = prefs[Keys.ENGINE_DISPLACEMENT]?.toDoubleOrNull(),
                tankCapacityL = prefs[Keys.TANK_CAPACITY]?.toDoubleOrNull(),
                manualCurve = decodeManualCurve(prefs[Keys.MANUAL_CURVE]),
                fuelRateCorrection = prefs[Keys.CORRECTION] ?: 1.0,
            )
        }

    override suspend fun save(profile: VehicleProfile) {
        dataStore.edit { prefs ->
            prefs[Keys.ID] = profile.id
            prefs[Keys.NAME] = profile.name
            prefs[Keys.FUEL_TYPE] = profile.fuelType.name
            prefs[Keys.RATED_COMBINED] = profile.ratedCombinedL100
            prefs[Keys.ENGINE_DISPLACEMENT] = profile.engineDisplacementL?.toString().orEmpty()
            prefs[Keys.TANK_CAPACITY] = profile.tankCapacityL?.toString().orEmpty()
            prefs[Keys.CORRECTION] = profile.fuelRateCorrection
            prefs[Keys.MANUAL_CURVE] = encodeManualCurve(profile.manualCurve)
        }
    }

    override suspend fun updateFuelRateCorrection(value: Double) {
        save(profile.first().copy(fuelRateCorrection = value))
    }

    private fun encodeManualCurve(curve: List<SpeedPoint>?): String =
        curve.orEmpty()
            .filter { it.speedKmh > 0.0 }
            .sortedBy { it.speedKmh }
            .joinToString(",") { "${it.speedKmh}:${it.litersPer100Km}" }

    private fun decodeManualCurve(raw: String?): List<SpeedPoint>? {
        if (raw.isNullOrBlank()) return null
        val points = raw.split(",").mapNotNull { token ->
            val parts = token.split(":")
            if (parts.size != 2) return@mapNotNull null
            val speed = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val l100 = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            SpeedPoint(speed, l100)
        }
        return points.takeIf { it.size >= 2 }
    }

    private companion object {
        const val DEFAULT_RATED_L100 = 7.0
    }
}