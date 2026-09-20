package com.fuelroute.data.obd

import com.fuelroute.data.db.TripDao
import com.fuelroute.domain.model.Trip
import javax.inject.Inject
import javax.inject.Singleton

interface TripRepository {
    suspend fun recentTrips(vehicleId: String, limit: Int): List<Trip>
}

@Singleton
class DefaultTripRepository @Inject constructor(
    private val tripDao: TripDao,
) : TripRepository {

    override suspend fun recentTrips(vehicleId: String, limit: Int): List<Trip> =
        tripDao.recentForVehicle(vehicleId, limit).map {
            Trip(
                id = it.id,
                vehicleId = it.vehicleId,
                startedAtMs = it.startedAtMs,
                endedAtMs = it.endedAtMs,
                distanceKm = it.distanceKm,
                fuelL = it.fuelL,
                avgSpeedKmh = it.avgSpeedKmh,
                maxSpeedKmh = it.maxSpeedKmh,
                idleSeconds = it.idleSeconds,
            )
        }
}