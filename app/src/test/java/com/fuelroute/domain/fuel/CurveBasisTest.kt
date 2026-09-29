package com.fuelroute.domain.fuel

import com.fuelroute.domain.learning.LearnedCurve
import com.fuelroute.domain.model.SpeedBinStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveBasisTest {

    private val fallback = DefaultCurve.forVehicle(7.0)

    @Test
    fun `quality bands by total learned km`() {
        assertEquals(CurveDataQuality.NONE, CurveBasis.quality(0.0))
        assertEquals(CurveDataQuality.NONE, CurveBasis.quality(-3.0))
        assertEquals(CurveDataQuality.LOW, CurveBasis.quality(5.0))
        assertEquals(CurveDataQuality.LOW, CurveBasis.quality(CurveBasis.LOW_MAX_KM - 0.01))
        assertEquals(CurveDataQuality.MEDIUM, CurveBasis.quality(CurveBasis.LOW_MAX_KM))
        assertEquals(CurveDataQuality.MEDIUM, CurveBasis.quality(CurveBasis.MEDIUM_MAX_KM - 0.01))
        assertEquals(CurveDataQuality.HIGH, CurveBasis.quality(CurveBasis.MEDIUM_MAX_KM))
        assertEquals(CurveDataQuality.HIGH, CurveBasis.quality(1_000.0))
    }

    @Test
    fun `learned share is zero without data`() {
        assertEquals(0.0, CurveBasis.learnedShare(null, fallback, listOf(50.0, 90.0)), 1e-9)
        assertEquals(0.0, CurveBasis.learnedShare(LearnedCurve(emptyList()), fallback, listOf(50.0)), 1e-9)
        assertEquals(0.0, CurveBasis.learnedShare(learned(bin = 10, km = 50.0), fallback, emptyList()), 1e-9)
    }

    @Test
    fun `uncovered speeds pull the share down`() {
        // Learned data only around 52.5 km/h; the 110 km/h sample has no coverage.
        val learned = learned(bin = 11, km = 50.0)
        val nearOnly = CurveBasis.learnedShare(learned, fallback, listOf(52.5))
        val mixed = CurveBasis.learnedShare(learned, fallback, listOf(52.5, 110.0))
        assertTrue("share at covered speed should be positive: $nearOnly", nearOnly > 0.0)
        assertTrue("mixed share should be lower: $mixed", mixed < nearOnly)
        assertEquals(nearOnly / 2.0, mixed, 1e-9)
    }

    @Test
    fun `share grows with measured distance`() {
        val speeds = listOf(52.5)
        val low = CurveBasis.learnedShare(learned(bin = 11, km = 20.0), fallback, speeds)
        val high = CurveBasis.learnedShare(learned(bin = 11, km = 200.0), fallback, speeds)
        assertEquals(0.5, low, 1e-9)
        assertTrue("expected $high > $low", high > low)
        assertTrue("share must stay within 0..1: $high", high in 0.0..1.0)
    }

    @Test
    fun `a speed between two bins shares the weight of both`() {
        // 97.5 (bin 20) and 102.5 (bin 21) around 100 km/h, both at a plausible 8 L/100km.
        val learned = LearnedCurve(
            listOf(
                SpeedBinStats("v", binIndex = 20, distanceKm = 5.0, fuelL = 0.4, seconds = 185.0, samples = 10),
                SpeedBinStats("v", binIndex = 21, distanceKm = 180.0, fuelL = 14.4, seconds = 6_300.0, samples = 10),
            ),
        )
        val share = CurveBasis.learnedShare(learned, fallback, listOf(100.0))
        assertEquals((CurveBlender.weight(5.0) + CurveBlender.weight(180.0)) / 2.0, share, 1e-9)
    }

    @Test
    fun `the default sampling covers the model range uniformly`() {
        assertEquals(121, CurveBasis.UNIFORM_SPEEDS.size)
        val share = CurveBasis.learnedShare(learned(bin = 11, km = 50.0), fallback)
        assertTrue("one bin covers a small part of the range: $share", share > 0.0 && share < 0.1)
    }

    private fun learned(bin: Int, km: Double): LearnedCurve = LearnedCurve(
        listOf(
            SpeedBinStats(
                vehicleId = "v",
                binIndex = bin,
                distanceKm = km,
                fuelL = km * 0.08,
                seconds = km * 100.0,
                samples = 10,
            ),
        ),
    )
}