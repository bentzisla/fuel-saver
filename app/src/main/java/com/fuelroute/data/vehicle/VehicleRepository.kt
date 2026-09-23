package com.fuelroute.data.vehicle

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fuelroute.data.db.RefuelDao
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.VehicleDao
import com.fuelroute.data.db.VehicleEntity
import com.fuelroute.domain.learning.EngineDisplacement
import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.SpeedPoint
import com.fuelroute.domain.model.VehicleProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

interface VehicleRepository {
    /** All known vehicles, ordered by creation time. */
    fun vehicles(): Flow<List<VehicleProfile>>

    /** The currently selected vehicle, bootstrapping the first one when needed. */
    suspend fun active(): VehicleProfile

    /** The vehicle whose stored VIN equals [vin], if any (case-sensitive). */
    suspend fun findByVin(vin: String): VehicleProfile?

    suspend fun setActive(id: String)

    /** Inserts or updates [profile]; becomes active when it is the first vehicle. */
    suspend fun upsert(profile: VehicleProfile)

    /** Deletes [id] unless it is the last remaining vehicle. */
    suspend fun delete(id: String)

    suspend fun updateFuelRateCorrection(value: Double)
}

/**
 * Room is the source of truth for vehicles. The `vehicle` DataStore is kept only for the
 * active-vehicle pointer (and, on first run, as the legacy single-profile source that is
 * migrated into the table).
 */
