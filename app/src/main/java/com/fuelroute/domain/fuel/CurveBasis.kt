package com.fuelroute.domain.fuel

import com.fuelroute.domain.learning.LearnedCurve

/** How much real measured data backs the current learned curve. */
enum class CurveDataQuality { NONE, LOW, MEDIUM, HIGH }

/**
 * Summary of *what the effective curve is based on*: how strongly the learned (OBD)
 * data contributes versus the manual/default fallback, and how trustworthy that
 * learned data is overall.
 *
 * Pure logic so the curve screen and its tests share one definition.
 */
object CurveBasis {

    /** Below this many learned km the data is considered [CurveDataQuality.LOW]. */
    const val LOW_MAX_KM = 20.0

    /** Below this many learned km (but at least [LOW_MAX_KM]) the data is [CurveDataQuality.MEDIUM]. */
    const val MEDIUM_MAX_KM = 100.0

    fun quality(totalLearnedKm: Double): CurveDataQuality = when {
        totalLearnedKm <= 0.0 -> CurveDataQuality.NONE
        totalLearnedKm < LOW_MAX_KM -> CurveDataQuality.LOW
        totalLearnedKm < MEDIUM_MAX_KM -> CurveDataQuality.MEDIUM
        else -> CurveDataQuality.HIGH
    }

    /**
     * Mean learned confidence weight across [speedsKmh], in `0..1`.
     *
     * Each speed contributes `w = km/(km+20)` of its nearest measured bin; speeds with
     * no learned coverage contribute 0. The result approximates the fraction of the
     * effective curve that currently comes from learning rather than the fallback.
     */
    fun learnedShare(learned: LearnedCurve?, speedsKmh: List<Double>): Double {
        if (learned == null || learned.isEmpty || speedsKmh.isEmpty()) return 0.0
        val total = speedsKmh.sumOf { speed ->
            val value = learned.litersPer100Km(speed) ?: return@sumOf 0.0
            if (value.isFinite()) CurveBlender.weight(learned.confidenceKm(speed)) else 0.0
        }
        return (total / speedsKmh.size).coerceIn(0.0, 1.0)
    }
}