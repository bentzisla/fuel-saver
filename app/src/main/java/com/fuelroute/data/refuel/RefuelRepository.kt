package com.fuelroute.data.refuel

import com.fuelroute.data.db.RefuelDao
import com.fuelroute.data.db.RefuelEntity
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.domain.model.Refuel
import javax.inject.Inject
import javax.inject.Singleton

interface RefuelRepository {
    suspend fun recent(limit: Int): List<Refuel>
    suspend fun add(liters: Double, totalPrice: Double, isFull: Boolean, vehicleId: String)
    suspend fun totalFullLiters(vehicleId: String): Double
    suspend fun totalObdFuel(vehicleId: String): Double
}

@Singleton
class DefaultRefuelRepository @Inject constructor(
    private val refuelDao: RefuelDao,
    private val speedBinDao: SpeedBinDao,
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

    private fun RefuelEntity.toDomain() = Refuel(
        id = id,
        vehicleId = vehicleId,
        timestampMs = timestampMs,
        liters = liters,
        totalPrice = totalPrice,
        isFull = isFull,
    )
}