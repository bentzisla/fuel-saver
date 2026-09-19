package com.fuelroute.domain.learning

import com.fuelroute.domain.fuel.ConsumptionCurve
import com.fuelroute.domain.model.SpeedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColdStartLearnerTest {

    private val warmCurve = ConsumptionCurve(listOf(SpeedPoint(0.0, 8.0), SpeedPoint(130.0, 8.0)))

    @Test
    fun `cold phase accumulates the fuel above the warm curve`() {
        val learner = ColdStartLearner(warmCurve)

        assertTrue(learner.onSample(speedKmh = 36.0, fuelRateLph = 12.0, dtSec = 10.0, coolantTempC = 20.0))
        assertTrue(learner.onSample(speedKmh = 36.0, fuelRateLph = 12.0, dtSec = 10.0, coolantTempC = 40.0))

        val distanceKm = 36.0 * (20.0 / 3600.0)
        val fuelL = 12.0 * (20.0 / 3600.0)
        val warmL = 8.0 * distanceKm / 100.0
        assertEquals(fuelL - warmL, learner.extraL, 1e-9)
        assertTrue(learner.hasColdSamples)

        val trip = learner.endTrip()
        assertEquals(fuelL - warmL, trip, 1e-9)
        assertEquals(0.0, learner.extraL, 0.0)
        assertFalse(learner.hasColdSamples)
    }

    @Test
    fun `warm phase contributes nothing`() {
        val learner = ColdStartLearner(warmCurve)

        assertFalse(learner.onSample(speedKmh = 36.0, fuelRateLph = 12.0, dtSec = 10.0, coolantTempC = 85.0))

        assertEquals(0.0, learner.extraL, 0.0)
        assertEquals(0.0, learner.endTrip(), 0.0)
    }

    @Test
    fun `cold idle burns extra fuel even with no distance`() {
        val learner = ColdStartLearner(warmCurve)

        assertTrue(learner.onSample(speedKmh = 0.0, fuelRateLph = 2.0, dtSec = 60.0, coolantTempC = 10.0))

        assertEquals(2.0 * 60.0 / 3600.0, learner.extraL, 1e-9)
    }

    @Test
    fun `running mean keeps the default until enough cold starts are seen`() {
        var stats = ColdStartStats.initial()
        assertEquals(0.15, stats.effectiveExtraL, 1e-9)

        stats = ColdStartStats.runningMean(stats, 0.4)
        assertEquals(0.4, stats.meanExtraL, 1e-9)
        assertEquals(0.15, stats.effectiveExtraL, 1e-9)

        stats = ColdStartStats.runningMean(stats, 0.5)
        stats = ColdStartStats.runningMean(stats, 0.6)
        assertEquals(3, stats.count)
        assertEquals(0.5, stats.meanExtraL, 1e-9)
        assertEquals(0.5, stats.effectiveExtraL, 1e-9)
    }
}
