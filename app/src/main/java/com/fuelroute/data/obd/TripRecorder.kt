package com.fuelroute.data.obd

import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.TripEntity
import com.fuelroute.data.db.TripSource
import com.fuelroute.domain.history.TripCost
import com.fuelroute.domain.learning.TripContinuation

/**
 * Owns the lifecycle of the currently-open trip row. The run loop only has to call
 * [startOrContinue]/[checkpoint]/[end]; keeping the generated row id here means a checkpoint
 * updates the same row instead of inserting a duplicate every 30 s.
 *
 * Writes touch only the columns the recorder owns (targeted UPDATEs): the provenance [source] is
 * written once at insert, and a route-search link, manual entry or cold-start figure written by
 * someone else meanwhile is never reset by a checkpoint.
 */
class TripRecorder(private val tripDao: TripDao) {

    private var tripId: Long? = null
    private var startedAtMs: Long = 0L

    val isOpen: Boolean
        get() = tripId != null

    /**
     * Start timestamp of the currently-open (or just-closed) trip. The link wiring anchors
     * [com.fuelroute.data.history.TripLinker.autoLink] on this value so the match always uses
     * the same start that was written to the trip row.
     */
    val tripStartedAtMs: Long
        get() = startedAtMs

    /** True when the open trip was continued from a row that already had a route-search link. */
    var continuedLinkedTrip: Boolean = false
        private set

    /** Totals already stored in a continued trip row; the run adds its own on top. */
    data class ContinuedTotals(
        val distanceKm: Double,
        val fuelL: Double,
        val maxSpeedKmh: Double,
        val idleSeconds: Double,
        val seconds: Double,
    )

    /** Closes rows left open by a previous crash/force-kill. */
    suspend fun closeLeftovers(nowMs: Long) {
        tripDao.closeOpenTrips(nowMs)
    }

    /**
     * Continues the vehicle's last trip when it closed less than
     * [TripContinuation.MAX_GAP_MS] before [startedAtMs] (same drive, the link just dropped),
     * else inserts a new row. Returns the continued row's totals, or null for a new trip.
     */
    suspend fun startOrContinue(
        vehicleId: String,
        startedAtMs: Long,
        source: String = TripSource.REAL,
    ): ContinuedTotals? {
        val last = tripDao.recentForVehicle(vehicleId, 1).firstOrNull()
        val candidate = last?.let {
            TripContinuation.LastTrip(
                endedAtMs = it.endedAtMs,
                isOpen = it.isOpen != 0,
                source = it.source,
                hasManualEntry = it.manualEnteredAtMs != null,
            )
        }
        if (last != null && TripContinuation.canContinue(candidate, source, startedAtMs)) {
            tripDao.reopen(last.id)
            tripId = last.id
            this.startedAtMs = last.startedAtMs
            continuedLinkedTrip = last.routeSearchId != null
            return ContinuedTotals(
                distanceKm = last.distanceKm,
                fuelL = last.fuelL,
                maxSpeedKmh = last.maxSpeedKmh,
                idleSeconds = last.idleSeconds,
                seconds = ((last.endedAtMs - last.startedAtMs).coerceAtLeast(0L)) / 1000.0,
            )
        }
        start(vehicleId, startedAtMs, source)
        return null
    }

    suspend fun start(vehicleId: String, startedAtMs: Long, source: String = TripSource.REAL) {
        this.startedAtMs = startedAtMs
        continuedLinkedTrip = false
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
                source = source,
            ),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun checkpoint(
        vehicleId: String,
        nowMs: Long,
        distanceKm: Double,
        fuelL: Double,
        maxSpeedKmh: Double,
        idleSeconds: Double,
    ) {
        val id = tripId ?: return
        tripDao.updateRecordedProgress(
            id = id,
            endedAtMs = nowMs,
            distanceKm = distanceKm,
            fuelL = fuelL,
            avgSpeedKmh = avgSpeed(distanceKm, nowMs),
            maxSpeedKmh = maxSpeedKmh,
            idleSeconds = idleSeconds,
        )
    }

    /**
     * Closes the open trip, snapshots its actual cost, and returns the trip id (or null). The
     * recorder forgets the trip even when the write fails: the row then stays open and is closed
     * by [closeLeftovers] on the next run, instead of a later trip overwriting it.
     */
    @Suppress("UNUSED_PARAMETER")
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
        tripId = null
        val cost = TripCost(fuelL = fuelL, pricePerLiterAtTrip = pricePerLiter)
        tripDao.closeRecorded(
            id = id,
            endedAtMs = endedAtMs,
            distanceKm = distanceKm,
            fuelL = fuelL,
            avgSpeedKmh = avgSpeed(distanceKm, endedAtMs),
            maxSpeedKmh = maxSpeedKmh,
            idleSeconds = idleSeconds,
            actualCost = cost.actualCost,
            pricePerLiterAtTrip = cost.pricePerLiterAtTrip,
        )
        return id
    }

    private fun avgSpeed(distanceKm: Double, nowMs: Long): Double {
        val durationMs = (nowMs - startedAtMs).coerceAtLeast(0L)
        return if (durationMs > 0L) distanceKm / (durationMs / 3_600_000.0) else 0.0
    }
}
