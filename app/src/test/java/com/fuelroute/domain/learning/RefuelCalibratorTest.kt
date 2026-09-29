package com.fuelroute.domain.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefuelCalibratorTest {

    @Test
    fun `exact ratio stays unclamped`() {
        val result = RefuelCalibrator.calibrate(pumpedLitresBetweenFull = 40.0, obdLitresBetweenFull = 38.0)

        assertTrue(result is Calibration.Exact)
        assertEquals(40.0 / 38.0, (result as Calibration.Exact).factor, 1e-9)
    }

    @Test
    fun `low ratio clamps to the minimum`() {
        val result = RefuelCalibrator.calibrate(pumpedLitresBetweenFull = 20.0, obdLitresBetweenFull = 40.0)

        assertEquals(Calibration.Clamped(RefuelCalibrator.MIN_FACTOR), result)
    }

    @Test
    fun `high ratio clamps to the maximum`() {
        val result = RefuelCalibrator.calibrate(pumpedLitresBetweenFull = 80.0, obdLitresBetweenFull = 40.0)

        assertEquals(Calibration.Clamped(RefuelCalibrator.MAX_FACTOR), result)
    }

    @Test
    fun `too little obd fuel is insufficient`() {
        assertEquals(
            Calibration.Insufficient,
            RefuelCalibrator.calibrate(pumpedLitresBetweenFull = 10.0, obdLitresBetweenFull = 0.5),
        )
    }

    @Test
    fun `the ratio adjusts the correction the interval was logged with`() {
        // Regression (oscillation): trips logged with 1.25 already match the pump (ratio 1.0).
        // The old code stored the ratio itself, resetting a correct 1.25 to 1.0.
        val result = RefuelCalibrator.calibrate(40.0, 40.0, activeCorrection = 1.25)
        assertEquals(Calibration.Exact(1.25), result)
    }

    @Test
    fun `repeated tanks converge instead of oscillating`() {
        // The car really burns 1.25x what the raw OBD estimate says.
        val truth = 1.25
        var correction = 1.0
        repeat(4) {
            val rawObd = 40.0 / truth
            val logged = rawObd * correction
            val result = RefuelCalibrator.calibrate(40.0, logged, activeCorrection = correction)
            correction = (result as Calibration.Exact).factor
            assertEquals(truth, correction, 1e-9)
        }
    }

    @Test
    fun `the new absolute factor is what gets clamped`() {
        // Ratio 1.2 is fine on its own, but on top of 1.3 it would exceed the maximum.
        assertEquals(
            Calibration.Clamped(RefuelCalibrator.MAX_FACTOR),
            RefuelCalibrator.calibrate(48.0, 40.0, activeCorrection = 1.3),
        )
        // A ratio of 1.45 on top of 0.9 is a plausible 1.305.
        val exact = RefuelCalibrator.calibrate(43.5, 30.0, activeCorrection = 0.9)
        assertEquals(0.9 * 43.5 / 30.0, (exact as Calibration.Exact).factor, 1e-9)
    }

    @Test
    fun `an unusable stored correction is treated as 1`() {
        assertEquals(Calibration.Exact(40.0 / 38.0), RefuelCalibrator.calibrate(40.0, 38.0, activeCorrection = Double.NaN))
        assertEquals(Calibration.Exact(40.0 / 38.0), RefuelCalibrator.calibrate(40.0, 38.0, activeCorrection = 0.0))
    }

    @Test
    fun `obd covering under half the pumped fuel is not calibrated`() {
        assertEquals(
            Calibration.LowCoverage(pumpedLitres = 45.0, obdLitres = 20.0),
            RefuelCalibrator.calibrate(pumpedLitresBetweenFull = 45.0, obdLitresBetweenFull = 20.0),
        )
    }

    @Test
    fun `no pumped fuel is insufficient`() {
        assertEquals(
            Calibration.Insufficient,
            RefuelCalibrator.calibrate(pumpedLitresBetweenFull = 0.0, obdLitresBetweenFull = 40.0),
        )
    }
}
