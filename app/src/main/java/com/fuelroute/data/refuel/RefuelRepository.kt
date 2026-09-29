package com.fuelroute.data.refuel

import com.fuelroute.data.db.RefuelDao
import com.fuelroute.data.db.RefuelEntity
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.data.db.TripDao
import com.fuelroute.domain.model.Refuel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fuel pumped and OBD-measured between the two most recent full refuels (tank-to-tank method).
 * [pumpedLitres] is every fill after the previous full one up to and including the latest full
 * one (partial top-ups in between were burned too); [obdLitres] is the trip fuel recorded in
 * between. [activeCorrection] is the OBD fuel-rate correction those trips were logged with, when
 * the latest fill recorded it (null for fills from before v11).
 */
data class FullRefuelInterval(
    val pumpedLitres: Double,
    val obdLitres: Double,
    val fromTimestampMs: Long,
    val toTimestampMs: Long,
    val activeCorrection: Double? = null,
)

interface RefuelRepository {
    suspend fun recent(vehicleId: String, limit: Int): List<Refuel>

    /**
     * Records one refuel. [pricePerLiter] is derived from [totalPrice] / [liters]; [grade] is the
     * active vehicle's fuel grade at the time of the fill; [obdCorrection] the vehicle's OBD
     * fuel-rate correction at that time (see [FullRefuelInterval.activeCorrection]).
     */
    suspend fun add(
        liters: Double,
        totalPrice: Double,
        isFull: Boolean,
        vehicleId: String,
        grade: String,
        obdCorrection: Double? = null,
    )
    suspend fun totalFullLiters(vehicleId: String): Double
    suspend fun totalObdFuel(vehicleId: String): Double

    /** The last tank-to-tank interval, or null until two full refuels exist. */
    suspend fun lastFullInterval(vehicleId: String): FullRefuelInterval?
}

@Singleton
class DefaultRefuelRepository @Inject constructor(
    private val refuelDao: RefuelDao,
    private val speedBinDao: SpeedBinDao,
    private val tripDao: TripDao,
) : RefuelRepository {

    override suspend fun recent(vehicleId: String, limit: Int): List<Refuel> =
        refuelDao.recentForVehicle(vehicleId, limit).map { it.toDomain() }

    override suspend fun add(
        liters: Double,
        totalPrice: Double,
        isFull: Boolean,
        vehicleId: String,
        grade: String,
        obdCorrection: Double?,
    ) {
        refuelDao.insert(
            RefuelEntity(
                vehicleId = vehicleId,
                timestampMs = System.currentTimeMillis(),
                liters = liters,
                totalPrice = totalPrice,
                isFull = isFull,
                pricePerLiter = if (liters > 0.0) totalPrice / liters else 0.0,
                grade = grade,
                obdCorrectionAtFill = obdCorrection,
            ),
        )
    }

    override suspend fun totalFullLiters(vehicleId: String): Double =
        refuelDao.totalFullLiters(vehicleId)

    override suspend fun totalObdFuel(vehicleId: String): Double =
        speedBinDao.totalFuelForVehicle(vehicleId)

    override suspend fun lastFullInterval(vehicleId: String): FullRefuelInterval? {
        val fulls = refuelDao.fullRefuelsSince(vehicleId, 0L)
        if (fulls.size < 2) return null
        val previous = fulls[fulls.size - 2]
        val latest = fulls[fulls.size - 1]
        return FullRefuelInterval(
            // Not just the latest fill: partial top-ups since the previous full fill were burned
            // in this interval too, and the OBD litres below include their fuel.
            pumpedLitres = refuelDao.litersBetween(vehicleId, previous.timestampMs, latest.timestampMs),
            obdLitres = tripDao.fuelBetween(vehicleId, previous.timestampMs, latest.timestampMs),
            fromTimestampMs = previous.timestampMs,
            toTimestampMs = latest.timestampMs,
            activeCorrection = latest.obdCorrectionAtFill,
        )
    }

    private fun RefuelEntity.toDomain() = Refuel(
        id = id,
        vehicleId = vehicleId,
        timestampMs = timestampMs,
        liters = liters,
        totalPrice = totalPrice,
        isFull = isFull,
        grade = grade,
    )
}