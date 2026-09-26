package com.fuelroute.domain.fuel

import com.fuelroute.domain.learning.LearnedCurve
import com.fuelroute.domain.model.SpeedPoint

/**
 * Merges the learned (OBD) curve with a fallback curve, weighting each speed by
 * how many kilometers were actually measured in that bin:
 *
 *     w = km / (km + K)
 *
 * With K = 20 km, 20 measured km give the learned value a 50% weight, 80 km give 80%.
 * Speeds with no measurements simply use the fallback curve.
 */
object CurveBlender {

    const val CONFIDENCE_K_KM = 20.0

    /**
     * Plausibility band for a learned value against the rated/default (fallback) curve at the
     * same speed. A learned point far outside this band (a bad OBD reading - e.g. an
     * underestimated MAF/speed-density fuel rate, or a wrong displacement) is excluded from the
     * blend entirely rather than dragging the effective curve towards an implausible number;
     * see the bug report investigation (under-4.5L/100km uphill estimate).
     */
    const val MIN_PLAUSIBLE_RATIO = 0.5
    const val MAX_PLAUSIBLE_RATIO = 3.0

    private val blendSpeeds = listOf(
        10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0,
        80.0, 90.0, 100.0, 110.0, 120.0, 130.0,
    )

    fun blend(learned: LearnedCurve?, fallback: ConsumptionCurve): ConsumptionCurve {
        if (learned == null || learned.isEmpty) return fallback

        val points = blendSpeeds.map { speed ->
            val fallbackValue = fallback.litersPer100Km(speed)
            val learnedValue = learned.litersPer100Km(speed)
            val value = if (learnedValue == null || !isPlausible(learnedValue, fallbackValue)) {
                fallbackValue
            } else {
                val w = weight(learned.confidenceKm(speed))
                w * learnedValue + (1.0 - w) * fallbackValue
            }
            SpeedPoint(speed, value)
        }
        return ConsumptionCurve(points)
    }

    /**
     * False when [learnedValue] is more than [MAX_PLAUSIBLE_RATIO]x or less than
     * [MIN_PLAUSIBLE_RATIO]x [fallbackValue] (a non-positive fallback can't judge a ratio, so
     * anything is left plausible in that edge case - the fallback curve itself is validated
     * elsewhere).
     */
    private fun isPlausible(learnedValue: Double, fallbackValue: Double): Boolean {
        if (!learnedValue.isFinite() || learnedValue < 0.0) return false
        if (fallbackValue <= 0.0 || !fallbackValue.isFinite()) return true
        val ratio = learnedValue / fallbackValue
        return ratio in MIN_PLAUSIBLE_RATIO..MAX_PLAUSIBLE_RATIO
    }

    fun weight(distanceKm: Double): Double {
        if (distanceKm <= 0.0) return 0.0
        return distanceKm / (distanceKm + CONFIDENCE_K_KM)
    }
}