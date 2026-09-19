package com.fuelroute.domain.fuel

import com.fuelroute.domain.learning.LearnedCurve
import com.fuelroute.domain.model.SpeedBinStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveBlenderTest {

    @Test
    fun `without learned data the fallback is used as-is`() {
        val fallback = DefaultCurve.forVehicle(7.0)
        val blended = CurveBlender.blend(null, fallback)
        assertEquals(fallback.litersPer100Km(70.0), blended.litersPer100Km(70.0), 1e-9)
    }

    @Test
    fun `weight grows with measured distance`() {
        assertEquals(0.0, CurveBlender.weight(0.0), 1e-9)
        assertEquals(0.5, CurveBlender.weight(CurveBlender.CONFIDENCE_K_KM), 1e-9)
        assertEquals(0.8, CurveBlender.weight(4.0 * CurveBlender.CONFIDENCE_K_KM), 1e-9)
    }

    @Test
    fun `heavy learning dominates the fallback`() {
        val fallback = DefaultCurve.forVehicle(7.0)
        val learned = LearnedCurve(
            listOf(
                SpeedBinStats(
                    vehicleId = "v",
                    binIndex = 14, // 70-75 km/h, center 72.5
                    distanceKm = 500.0,
                    fuelL = 50.0,
                    seconds = 20_000.0,
                    samples = 1_000,
                ),
            ),
        )
        val blended = CurveBlender.blend(learned, fallback)
        val value = blended.litersPer100Km(72.5)
        assertTrue("expected close to 10.0 but was $value", value > 9.3 && value < 10.0)
    }

    @Test
    fun `speeds without data keep the fallback value`() {
        val fallback = DefaultCurve.forVehicle(7.0)
        val learned = LearnedCurve(
            listOf(
                SpeedBinStats(vehicleId = "v", binIndex = 4, distanceKm = 100.0, fuelL = 20.0, seconds = 4_000.0, samples = 100),
            ),
        )
        val blended = CurveBlender.blend(learned, fallback)
        assertEquals(fallback.litersPer100Km(120.0), blended.litersPer100Km(120.0), 1e-6)
    }
}