package com.fuelroute.data.obd

import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.TripEntity
import com.fuelroute.domain.history.TripCost

/**
 * Owns the lifecycle of the currently-open trip row. The run loop only has to call
 * [start]/[checkpoint]/[end]; keeping the generated row id here means a checkpoint
 * updates the same row instead of inserting a duplicate every 30 s.
 */
class TripRecorder(private val tripDao: TripDao) {

    private var tripId: Long? = null
    private var startedAtMs: Long = 0L

    val isOpen: Boolean
        get() = tripId != null

    /** Closes rows left open by a previous crash/force-kill. */
    suspend fun closeLeftovers(nowMs: Long) {
        tripDao.closeOpenTrips(nowMs)
    }

    suspend fun start(vehicleId: String, startedAtMs: Long) {
        this.startedAtMs = startedAtMs
        tripId = tripDao.insert(
            TripEntity(
                vehicleId = vehicleId,
                startedAtMs = startedAtMs,
                endedAtMs = startedAtMs,
                distanceKm = 0.0,
                fuelL = 0.0,
                avgSpeedKmh = 0.0,
                maxSpeedKmh = 0.0,
                idleSeconds = 0.0,
                isOpen = 1,
            ),
        )
    }

    suspend fun checkpoint(
        vehicleId: String,
        nowMs: Long,
        distanceKm: Double,
        fuelL: Double,
        maxSpeedKmh: Double,
        idleSeconds: Double,
    ) {
        write(vehicleId, nowMs, distanceKm, fuelL, maxSpeedKmh, idleSeconds, open = true)
    }

    /** Closes the open trip, snapshots its actual cost, and returns the trip id (or null). */
    suspend fun end(
        vehicleId: String,
        endedAtMs: Long,
        distanceKm: Double,
        fuelL: Double,
        maxSpeedKmh: Double,
        idleSeconds: Double,
        pricePerLiter: Double = 0.0,
    ): Long? {
        val id = tripId ?: return null
        write(
            vehicleId = vehicleId,
            nowMs = endedAtMs,
            distanceKm = distanceKm,
            fuelL = fuelL,
            maxSpeedKmh = maxSpeedKmh,
            idleSeconds = idleSeconds,
            open = false,
            pricePerLiter = pricePerLiter,
        )
        tripId = null
        return id
    }

    private suspend fun write(
        vehicleId: String,
        nowMs: Long,
        distanceKm: Double,
        fuelL: Double,
        maxSpeedKmh: Double,
        idleSeconds: Double,
        open: Boolean,
        pricePerLiter: Double = 0.0,
    ) {
        val id = tripId ?: return
        val durationMs = (nowMs - startedAtMs).coerceAtLeast(0L)
        val cost = TripCost(fuelL = fuelL, pricePerLiterAtTrip = pricePerLiter)
        tripDao.update(
            TripEntity(
                id = id,
                vehicleId = vehicleId,
                startedAtMs = startedAtMs,
                endedAtMs = nowMs,
                distanceKm = distanceKm,
                fuelL = fuelL,
                avgSpeedKmh = if (durationMs > 0L) distanceKm / (durationMs / 3_600_000.0) else 0.0,
                maxSpeedKmh = maxSpeedKmh,
                idleSeconds = idleSeconds,
                isOpen = if (open) 1 else 0,
                actualCost = if (open) 0.0 else cost.actualCost,
                pricePerLiterAtTrip = if (open) 0.0 else cost.pricePerLiterAtTrip,
            ),
        )
    }
}
