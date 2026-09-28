package com.fuelroute.domain.fuel

import com.fuelroute.domain.learning.LearnedCurve
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.SpeedPoint
import com.fuelroute.domain.model.speedToBinIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EfficientSpeedTest {

    private val default = DefaultCurve.forVehicle(10.0)

    /** A learned bin at [speedKmh] (bin centre) with [km] measured at [l100]. */
    private fun bin(speedKmh: Double, km: Double, l100: Double): SpeedBinStats {
        return SpeedBinStats(
            vehicleId = "v1",
            binIndex = speedToBinIndex(speedKmh),
            distanceKm = km,
            fuelL = km * l100 / 100.0,
            seconds = km / speedKmh * 3600.0,
            samples = (km * 10).toInt().coerceAtLeast(1),
        )
    }

    @Test
    fun `the optimum is the minimum of the effective curve`() {
        val insight = EfficientSpeed.analyze(default, null, default)!!
        // The default gasoline shape bottoms out at 70 km/h (factor 0.91).
        assertEquals(70.0, insight.speedKmh, 1e-9)
        assertEquals(9.1, insight.litersPer100Km, 1e-9)
        assertEquals(0.0, insight.learnedWeight, 1e-9)
    }

    @Test
    fun `the near-optimal band covers the flat bottom, rounded to 5 km per h`() {
        val insight = EfficientSpeed.analyze(default, null, default)!!
        // Within 3% of 9.1 (<= 9.373): 61..81 km/h on the piecewise-linear default.
        assertEquals(60.0, insight.rangeFromKmh, 1e-9)
        assertEquals(80.0, insight.rangeToKmh, 1e-9)
    }

    @Test
    fun `a manual curve with its minimum between vertices of the default still reports its own minimum`() {
        val manual = ConsumptionCurve(listOf(SpeedPoint(20.0, 9.0), SpeedPoint(55.0, 5.0), SpeedPoint(120.0, 9.0)))
        assertEquals(55.0, EfficientSpeed.analyze(manual, null, manual)!!.speedKmh, 1e-9)
    }

    @Test
    fun `measured points far below the base are flagged as a mismatch and the measured optimum is surfaced`() {
        // The field case: rated 10 L/100, measured 5-6 L/100 at highway speeds.
        val learned = LearnedCurve(
            listOf(
                bin(77.5, 2.5, 6.3),
                bin(87.5, 3.8, 5.6),
                bin(92.5, 9.2, 5.2),
            ),
        )
        val effective = CurveBlender.blend(learned, default)
        val insight = EfficientSpeed.analyze(effective, learned, default)!!

        assertTrue(insight.fallbackMismatch)
        assertTrue(insight.measuredVsFallback!! < 0.75)
        assertNotNull(insight.measuredSpeedKmh)
        assertEquals(92.5, insight.measuredSpeedKmh!!, 1e-9)
        assertEquals(5.2, insight.measuredL100!!, 1e-6)
    }

    @Test
    fun `no mismatch and no separate measured optimum when the data agrees with the curve`() {
        val learned = LearnedCurve(
            listOf(
                bin(62.5, 30.0, default.litersPer100Km(62.5)),
                bin(72.5, 40.0, default.litersPer100Km(72.5)),
                bin(82.5, 30.0, default.litersPer100Km(82.5)),
            ),
        )
        val effective = CurveBlender.blend(learned, default)
        val insight = EfficientSpeed.analyze(effective, learned, default)!!

        assertFalse(insight.fallbackMismatch)
        assertNull(insight.measuredSpeedKmh)
        assertTrue(insight.learnedWeight > EfficientSpeed.FALLBACK_DOMINATED_WEIGHT)
    }

    @Test
    fun `the mismatch ratio needs enough measured distance`() {
        val learned = LearnedCurve(listOf(bin(72.5, 2.0, 4.0)))
        assertNull(EfficientSpeed.measuredVsFallback(learned, default))
    }
}
