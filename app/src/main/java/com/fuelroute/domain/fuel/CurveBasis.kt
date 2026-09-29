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

    /** Every whole km/h of the blend range: the default sampling for [learnedShare]. */
    val UNIFORM_SPEEDS: List<Double> =
        generateSequence(CurveBlender.MIN_SPEED_KMH) { it + 1.0 }
            .takeWhile { it <= CurveBlender.MAX_SPEED_KMH + 1e-9 }
            .toList()

    /**
     * Mean learned weight across [speedsKmh], in `0..1`: exactly the `w` [CurveBlender.blend]
     * gives each speed against [fallback] (per-bin `km/(km+20)`, interpolated between bin centres,
     * 0 where nothing usable was measured). With the default uniform sampling the result is the
     * fraction of the effective curve that currently comes from learning rather than the fallback;
     * pass a uniform sampling, not the effective curve's vertices (those cluster around the data).
     */
    fun learnedShare(
        learned: LearnedCurve?,
        fallback: ConsumptionCurve,
        speedsKmh: List<Double> = UNIFORM_SPEEDS,
    ): Double {
        if (learned == null || learned.isEmpty || speedsKmh.isEmpty()) return 0.0
        val total = speedsKmh.sumOf { CurveBlender.weightAt(learned, fallback, it) }
        return (total / speedsKmh.size).coerceIn(0.0, 1.0)
    }
}
