package com.fuelroute.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationAccuracyBandsTest {

    @Test
    fun `bands by mape at fixed pair count`() {
        assertEquals(
            CalibrationAccuracy.HIGH,
            CalibrationAccuracyBands.from(0.0, pairCount = 5),
        )
        assertEquals(
            CalibrationAccuracy.HIGH,
            CalibrationAccuracyBands.from(CalibrationAccuracyBands.HIGH_MAX_MAPE, pairCount = 5),
        )
        assertEquals(
            CalibrationAccuracy.MEDIUM,
            CalibrationAccuracyBands.from(CalibrationAccuracyBands.HIGH_MAX_MAPE + 0.01, pairCount = 5),
        )
        assertEquals(
            CalibrationAccuracy.MEDIUM,
            CalibrationAccuracyBands.from(CalibrationAccuracyBands.MEDIUM_MAX_MAPE, pairCount = 5),
        )
        assertEquals(
            CalibrationAccuracy.LOW,
            CalibrationAccuracyBands.from(CalibrationAccuracyBands.MEDIUM_MAX_MAPE + 0.01, pairCount = 5),
        )
    }

    @Test
    fun `too few drives is none regardless of mape`() {
        assertEquals(
            CalibrationAccuracy.NONE,
            CalibrationAccuracyBands.from(1.0, pairCount = CalibrationAccuracyBands.MIN_PAIRS - 1),
        )
    }

    @Test
    fun `missing or invalid mape is none`() {
        assertEquals(CalibrationAccuracy.NONE, CalibrationAccuracyBands.from(null, pairCount = 5))
        assertEquals(
            CalibrationAccuracy.NONE,
            CalibrationAccuracyBands.from(Double.NaN, pairCount = 5),
        )
        assertEquals(
            CalibrationAccuracy.NONE,
            CalibrationAccuracyBands.from(Double.POSITIVE_INFINITY, pairCount = 5),
        )
        assertEquals(CalibrationAccuracy.NONE, CalibrationAccuracyBands.from(-1.0, pairCount = 5))
    }
}