@Singleton
class DefaultVehicleRepository @Inject constructor(
    @Named("vehicle") private val dataStore: DataStore<Preferences>,
    private val vehicleDao: VehicleDao,
    private val speedBinDao: SpeedBinDao,
    private val tripDao: TripDao,
    private val refuelDao: RefuelDao,
) : VehicleRepository {

    private object Keys {
        // Legacy single-profile keys, read once during bootstrap.
        val ID = stringPreferencesKey("vehicle_id")
        val NAME = stringPreferencesKey("vehicle_name")
        val FUEL_TYPE = stringPreferencesKey("vehicle_fuel_type")
        val RATED_COMBINED = doublePreferencesKey("vehicle_rated_combined_l100")
        val ENGINE_DISPLACEMENT = stringPreferencesKey("vehicle_engine_displacement_l")
        val TANK_CAPACITY = stringPreferencesKey("vehicle_tank_capacity_l")
        val CORRECTION = doublePreferencesKey("vehicle_fuel_rate_correction")
        val MANUAL_CURVE = stringPreferencesKey("vehicle_manual_curve")

        // New pointer.
        val ACTIVE_ID = stringPreferencesKey("active_vehicle_id")
    }

    private val bootstrapMutex = Mutex()
    private var bootstrapped = false

    override fun vehicles(): Flow<List<VehicleProfile>> = flow {
        ensureBootstrapped()
        emitAll(vehicleDao.getAll().map { entities -> entities.map { it.toDomain() } })
    }

    override suspend fun active(): VehicleProfile {
        ensureBootstrapped()
        val selectedId = activeId()
        val entity = selectedId?.let { vehicleDao.getById(it) }
            ?: vehicleDao.getAll().first().firstOrNull()
            ?: error("Vehicle table is empty after bootstrap")
        if (entity.id != selectedId) setActiveId(entity.id)
        return entity.toDomain()
    }

    override suspend fun setActive(id: String) {
        ensureBootstrapped()
        if (vehicleDao.getById(id) != null) setActiveId(id)
    }

    override suspend fun findByVin(vin: String): VehicleProfile? {
        ensureBootstrapped()
        return vehicleDao.getByVin(vin)?.toDomain()
    }

    override suspend fun upsert(profile: VehicleProfile) {
        ensureBootstrapped()
        val id = profile.id.ifBlank { UUID.randomUUID().toString() }
        val createdAtMs = vehicleDao.getById(id)?.createdAtMs ?: System.currentTimeMillis()
        vehicleDao.upsert(listOf(profile.copy(id = id).toEntity(createdAtMs)))
        if (activeId() == null) setActiveId(id)
    }

    override suspend fun delete(id: String) {
        ensureBootstrapped()
        if (vehicleDao.count() <= 1) return
        vehicleDao.deleteWithChildren(id)
        if (activeId() == id) {
            vehicleDao.getAll().first().firstOrNull()?.let { setActiveId(it.id) }
        }
    }

    override suspend fun updateFuelRateCorrection(value: Double) {
        val current = active()
        upsert(current.copy(fuelRateCorrection = value))
    }

    private suspend fun activeId(): String? = preferences()[Keys.ACTIVE_ID]

    private suspend fun setActiveId(id: String) {
        dataStore.edit { prefs -> prefs[Keys.ACTIVE_ID] = id }
    }

    private suspend fun preferences(): Preferences =
        dataStore.data.catch { emit(emptyPreferences()) }.first()

    /**
     * On first access after the multi-vehicle migration, materialize a vehicle row from the
     * legacy DataStore profile and repoint any OBD/trip/refuel rows that were written under the
     * old blank or placeholder vehicle id. Safe to call repeatedly.
     */
    private suspend fun ensureBootstrapped() {
        bootstrapMutex.withLock {
            if (bootstrapped) return
            if (vehicleDao.count() == 0) {
                val prefs = preferences()
                val legacyId = prefs[Keys.ID].orEmpty()
                val newId = legacyId.ifBlank { UUID.randomUUID().toString() }
                val legacy = VehicleProfile(
                    id = newId,
                    name = prefs[Keys.NAME].orEmpty(),
                    fuelType = prefs[Keys.FUEL_TYPE]
                        ?.let { runCatching { FuelType.valueOf(it) }.getOrNull() }
                        ?: FuelType.GASOLINE,
                    ratedCombinedL100 = prefs[Keys.RATED_COMBINED] ?: DEFAULT_RATED_L100,
                    engineDisplacementL = EngineDisplacement.parseLiters(prefs[Keys.ENGINE_DISPLACEMENT]),
                    tankCapacityL = prefs[Keys.TANK_CAPACITY]?.toDoubleOrNull(),
                    manualCurve = decodeManualCurve(prefs[Keys.MANUAL_CURVE]),
                    fuelRateCorrection = prefs[Keys.CORRECTION] ?: 1.0,
                )
                vehicleDao.upsert(listOf(legacy.toEntity(System.currentTimeMillis())))
                setActiveId(newId)
                // Repoint orphaned rows (written under the old blank/placeholder id). The vehicle
                // row is already inserted, so only genuinely unknown ids are affected.
                speedBinDao.repointOrphans(newId)
                tripDao.repointOrphans(newId)
                refuelDao.repointOrphans(newId)
            } else {
                val selectedId = activeId()
                if (selectedId == null || vehicleDao.getById(selectedId) == null) {
                    vehicleDao.getAll().first().firstOrNull()?.let { setActiveId(it.id) }
                }
            }
            bootstrapped = true
        }
    }

    private fun VehicleEntity.toDomain() = VehicleProfile(
        id = id,
        name = name,
        fuelType = runCatching { FuelType.valueOf(fuelType) }.getOrDefault(FuelType.GASOLINE),
        ratedCombinedL100 = ratedCombinedL100,
        // A value typed in cc (e.g. 1800) is normalized to litres on every read and write.
        engineDisplacementL = EngineDisplacement.normalizeLiters(engineDisplacementL),
        manualCurve = decodeManualCurve(manualCurve),
        fuelRateCorrection = fuelRateCorrection,
        tankCapacityL = tankCapacityL,
        vin = vin,
        grade = grade,
    )

    private fun VehicleProfile.toEntity(createdAtMs: Long) = VehicleEntity(
        id = id,
        name = name,
        fuelType = fuelType.name,
        ratedCombinedL100 = ratedCombinedL100,
        // A value typed in cc (e.g. 1800) is normalized to litres on every read and write.
        engineDisplacementL = EngineDisplacement.normalizeLiters(engineDisplacementL),
        tankCapacityL = tankCapacityL,
        fuelRateCorrection = fuelRateCorrection,
        manualCurve = encodeManualCurve(manualCurve),
        vin = vin,
        grade = grade,
        createdAtMs = createdAtMs,
    )

    private fun encodeManualCurve(curve: List<SpeedPoint>?): String? =
        curve
            ?.takeIf { it.isNotEmpty() }
            ?.filter { it.speedKmh > 0.0 }
            ?.sortedBy { it.speedKmh }
            ?.joinToString(",") { "${it.speedKmh}:${it.litersPer100Km}" }

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
