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
    fun `no pumped fuel is insufficient`() {
        assertEquals(
            Calibration.Insufficient,
            RefuelCalibrator.calibrate(pumpedLitresBetweenFull = 0.0, obdLitresBetweenFull = 40.0),
        )
    }
}
