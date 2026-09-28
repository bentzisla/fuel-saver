package com.fuelroute.domain.fuel

import com.fuelroute.domain.learning.LearnedCurve
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.SpeedPoint
import com.fuelroute.domain.model.speedToBinIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseLevelTest {

    private val default = DefaultCurve.forVehicle(10.0)

    private fun bin(speedKmh: Double, km: Double, l100: Double) = SpeedBinStats(
        vehicleId = "v1",
        binIndex = speedToBinIndex(speedKmh),
        distanceKm = km,
        fuelL = km * l100 / 100.0,
        seconds = km / speedKmh * 3600.0,
        samples = (km * 10).toInt().coerceAtLeast(1),
    )

    /** Every bin measured at [ratio] x the default, [kmEach] km per bin. */
    private fun learnedAt(ratio: Double, kmEach: Double) = LearnedCurve(
        listOf(42.5, 62.5, 82.5, 97.5).map { bin(it, kmEach, default.litersPer100Km(it) * ratio) },
    )

    @Test
    fun `no measurements leave the default untouched`() {
        assertEquals(1.0, BaseLevel.factor(null, default), 1e-9)
        assertEquals(1.0, BaseLevel.factor(LearnedCurve(emptyList()), default), 1e-9)
        assertSame(default, BaseLevel.anchor(default, 1.0))
    }

    @Test
    fun `the level moves towards the measurements as km accumulate`() {
        val few = BaseLevel.factor(learnedAt(0.6, kmEach = 2.5), default)
        val many = BaseLevel.factor(learnedAt(0.6, kmEach = 50.0), default)
        // 10 km: 1 + 10/60 * -0.4; 200 km: 1 + 200/250 * -0.4.
        assertEquals(1.0 - 0.4 * 10.0 / 60.0, few, 1e-6)
        assertEquals(1.0 - 0.4 * 200.0 / 250.0, many, 1e-6)
        assertTrue(many < few)
    }

    @Test
    fun `the factor is bounded whatever the data says`() {
        assertEquals(BaseLevel.MIN_FACTOR, BaseLevel.factor(learnedAt(0.2, kmEach = 1_000.0), default), 1e-9)
        assertEquals(BaseLevel.MAX_FACTOR, BaseLevel.factor(learnedAt(3.0, kmEach = 1_000.0), default), 1e-9)
    }

    @Test
    fun `anchoring scales the level and keeps the shape`() {
        val anchored = BaseLevel.anchor(default, 0.7)
        assertEquals(default.litersPer100Km(70.0) * 0.7, anchored.litersPer100Km(70.0), 1e-9)
        assertEquals(default.litersPer100Km(120.0) * 0.7, anchored.litersPer100Km(120.0), 1e-9)
    }

    @Test
    fun `a manual curve is used as typed`() {
        val manual = ConsumptionCurve(listOf(SpeedPoint(20.0, 9.0), SpeedPoint(120.0, 9.0)))
        assertSame(manual, BaseLevel.fallback(learnedAt(0.6, kmEach = 50.0), manual, default))
    }

    @Test
    fun `field case - 44 km at 5_5 L per 100 no longer blends to 7_2 at 100 km per h`() {
        // Rated 10.0 in the profile; highway bins measured ~5.2-5.5 over ~100 km, city bins ~8-10.
        val learned = LearnedCurve(
            listOf(
                bin(42.5, 5.0, 8.6),
                bin(62.5, 3.5, 6.6),
                bin(82.5, 13.3, 5.85),
                bin(92.5, 31.0, 5.48),
                bin(97.5, 43.9, 5.49),
                bin(102.5, 22.1, 5.2),
            ),
        )
        val unanchored = CurveBlender.blend(learned, default).litersPer100Km(100.0)
        val anchored = CurveBlender.blend(learned, BaseLevel.fallback(learned, null, default)).litersPer100Km(100.0)

        assertTrue("before: $unanchored", unanchored > 7.0)
        assertTrue("after: $anchored", anchored < 6.2)
    }
}
