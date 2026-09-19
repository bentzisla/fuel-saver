package com.fuelroute.data.obd

import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.TripEntity

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

    suspend fun end(
        vehicleId: String,
        endedAtMs: Long,
        distanceKm: Double,
        fuelL: Double,
        maxSpeedKmh: Double,
        idleSeconds: Double,
    ) {
        write(vehicleId, endedAtMs, distanceKm, fuelL, maxSpeedKmh, idleSeconds, open = false)
        tripId = null
    }

    private suspend fun write(
        vehicleId: String,
        nowMs: Long,
        distanceKm: Double,
        fuelL: Double,
        maxSpeedKmh: Double,
        idleSeconds: Double,
        open: Boolean,
    ) {
        val id = tripId ?: return
        val durationMs = (nowMs - startedAtMs).coerceAtLeast(0L)
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
            ),
        )
    }
}
