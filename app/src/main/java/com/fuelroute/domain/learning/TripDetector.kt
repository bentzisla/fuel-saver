package com.fuelroute.domain.learning

import com.fuelroute.domain.model.ObdSample

/**
 * Decides when a "trip" starts and ends from the OBD sample stream, independent
 * of any routing. A trip starts on the first sample with the engine running and
 * the car moving, and ends after [stopAfterIdleMs] without movement.
 */
class TripDetector(
    private val stopAfterIdleMs: Long = 180_000L,
) {

    var startedAtMs: Long? = null
        private set

    var lastMovementMs: Long? = null
        private set

    val isActive: Boolean
        get() = startedAtMs != null

    fun onSample(sample: ObdSample): TripTransition {
        val moving = sample.engineRunning && (sample.speedKmh ?: 0.0) > 1.0

        if (!isActive) {
            if (moving) {
                startedAtMs = sample.timestampMs
                lastMovementMs = sample.timestampMs
                return TripTransition.Started
            }
            return TripTransition.None
        }

        if (moving) {
            lastMovementMs = sample.timestampMs
            return TripTransition.None
        }

        val last = lastMovementMs
        if (last != null && sample.timestampMs - last >= stopAfterIdleMs) {
            val start = startedAtMs ?: return TripTransition.None
            startedAtMs = null
            lastMovementMs = null
            return TripTransition.Ended(start, sample.timestampMs)
        }

        return TripTransition.None
    }

    fun forceEnd(timestampMs: Long): TripTransition {
        val start = startedAtMs ?: return TripTransition.None
        startedAtMs = null
        lastMovementMs = null
        return TripTransition.Ended(start, timestampMs)
    }

    sealed interface TripTransition {
        data object None : TripTransition
        data object Started : TripTransition
        data class Ended(val startedAtMs: Long, val endedAtMs: Long) : TripTransition
    }
}