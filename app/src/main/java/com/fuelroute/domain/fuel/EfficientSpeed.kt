package com.fuelroute.domain.fuel

import com.fuelroute.domain.learning.LearnedCurve
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The curve screen's recommendation, derived from exactly what the chart draws so the two can
 * never disagree:
 *
 * - [speedKmh]/[litersPer100Km]: the minimum of the effective curve (the thick line, the one route
 *   costs are computed with), marked on the chart;
 * - [rangeFromKmh]..[rangeToKmh]: every speed within [EfficientSpeed.NEAR_OPTIMAL_TOLERANCE] of
 *   that minimum. Real curves have a flat bottom, and a single number hides that 60 and 80 km/h
 *   can cost practically the same; the chart shades this band;
 * - [learnedWeight]: how much measured data backs the curve at [speedKmh] (0..1, the blender's
 *   `w = km/(km+20)`). Near 0 the recommendation is the default/manual curve's shape, not yet the
 *   car's own;
 * - [measuredSpeedKmh]/[measuredL100]: the lowest well-measured learned point, shown only when it
 *   sits noticeably elsewhere, which is what the user sees when their dots slope another way than
 *   the effective line (little data there, so the blend still leans on the fallback);
 * - [measuredVsFallback]: the distance-weighted ratio of the measured points to the fallback
 *   curve at the same speeds. Far from 1 it explains a line that sits well above (or below) the
 *   dots: either the rated consumption in the vehicle profile is off, or the OBD fuel estimate is
 *   (which a refuel calibration corrects). Beyond [CurveBlender]'s plausibility band whole stretches
 *   of measurements are ignored, so the curve cannot move towards them at all.
 */
data class EfficientSpeedInsight(
    val speedKmh: Double,
    val litersPer100Km: Double,
    val rangeFromKmh: Double,
    val rangeToKmh: Double,
    val learnedWeight: Double,
    val measuredSpeedKmh: Double? = null,
    val measuredL100: Double? = null,
    val measuredVsFallback: Double? = null,
) {
    /** True when the measurements and the fallback curve disagree enough to tell the user. */
    val fallbackMismatch: Boolean
        get() = measuredVsFallback?.let {
            it < EfficientSpeed.MISMATCH_LOW_RATIO || it > EfficientSpeed.MISMATCH_HIGH_RATIO
        } ?: false
}

object EfficientSpeed {

    /** Speeds whose consumption is within 3% of the minimum count as "just as efficient". */
    const val NEAR_OPTIMAL_TOLERANCE = 0.03

    /** A learned point needs this much measured distance before it can be called "your optimum". */
    const val MIN_MEASURED_KM = 3.0

    /** The measured optimum is only surfaced when it differs from the curve's by at least this much. */
    const val MEASURED_DIFFERENCE_KMH = 10.0

    /** Below this learned weight at the optimum, the recommendation is the fallback curve's shape. */
    const val FALLBACK_DOMINATED_WEIGHT = 0.3

    /** Measured/fallback ratios outside this band are worth flagging to the user. */
    const val MISMATCH_LOW_RATIO = 0.75
    const val MISMATCH_HIGH_RATIO = 1.35

    /** Too few measured km make the ratio noise, so it is only computed above this. */
    const val MISMATCH_MIN_KM = 10.0

    /** Resolution of the scan over the (piecewise-linear) effective curve. */
    private const val STEP_KMH = 1.0

    /** Range bounds are rounded to this, so the label reads "60-80", not "61-81". */
    private const val RANGE_ROUNDING_KMH = 5.0

    fun analyze(
        effective: ConsumptionCurve,
        learned: LearnedCurve?,
        fallback: ConsumptionCurve,
    ): EfficientSpeedInsight? {
        val from = effective.minSpeedKmh
        val to = effective.maxSpeedKmh
        if (!from.isFinite() || !to.isFinite() || to <= from) return null

        val speeds = generateSequence(from) { it + STEP_KMH }.takeWhile { it <= to + 1e-9 }.toList()
        val values = speeds.map { effective.litersPer100Km(it) }
        val bestIndex = values.indices.minByOrNull { values[it] } ?: return null
        val best = values[bestIndex]
        if (!best.isFinite() || best <= 0.0) return null

        // The contiguous band around the optimum that stays within the tolerance.
        val limit = best * (1.0 + NEAR_OPTIMAL_TOLERANCE)
        var lo = bestIndex
        while (lo > 0 && values[lo - 1] <= limit) lo--
        var hi = bestIndex
        while (hi < values.lastIndex && values[hi + 1] <= limit) hi++

        val bestSpeed = speeds[bestIndex]
        val weight = learned?.let { CurveBlender.weight(it.confidenceKm(bestSpeed)) } ?: 0.0

        val measured = learned?.points
            ?.filter { it.distanceKm >= MIN_MEASURED_KM }
            ?.filter { CurveBlender.usesLearnedValue(it.litersPer100Km, fallback.litersPer100Km(it.speedKmh)) }
            ?.minByOrNull { it.litersPer100Km }
            ?.takeIf { abs(it.speedKmh - bestSpeed) >= MEASURED_DIFFERENCE_KMH }

        return EfficientSpeedInsight(
            speedKmh = bestSpeed,
            litersPer100Km = best,
            rangeFromKmh = roundToStep(speeds[lo]).coerceIn(from, bestSpeed),
            rangeToKmh = roundToStep(speeds[hi]).coerceIn(bestSpeed, to),
            learnedWeight = weight,
            measuredSpeedKmh = measured?.speedKmh,
            measuredL100 = measured?.litersPer100Km,
            measuredVsFallback = learned?.let { measuredVsFallback(it, fallback) },
        )
    }

    private fun roundToStep(speedKmh: Double): Double =
        (speedKmh / RANGE_ROUNDING_KMH).roundToLong() * RANGE_ROUNDING_KMH

    /**
     * Distance-weighted mean of `learned / fallback` over every plotted learned point (used by the
     * blend or not), or null below [MISMATCH_MIN_KM] of measured distance.
     */
    fun measuredVsFallback(learned: LearnedCurve, fallback: ConsumptionCurve): Double? {
        val points = learned.points.filter { fallback.litersPer100Km(it.speedKmh) > 0.0 }
        val km = points.sumOf { it.distanceKm }
        if (km < MISMATCH_MIN_KM) return null
        return points.sumOf { it.distanceKm * it.litersPer100Km / fallback.litersPer100Km(it.speedKmh) } / km
    }
}
