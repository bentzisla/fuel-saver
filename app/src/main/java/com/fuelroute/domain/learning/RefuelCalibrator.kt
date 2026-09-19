package com.fuelroute.domain.learning

/**
 * Result of comparing the fuel pumped at a full refuel with the fuel the OBD measured
 * since the previous full refuel. The ratio is the multiplicative correction applied to
 * the OBD fuel-rate readings.
 */
sealed interface Calibration {

    /** Not enough OBD fuel in the interval to calibrate reliably. */
    data object Insufficient : Calibration

    /** The ratio is inside the trusted range. */
    data class Exact(val factor: Double) : Calibration

    /** The ratio fell outside [RefuelCalibrator.MIN_FACTOR]..[RefuelCalibrator.MAX_FACTOR]
     *  and got pinned to the nearest edge. */
    data class Clamped(val factor: Double) : Calibration
}

/**
 * Tank-to-tank calibration of the OBD fuel rate. Pure and unit-testable; the UI decides
 * whether to persist [Calibration.Exact] / [Calibration.Clamped] and how to warn about a
 * clamped result.
 */
object RefuelCalibrator {

    const val MIN_FACTOR = 0.7
    const val MAX_FACTOR = 1.4

    /** Below this many OBD liters the ratio is too noisy to trust. */
    const val MIN_OBD_LITRES = 1.0

    fun calibrate(pumpedLitresBetweenFull: Double, obdLitresBetweenFull: Double): Calibration {
        if (pumpedLitresBetweenFull <= 0.0) return Calibration.Insufficient
        if (obdLitresBetweenFull < MIN_OBD_LITRES) return Calibration.Insufficient

        val raw = pumpedLitresBetweenFull / obdLitresBetweenFull
        if (!raw.isFinite() || raw <= 0.0) return Calibration.Insufficient

        return if (raw < MIN_FACTOR || raw > MAX_FACTOR) {
            Calibration.Clamped(raw.coerceIn(MIN_FACTOR, MAX_FACTOR))
        } else {
            Calibration.Exact(raw)
        }
    }
}
