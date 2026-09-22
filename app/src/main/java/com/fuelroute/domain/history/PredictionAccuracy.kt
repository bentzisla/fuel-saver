package com.fuelroute.domain.history

import kotlin.math.abs

/**
 * One drive's predicted values next to what the OBD actually measured. Only the cost
 * fields are required; the liter/minute fields are carried for richer UI.
 */
data class DriveOutcome(
    val predictedCost: Double,
    val actualCost: Double,
    val predictedLiters: Double = 0.0,
    val actualLiters: Double = 0.0,
    val predictedMinutes: Double = 0.0,
    val actualMinutes: Double = 0.0,
)

/** Pure accuracy math for the "how good is our prediction?" header. */
object PredictionAccuracy {

    /**
     * Signed prediction error as a percentage of the prediction:
     * `(actual - predicted) / predicted * 100`. Null when [predicted] is not usable
     * (<= 0), so callers can skip those rows instead of dividing by zero.
     */
    fun errorPct(predicted: Double, actual: Double): Double? =
        if (!predicted.isFinite() || !actual.isFinite() || predicted <= 0.0) {
            null
        } else {
            (actual - predicted) / predicted * 100.0
        }

    /**
     * Mean absolute percentage error over [outcomes], ignoring rows where [errorPct]
     * is not usable. Returns null when there is nothing to average.
     */
    fun mape(outcomes: List<DriveOutcome>): Double? {
        val errors = outcomes.mapNotNull { errorPct(it.predictedCost, it.actualCost)?.let(::abs) }
        if (errors.isEmpty()) return null
        return errors.average()
    }
}