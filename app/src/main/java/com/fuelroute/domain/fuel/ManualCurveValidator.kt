package com.fuelroute.domain.fuel

import com.fuelroute.domain.model.SpeedPoint

/** Outcome of validating a manually entered consumption curve. */
sealed interface ManualCurveResult {
    /** No usable points were supplied: the stored manual curve should be cleared. */
    object Cleared : ManualCurveResult

    /** A usable curve: sorted ascending by speed, de-duplicated and within range. */
    data class Valid(val points: List<SpeedPoint>) : ManualCurveResult

    /** The input cannot form a valid curve; [reason] says why. */
    data class Invalid(val reason: ManualCurveError) : ManualCurveResult
}

/** Why a [ManualCurveResult.Invalid] was returned, so the UI can show a specific message. */
enum class ManualCurveError {
    /** Fewer than [ManualCurveValidator.MIN_POINTS] usable points remained after cleaning. */
    TOO_FEW_POINTS,
}

/**
 * Normalizes and validates a user-entered speed -> L/100km curve.
 *
 * Pure Kotlin (no Android imports) so it is unit-testable on the JVM and shared by the UI.
 *
 * Normalization: drops points whose speed or consumption is non-finite or non-positive,
 * clamps speeds into `[MIN_SPEED_KMH, MAX_SPEED_KMH]`, sorts by speed, and keeps the last
 * value entered for a duplicated speed.
 */
object ManualCurveValidator {

    /** Lowest accepted speed, in km/h. */
    const val MIN_SPEED_KMH = 0.0

    /** Highest accepted speed, in km/h (matches the curve chart's X axis). */
    const val MAX_SPEED_KMH = 140.0

    /** A curve needs at least this many points to be usable. */
    const val MIN_POINTS = 2

    /** Cleans [points]; see the object docs for the exact rules. */
    fun normalize(points: List<SpeedPoint>): List<SpeedPoint> {
        val cleaned = points
            .filter { point ->
                point.speedKmh.isFinite() && point.speedKmh > 0.0 &&
                    point.litersPer100Km.isFinite() && point.litersPer100Km > 0.0
            }
            .map { point ->
                point.copy(speedKmh = point.speedKmh.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH))
            }
            .sortedBy { it.speedKmh }

        // Last-write-wins per speed (the map preserves the ascending order).
        val bySpeed = LinkedHashMap<Double, SpeedPoint>(cleaned.size)
        cleaned.forEach { bySpeed[it.speedKmh] = it }
        return bySpeed.values.toList()
    }

    /** Normalizes [points] and classifies the outcome for the caller/UI. */
    fun validate(points: List<SpeedPoint>): ManualCurveResult {
        if (points.isEmpty()) return ManualCurveResult.Cleared
        val normalized = normalize(points)
        return if (normalized.size >= MIN_POINTS) {
            ManualCurveResult.Valid(normalized)
        } else {
            ManualCurveResult.Invalid(ManualCurveError.TOO_FEW_POINTS)
        }
    }

    /** The cleaned curve, or `null` when [points] is empty or does not form a valid curve. */
    fun normalizedOrNull(points: List<SpeedPoint>): List<SpeedPoint>? =
        when (val result = validate(points)) {
            is ManualCurveResult.Valid -> result.points
            ManualCurveResult.Cleared,
            is ManualCurveResult.Invalid,
            -> null
        }
}