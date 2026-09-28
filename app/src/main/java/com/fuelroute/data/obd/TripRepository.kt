package com.fuelroute.data.obd

import com.fuelroute.data.db.TripDao
import com.fuelroute.data.history.effectiveAvgSpeedKmh
import com.fuelroute.data.history.effectiveDistanceKm
import com.fuelroute.data.history.effectiveFuelL
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
            // The same read-time view as History (manual entry wins over the OBD measurement,
            // including a direct-cost entry), so the Drive tab and History never disagree.
            Trip(
                id = it.id,
                vehicleId = it.vehicleId,
                startedAtMs = it.startedAtMs,
                endedAtMs = it.endedAtMs,
                distanceKm = it.effectiveDistanceKm(),
                fuelL = it.effectiveFuelL(),
                avgSpeedKmh = it.effectiveAvgSpeedKmh() ?: 0.0,
                maxSpeedKmh = it.maxSpeedKmh,
                idleSeconds = it.idleSeconds,
            )
        }
}