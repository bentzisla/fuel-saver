package com.fuelroute.domain.fuel

import kotlin.math.abs

/**
 * Closed-form fit of a single global fuel correction factor over linked drives.
 *
 * Each pair is `(predictedLiters, actualLiters)` for a drive: the corrected prediction is
 * `predicted * k`, and [fitCorrection] returns the `k` minimizing `Σ (predicted·k − actual)²`,
 * which has the closed form `Σ(p·a) / Σ(p²)`.
 *
 * Callers whose `predictedLiters` are already corrected by the active override must run them
 * through [uncorrectedPairs] first; otherwise the fit double-counts that correction.
 *
 * Scope note (card 18): this is deliberately a single multiplier. A per-segment re-fit of the
 * stop-go weight and congestion factors would need per-route segment geometry that
 * `route_search` does not persist. Keep the fit global until that geometry is stored.
 */
object CalibrationFitter {

    /**
     * Plausibility band for a fitted global correction (bug report investigation: an unclamped
     * fit over a bad/short drive history could push the correction arbitrarily low and silently
     * halve every predicted route cost). A single tank-to-tank drive can genuinely be off by
     * this much, but the model should never trust a fit further than this without more data.
     */
    const val MIN_CORRECTION = 0.75
    const val MAX_CORRECTION = 1.5

    /**
     * Per-pair outlier band on `actual / predicted` (bug report investigation: a mis-linked
     * drive - e.g. a demo/simulated ride, or a short real trip linked to a long searched route -
     * can carry an `actual` far smaller than any real calibration error would produce; one such
     * pair among otherwise-good data must not drag the whole fit down with it).
     */
    const val MIN_PLAUSIBLE_RATIO = 0.4
    const val MAX_PLAUSIBLE_RATIO = 2.5

    /** Below this many *plausible* pairs, a fit is too noisy to trust at all. */
    const val MIN_PAIRS = 3

    /**
     * Least-squares correction `Σ(p·a) / Σ(p²)` over the pairs whose `actual/predicted` ratio
     * falls inside [MIN_PLAUSIBLE_RATIO]..[MAX_PLAUSIBLE_RATIO], clamped to
     * [MIN_CORRECTION]..[MAX_CORRECTION]; null when fewer than [MIN_PAIRS] pairs remain.
     */
    fun fitCorrection(pairs: List<Pair<Double, Double>>): Double? {
        val usable = pairs.filter { (predicted, actual) ->
            predicted.isFinite() && predicted > 0.0 && actual.isFinite() && actual > 0.0 &&
                (actual / predicted) in MIN_PLAUSIBLE_RATIO..MAX_PLAUSIBLE_RATIO
        }
        if (usable.size < MIN_PAIRS) return null

        var numerator = 0.0
        var denominator = 0.0
        for ((predicted, actual) in usable) {
            numerator += predicted * actual
            denominator += predicted * predicted
        }
        if (denominator <= 0.0) return null
        val raw = numerator / denominator
        if (!raw.isFinite()) return null
        return raw.coerceIn(MIN_CORRECTION, MAX_CORRECTION)
    }

    /**
     * Divides [currentCorrection] back out of every [Pair]'s predicted liters so the
     * [fitCorrection] input is the *uncorrected* model output.
     *
     * Stored `predictedLiters` already include the correction that was active at search time
     * (the `FuelModel` is built with `overrides.fuelCorrection`), so fitting an absolute factor
     * directly against them would fold the current correction in twice and the calibrator would
     * oscillate away from a good value. A non-finite/non-positive [currentCorrection] means
     * "no scaling to undo" and is returned unchanged.
     */
    fun uncorrectedPairs(
        pairs: List<Pair<Double, Double>>,
        currentCorrection: Double,
    ): List<Pair<Double, Double>> {
        if (!currentCorrection.isFinite() || currentCorrection <= 0.0 || currentCorrection == 1.0) {
            return pairs
        }
        return pairs.map { (predicted, actual) -> (predicted / currentCorrection) to actual }
    }

    /**
     * Mean absolute percentage error of `predicted * correction` against `actual`, using the
     * same convention as `PredictionAccuracy.mape` (error relative to the corrected prediction).
     * Rows with a non-positive prediction are skipped; 0.0 when there is nothing to score.
     */
    fun suggestedMape(pairs: List<Pair<Double, Double>>, correction: Double): Double {
        if (correction <= 0.0) return 0.0
        val errors = pairs.mapNotNull { (predicted, actual) ->
            if (predicted <= 0.0) return@mapNotNull null
            abs(actual - predicted * correction) / (predicted * correction) * 100.0
        }
        if (errors.isEmpty()) return 0.0
        return errors.average()
    }
}