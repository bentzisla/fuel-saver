package com.fuelroute.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PredictionAccuracyTest {

    private val delta = 1e-9

    @Test
    fun `errorPct is signed relative to the prediction`() {
        assertEquals(10.0, PredictionAccuracy.errorPct(100.0, 110.0)!!, delta)
        assertEquals(-25.0, PredictionAccuracy.errorPct(100.0, 75.0)!!, delta)
        assertEquals(0.0, PredictionAccuracy.errorPct(50.0, 50.0)!!, delta)
    }

    @Test
    fun `errorPct guards against a non-positive prediction`() {
        assertNull(PredictionAccuracy.errorPct(0.0, 42.0))
        assertNull(PredictionAccuracy.errorPct(-5.0, 42.0))
    }

    @Test
    fun `mape averages the absolute errors`() {
        val outcomes = listOf(
            DriveOutcome(predictedCost = 100.0, actualCost = 110.0),
            DriveOutcome(predictedCost = 200.0, actualCost = 160.0),
        )

        assertEquals(15.0, PredictionAccuracy.mape(outcomes)!!, delta)
    }

    @Test
    fun `mape ignores unusable rows`() {
        val outcomes = listOf(
            DriveOutcome(predictedCost = 0.0, actualCost = 50.0),
            DriveOutcome(predictedCost = 100.0, actualCost = 120.0),
        )

        assertEquals(20.0, PredictionAccuracy.mape(outcomes)!!, delta)
    }

    @Test
    fun `mape of an empty list is null`() {
        assertNull(PredictionAccuracy.mape(emptyList()))
    }

    @Test
    fun `mape of only unusable rows is null`() {
        assertNull(PredictionAccuracy.mape(listOf(DriveOutcome(predictedCost = 0.0, actualCost = 10.0))))
    }
}