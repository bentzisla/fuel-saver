package com.fuelroute.domain.learning

/**
 * Result of comparing the fuel pumped at a full refuel with the fuel the OBD measured
 * since the previous full refuel. [Exact.factor] / [Clamped.factor] are the new *absolute*
 * correction to store for the OBD fuel-rate readings.
 */
sealed interface Calibration {

    /** Not enough OBD fuel in the interval to calibrate reliably. */
    data object Insufficient : Calibration

    /**
     * The OBD logged less than [RefuelCalibrator.MIN_COVERAGE] of the pumped fuel: most of the
     * tank was driven without the dongle logging, so the ratio says nothing about the fuel rate.
     * Never applied.
     */
    data class LowCoverage(val pumpedLitres: Double, val obdLitres: Double) : Calibration

    /** The new correction is inside the trusted range. */
    data class Exact(val factor: Double) : Calibration

    /**
     * The new correction fell outside [RefuelCalibrator.MIN_FACTOR]..[RefuelCalibrator.MAX_FACTOR]
     * and got pinned to the nearest edge. Usually means drives without the dongle (or a missed
     * full fill), so it is only applied when the user confirms.
     */
    data class Clamped(val factor: Double) : Calibration
}

/**
 * Tank-to-tank calibration of the OBD fuel rate. Pure and unit-testable; the UI persists
 * [Calibration.Exact] and asks before persisting [Calibration.Clamped].
 *
 * The OBD litres being compared were logged *with* the correction active during the interval
 * (`ObdSampleProcessor` multiplies every rate by it), so `pumped / obd` is a *relative*
 * adjustment: `new = active * pumped / obd`. Regression: storing `pumped / obd` as the absolute
 * factor made a correct 1.25 fall back to ~1.0 on the next tank and back again (oscillation).
 */
object RefuelCalibrator {

    const val MIN_FACTOR = 0.7
    const val MAX_FACTOR = 1.4

    /** Below this many OBD liters the ratio is too noisy to trust. */
    const val MIN_OBD_LITRES = 1.0

    /** Below this share of the pumped fuel the OBD cannot have logged most of the driving. */
    const val MIN_COVERAGE = 0.5

    /**
     * @param activeCorrection the fuel-rate correction the interval's trips were logged with;
     *   a non-finite/non-positive value is treated as 1.0 and anything else is bounded to
     *   [MIN_FACTOR]..[MAX_FACTOR] like every stored factor.
     */
    fun calibrate(
        pumpedLitresBetweenFull: Double,
        obdLitresBetweenFull: Double,
        activeCorrection: Double = 1.0,
    ): Calibration {
        if (!pumpedLitresBetweenFull.isFinite() || pumpedLitresBetweenFull <= 0.0) return Calibration.Insufficient
        if (!obdLitresBetweenFull.isFinite() || obdLitresBetweenFull < MIN_OBD_LITRES) return Calibration.Insufficient
        if (obdLitresBetweenFull < MIN_COVERAGE * pumpedLitresBetweenFull) {
            return Calibration.LowCoverage(pumpedLitresBetweenFull, obdLitresBetweenFull)
        }

        val raw = sanitize(activeCorrection) * pumpedLitresBetweenFull / obdLitresBetweenFull
        if (!raw.isFinite() || raw <= 0.0) return Calibration.Insufficient

        return if (raw < MIN_FACTOR || raw > MAX_FACTOR) {
            Calibration.Clamped(raw.coerceIn(MIN_FACTOR, MAX_FACTOR))
        } else {
            Calibration.Exact(raw)
        }
    }

    /** A stored correction as the calibrator trusts it (1.0 when unusable). */
    fun sanitize(correction: Double): Double =
        correction.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(MIN_FACTOR, MAX_FACTOR) ?: 1.0
}
