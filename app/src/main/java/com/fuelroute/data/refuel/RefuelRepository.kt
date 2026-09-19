package com.fuelroute.data.refuel

import com.fuelroute.data.db.RefuelDao
import com.fuelroute.data.db.RefuelEntity
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.data.db.TripDao
import com.fuelroute.domain.model.Refuel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fuel pumped and OBD-measured between the two most recent full refuels. [pumpedLitres] is
 * the amount added at the latest full refuel (tank-to-tank method); [obdLitres] is the sum
 * of the trip fuel recorded in between.
 */
data class FullRefuelInterval(
    val pumpedLitres: Double,
    val obdLitres: Double,
    val fromTimestampMs: Long,
    val toTimestampMs: Long,
)

interface RefuelRepository {
    suspend fun recent(limit: Int): List<Refuel>
    suspend fun add(liters: Double, totalPrice: Double, isFull: Boolean, vehicleId: String)
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

    override suspend fun recent(limit: Int): List<Refuel> =
        refuelDao.recent(limit).map { it.toDomain() }

    override suspend fun add(liters: Double, totalPrice: Double, isFull: Boolean, vehicleId: String) {
        refuelDao.insert(
            RefuelEntity(
                vehicleId = vehicleId,
                timestampMs = System.currentTimeMillis(),
                liters = liters,
                totalPrice = totalPrice,
                isFull = isFull,
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
            pumpedLitres = latest.liters,
            obdLitres = tripDao.fuelBetween(vehicleId, previous.timestampMs, latest.timestampMs),
            fromTimestampMs = previous.timestampMs,
            toTimestampMs = latest.timestampMs,
        )
    }

    private fun RefuelEntity.toDomain() = Refuel(
        id = id,
        vehicleId = vehicleId,
        timestampMs = timestampMs,
        liters = liters,
        totalPrice = totalPrice,
        isFull = isFull,
    )
}