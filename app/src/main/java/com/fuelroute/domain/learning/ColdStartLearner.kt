package com.fuelroute.domain.learning

import com.fuelroute.domain.fuel.ConsumptionCurve
import com.fuelroute.domain.fuel.ModelConstants

/**
 * Accumulates the extra fuel burned while the engine is warming up.
 *
 * While the coolant is below [coldThresholdC] the warm curve underpredicts real
 * consumption, so every sample contributes
 *
 *     fuelL - warmCurve(v) * distanceKm / 100
 *
 * to a per-trip total. [endTrip] hands that total to the persistence layer, which keeps a
 * running mean per vehicle (see [ColdStartStats]). The learner is pure: it only sees
 * numbers and never touches Android or the database.
 *
 * Samples are rejected under the same guard as [SpeedBinAggregator]: a gap longer than
 * [maxSampleGapSeconds] cannot be attributed to the cold phase, and a missing coolant reading
 * is treated as warm (no evidence of a cold start). The accumulated extra is clamped at >= 0 so
 * a warm-curve overshoot can never produce a negative "saving".
 */
class ColdStartLearner(
    private val warmCurve: ConsumptionCurve,
    private val coldThresholdC: Double = COLD_THRESHOLD_C,
    private val maxSampleGapSeconds: Double = 2.0,
) {

    private var accumulatedExtraL = 0.0
    private var coldSamples = 0

    /** Extra liters accumulated since the last [endTrip] call, never negative. */
    val extraL: Double
        get() = accumulatedExtraL.coerceAtLeast(0.0)

    /** Whether any cold sample has been folded into the current trip. */
    val hasColdSamples: Boolean
        get() = coldSamples > 0

    /**
     * Folds one sample into the current trip. Returns true when the sample was cold and
     * therefore counted.
     */
    fun onSample(
        speedKmh: Double?,
        fuelRateLph: Double?,
        dtSec: Double,
        coolantTempC: Double?,
    ): Boolean {
        if (coolantTempC == null || coolantTempC >= coldThresholdC) return false
        if (!dtSec.isFinite() || dtSec <= 0.0 || dtSec > maxSampleGapSeconds) return false
        val rate = fuelRateLph ?: return false
        if (!rate.isFinite() || rate < 0.0) return false

        val speed = (speedKmh ?: 0.0).takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val dtHours = dtSec / 3600.0
        val fuelL = rate * dtHours
        val distanceKm = speed * dtHours
        val warmL = warmCurve.litersPer100Km(speed) * distanceKm / 100.0
        accumulatedExtraL += fuelL - warmL
        coldSamples++
        return true
    }

    /** Returns the extra liters for the trip that just ended (>= 0) and resets for the next one. */
    fun endTrip(): Double {
        val extra = accumulatedExtraL.coerceAtLeast(0.0)
        accumulatedExtraL = 0.0
        coldSamples = 0
        return extra
    }

    companion object {
        const val COLD_THRESHOLD_C = 60.0
    }
}

/** Running mean of the extra fuel burned per cold start, across trips. */
data class ColdStartStats(
    val meanExtraL: Double,
    val count: Int,
) {
    /** The value to use for routing: the learned mean once trusted, else the default. */
    val effectiveExtraL: Double
        get() = if (count >= MIN_COLD_STARTS) meanExtraL else ModelConstants.COLD_START_DEFAULT_L

    companion object {
        /** How many cold starts are needed before the learned mean replaces the default. */
        const val MIN_COLD_STARTS = 3

        fun initial(): ColdStartStats = ColdStartStats(ModelConstants.COLD_START_DEFAULT_L, count = 0)

        /** Folds one trip's [tripExtraL] into the running mean, clamping to >= 0. */
        fun runningMean(current: ColdStartStats, tripExtraL: Double): ColdStartStats {
            val count = current.count + 1
            val clamped = tripExtraL.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            val mean = (current.meanExtraL * current.count + clamped) / count
            return ColdStartStats(meanExtraL = mean, count = count)
        }
    }
}