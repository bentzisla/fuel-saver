package com.fuelroute.domain.fuel

import com.fuelroute.domain.learning.LearnedCurve

/**
 * Anchors the level of the default curve to what the car actually measured.
 *
 * The default curve is a fixed shape times one number, the rated combined consumption from the
 * vehicle profile. When that number is off (field case: 10.0 L/100 typed in for a car whose 284
 * measured km average 7.7 L/100), every blended value is dragged towards the wrong level: 44 km
 * measured at 5.5 L/100 still came out as 7.2 in the curve in use, and measurements more than 2x
 * below the inflated base were rejected as implausible altogether.
 *
 * The default's *shape* stays; its *level* is scaled by the distance-weighted ratio of measured to
 * default consumption at the same speeds, shrunk towards 1 while little has been measured
 * (`km / (km + CONFIDENCE_K_KM)`) and bounded to [MIN_FACTOR]..[MAX_FACTOR], so a handful of bad
 * readings cannot move the whole curve far. A manual curve is the user's explicit statement and is
 * never rescaled.
 */
object BaseLevel {

    /** Measured km at which the level correction reaches half its full strength. */
    const val CONFIDENCE_K_KM = 50.0

    /** Bounds of the level factor, whatever the data says. */
    const val MIN_FACTOR = 0.6
    const val MAX_FACTOR = 1.5

    /** The level factor for [base] given [learned], or 1.0 when there is nothing to anchor to. */
    fun factor(learned: LearnedCurve?, base: ConsumptionCurve): Double {
        val points = learned?.points.orEmpty().filter { base.litersPer100Km(it.speedKmh) > 0.0 }
        val km = points.sumOf { it.distanceKm }
        if (km <= 0.0) return 1.0
        val ratio = points.sumOf { it.distanceKm * it.litersPer100Km / base.litersPer100Km(it.speedKmh) } / km
        if (!ratio.isFinite()) return 1.0
        val confidence = km / (km + CONFIDENCE_K_KM)
        return (1.0 + confidence * (ratio - 1.0)).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /** [base] with its level scaled by [factor]. */
    fun anchor(base: ConsumptionCurve, factor: Double): ConsumptionCurve =
        if (factor == 1.0) base else ConsumptionCurve(base.samples().map { it.copy(litersPer100Km = it.litersPer100Km * factor) })

    /**
     * The fallback the blend should use: the manual curve as typed, else the default anchored to
     * the measured level.
     */
    fun fallback(learned: LearnedCurve?, manual: ConsumptionCurve?, default: ConsumptionCurve): ConsumptionCurve =
        manual ?: anchor(default, factor(learned, default))
}
