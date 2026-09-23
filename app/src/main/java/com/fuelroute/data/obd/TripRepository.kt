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
            // A manual post-drive entry (km + L/100km) wins over the OBD measurement, mirroring
            // the computed view in DriveHistoryRepository, so Stats and History stay consistent.
            val distanceKm = it.manualDistanceKm ?: it.distanceKm
            val fuelL = if (it.manualDistanceKm != null && it.manualLitersPer100Km != null) {
                it.manualDistanceKm * it.manualLitersPer100Km / 100.0
            } else {
                it.fuelL
            }
            Trip(
                id = it.id,
                vehicleId = it.vehicleId,
                startedAtMs = it.startedAtMs,
                endedAtMs = it.endedAtMs,
                distanceKm = distanceKm,
                fuelL = fuelL,
                avgSpeedKmh = it.avgSpeedKmh,
                maxSpeedKmh = it.maxSpeedKmh,
                idleSeconds = it.idleSeconds,
            )
        }
}