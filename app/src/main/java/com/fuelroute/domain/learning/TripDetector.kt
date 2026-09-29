package com.fuelroute.domain.learning

import com.fuelroute.domain.model.ObdSample

/**
 * Decides when a "trip" starts and ends from the OBD sample stream, independent of any routing
 * (PLAN.md 5.5).
 *
 *  - Starts on the first moving sample (speed > 1 km/h).
 *  - Ends after [stopAfterEngineOffMs] without a running engine (RPM missing/0) and without
 *    movement. A standstill with the engine running (queue, drive-through, warm-up) keeps the
 *    trip open, so its idle fuel stays with the drive instead of being dropped or split off.
 *
 * The end timestamp is the last sample that still showed the engine running or the car moving,
 * so the engine-off wait is not billed to the trip.
 */
class TripDetector(
    private val stopAfterEngineOffMs: Long = DEFAULT_STOP_AFTER_ENGINE_OFF_MS,
) {

    var startedAtMs: Long? = null
        private set

    /** Last sample with the engine running or the car moving, while a trip is active. */
    var lastActiveMs: Long? = null
        private set

    val isActive: Boolean
        get() = startedAtMs != null

    fun onSample(sample: ObdSample): TripTransition {
        val moving = (sample.speedKmh ?: 0.0) > MOVING_KMH
        val alive = moving || sample.engineRunning

        if (!isActive) {
            if (moving) {
                startedAtMs = sample.timestampMs
                lastActiveMs = sample.timestampMs
                return TripTransition.Started
            }
            return TripTransition.None
        }

        if (alive) {
            lastActiveMs = sample.timestampMs
            return TripTransition.None
        }

        val last = lastActiveMs
        if (last != null && sample.timestampMs - last >= stopAfterEngineOffMs) {
            val start = startedAtMs ?: return TripTransition.None
            startedAtMs = null
            lastActiveMs = null
            return TripTransition.Ended(start, last)
        }

        return TripTransition.None
    }

    fun forceEnd(timestampMs: Long): TripTransition {
        val start = startedAtMs ?: return TripTransition.None
        startedAtMs = null
        lastActiveMs = null
        return TripTransition.Ended(start, maxOf(start, timestampMs))
    }

    sealed interface TripTransition {
        data object None : TripTransition
        data object Started : TripTransition
        data class Ended(val startedAtMs: Long, val endedAtMs: Long) : TripTransition
    }

    companion object {
        /** PLAN.md 5.5: a trip ends after 3 minutes of engine off. */
        const val DEFAULT_STOP_AFTER_ENGINE_OFF_MS = 180_000L
        private const val MOVING_KMH = 1.0
    }
}
