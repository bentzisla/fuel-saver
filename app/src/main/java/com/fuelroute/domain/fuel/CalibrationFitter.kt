package com.fuelroute.domain.fuel

import kotlin.math.abs

/**
 * Closed-form fit of a single global fuel correction factor over linked drives.
 *
 * Each pair is `(predictedLiters, actualLiters)` for a drive: the corrected prediction is
 * `predicted * k`, and [fitCorrection] returns the `k` minimizing `Σ (predicted·k − actual)²`,
 * which has the closed form `Σ(p·a) / Σ(p²)`.
 *
 * Scope note (card 18): this is deliberately a single multiplier. A per-segment re-fit of the
 * stop-go weight and congestion factors would need per-route segment geometry that
 * `route_search` does not persist. Keep the fit global until that geometry is stored.
 */
object CalibrationFitter {

    /** Least-squares correction `Σ(p·a) / Σ(p²)`; null when there is nothing to fit. */
    fun fitCorrection(pairs: List<Pair<Double, Double>>): Double? {
        var numerator = 0.0
        var denominator = 0.0
        for ((predicted, actual) in pairs) {
            if (predicted <= 0.0) continue
            numerator += predicted * actual
            denominator += predicted * predicted
        }
        if (denominator <= 0.0) return null
        return numerator / denominator
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