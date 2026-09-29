package com.fuelroute.domain.fuel

import com.fuelroute.domain.learning.LearnedCurve
import com.fuelroute.domain.model.SPEED_BIN_WIDTH_KMH
import com.fuelroute.domain.model.SpeedPoint
import kotlin.math.abs

/**
 * Merges the learned (OBD) curve with a fallback curve. Every measured speed bin is shrunk
 * towards the fallback *on its own*, by how many kilometers were measured in that bin:
 *
 *     w_i     = km_i / (km_i + K)                                  (K = 20 km)
 *     value_i = w_i * learned_i + (1 - w_i) * fallback(v_i)        at the bin centre v_i
 *
 * With K = 20 km, 20 measured km give the learned value a 50% weight, 80 km give 80%.
 *
 * The blended curve has a point at every bin centre inside [MIN_SPEED_KMH]..[MAX_SPEED_KMH] plus
 * the fixed grid (10, 20, ... 130 km/h) and the fallback's own vertices. Between those points the
 * ratio `value / fallback` (and the weight `w`) is interpolated linearly, so the fallback's shape
 * is kept between measured bins; a grid point with no bin centre within one bin width is pure
 * fallback (ratio 1, weight 0). Regression: the grid used to be evaluated with the learned curve's
 * nearest-bin confidence, and grid points sit exactly between two bin centres (100 between 97.5
 * and 102.5), so the tie always went to the lower bin and highway km at 100-105 km/h were ignored.
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

    /** The speed range the consumption model works in (see AGENTS.md "Fuel model rules"). */
    const val MIN_SPEED_KMH = 10.0
    const val MAX_SPEED_KMH = 130.0

    /** A grid speed with a measured bin centre closer than this is covered by that measurement. */
    const val COVERAGE_KMH = SPEED_BIN_WIDTH_KMH

    val gridSpeeds: List<Double> = listOf(
        10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0,
        80.0, 90.0, 100.0, 110.0, 120.0, 130.0,
    )

    /**
     * What the blend mixed at one speed: `value = weight * learnedValue + (1 - weight) * fallbackValue`.
     * [learnedValue] is null (and [weight] 0) where no usable measurement contributes. Between bin
     * centres [learnedValue] is the measurement-equivalent that reproduces [value].
     */
    data class Contribution(
        val speedKmh: Double,
        val value: Double,
        val fallbackValue: Double,
        val learnedValue: Double?,
        val weight: Double,
    )

    fun blend(learned: LearnedCurve?, fallback: ConsumptionCurve): ConsumptionCurve {
        if (learned == null || learned.isEmpty) return fallback
        val knots = knots(learned, fallback)
        val speeds = (outputGrid(fallback) + knots.map { it.speedKmh }).distinct().sorted()
        return ConsumptionCurve(speeds.map { SpeedPoint(it, fallback.litersPer100Km(it) * interpolate(knots, it).ratio) })
    }

    /** The blend's inputs at [speedKmh]; consistent with [blend] at every point of its curve. */
    fun contribution(learned: LearnedCurve?, fallback: ConsumptionCurve, speedKmh: Double): Contribution {
        val base = fallback.litersPer100Km(speedKmh)
        if (learned == null || learned.isEmpty) return Contribution(speedKmh, base, base, null, 0.0)
        val at = interpolate(knots(learned, fallback), speedKmh)
        val value = base * at.ratio
        val learnedValue = if (at.weight > 0.0) base + (value - base) / at.weight else null
        return Contribution(speedKmh, value, base, learnedValue, at.weight)
    }

    /** Learned weight `w` the blend gives [speedKmh] (0 where nothing usable was measured). */
    fun weightAt(learned: LearnedCurve?, fallback: ConsumptionCurve, speedKmh: Double): Double {
        if (learned == null || learned.isEmpty) return 0.0
        return interpolate(knots(learned, fallback), speedKmh).weight
    }

    /** True for a speed the blend takes measurements from. */
    fun inBlendRange(speedKmh: Double): Boolean = speedKmh in MIN_SPEED_KMH..MAX_SPEED_KMH

    /**
     * True when a measured point at [speedKmh] with [learnedValue] is used by [blend] against
     * [fallback]: inside the blend range and plausible (see [usesLearnedValue]).
     */
    fun usesLearnedPoint(speedKmh: Double, learnedValue: Double, fallback: ConsumptionCurve): Boolean {
        if (!inBlendRange(speedKmh)) return false
        val base = fallback.litersPer100Km(speedKmh)
        return base > 0.0 && base.isFinite() && usesLearnedValue(learnedValue, base)
    }

    /**
     * False when [learnedValue] is more than [MAX_PLAUSIBLE_RATIO]x or less than
     * [MIN_PLAUSIBLE_RATIO]x [fallbackValue] (a non-positive fallback can't judge a ratio, so
     * anything is left plausible in that edge case - the fallback curve itself is validated
     * elsewhere). A learned value this returns false for is ignored by [blend]; the curve screen
     * uses the same test to draw such a measured point as "not used".
     */
    fun usesLearnedValue(learnedValue: Double, fallbackValue: Double): Boolean {
        if (!learnedValue.isFinite() || learnedValue < 0.0) return false
        if (fallbackValue <= 0.0 || !fallbackValue.isFinite()) return true
        val ratio = learnedValue / fallbackValue
        return ratio in MIN_PLAUSIBLE_RATIO..MAX_PLAUSIBLE_RATIO
    }

    fun weight(distanceKm: Double): Double {
        if (distanceKm <= 0.0) return 0.0
        return distanceKm / (distanceKm + CONFIDENCE_K_KM)
    }

    /** `ratio` = blended / fallback, `weight` = learned weight, at one knot. */
    private data class Knot(val speedKmh: Double, val ratio: Double, val weight: Double)

    private fun outputGrid(fallback: ConsumptionCurve): List<Double> =
        (gridSpeeds + fallback.samples().map { it.speedKmh }.filter { inBlendRange(it) }).distinct().sorted()

    /**
     * One knot per bin centre in the blend range (shrunk individually; a rejected bin is a pure
     * fallback knot), plus a pure fallback knot at every output speed no bin centre covers.
     */
    private fun knots(learned: LearnedCurve, fallback: ConsumptionCurve): List<Knot> {
        val binKnots = learned.points.filter { inBlendRange(it.speedKmh) }.map { point ->
            if (usesLearnedPoint(point.speedKmh, point.litersPer100Km, fallback)) {
                val base = fallback.litersPer100Km(point.speedKmh)
                val w = weight(point.distanceKm)
                Knot(point.speedKmh, (w * point.litersPer100Km + (1.0 - w) * base) / base, w)
            } else {
                Knot(point.speedKmh, 1.0, 0.0)
            }
        }
        val anchors = outputGrid(fallback)
            .filter { speed -> binKnots.none { abs(it.speedKmh - speed) < COVERAGE_KMH } }
            .map { Knot(it, 1.0, 0.0) }
        return (binKnots + anchors).sortedBy { it.speedKmh }
    }

    /** Linear interpolation of ratio and weight between knots, flat beyond the ends. */
    private fun interpolate(knots: List<Knot>, speedKmh: Double): Knot {
        if (knots.isEmpty()) return Knot(speedKmh, 1.0, 0.0)
        val first = knots.first()
        val last = knots.last()
        if (!speedKmh.isFinite() || speedKmh <= first.speedKmh) return first.copy(speedKmh = speedKmh)
        if (speedKmh >= last.speedKmh) return last.copy(speedKmh = speedKmh)
        val upper = knots.indexOfFirst { it.speedKmh >= speedKmh }
        val b = knots[upper]
        val a = knots[upper - 1]
        val span = b.speedKmh - a.speedKmh
        val t = if (span <= 0.0) 0.0 else (speedKmh - a.speedKmh) / span
        return Knot(speedKmh, a.ratio + t * (b.ratio - a.ratio), a.weight + t * (b.weight - a.weight))
    }
}
