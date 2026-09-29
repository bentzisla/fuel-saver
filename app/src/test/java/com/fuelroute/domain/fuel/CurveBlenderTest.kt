package com.fuelroute.domain.fuel

import com.fuelroute.domain.learning.LearnedCurve
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.speedToBinIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                    binIndex = 15, // 70-75 km/h, center 72.5
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
    fun `an implausibly low learned bin is excluded, not blended in`() {
        val fallback = DefaultCurve.forVehicle(7.0)
        // 70-75 km/h fallback is ~6.4 L/100km; 2.0 L/100km (under 0.5x) would mean absurdly good
        // consumption - e.g. an underestimated MAF/speed-density reading - and must be rejected.
        val learned = LearnedCurve(
            listOf(
                SpeedBinStats(
                    vehicleId = "v",
                    binIndex = 15,
                    distanceKm = 500.0,
                    fuelL = 10.0, // 2.0 L/100km
                    seconds = 20_000.0,
                    samples = 1_000,
                ),
            ),
        )
        val blended = CurveBlender.blend(learned, fallback)
        assertEquals(fallback.litersPer100Km(72.5), blended.litersPer100Km(72.5), 1e-9)
    }

    @Test
    fun `an implausibly high learned bin is excluded, not blended in`() {
        val fallback = DefaultCurve.forVehicle(7.0)
        // Fallback is ~6.4 L/100km; 30 L/100km (over 3x) points at corrupted stored data.
        val learned = LearnedCurve(
            listOf(
                SpeedBinStats(
                    vehicleId = "v",
                    binIndex = 15,
                    distanceKm = 500.0,
                    fuelL = 150.0, // 30 L/100km
                    seconds = 20_000.0,
                    samples = 1_000,
                ),
            ),
        )
        val blended = CurveBlender.blend(learned, fallback)
        assertEquals(fallback.litersPer100Km(72.5), blended.litersPer100Km(72.5), 1e-9)
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

    private val default = DefaultCurve.forVehicle(8.0)

    /** A bin centred on [speedKmh] with [km] measured at [ratio] x the default curve. */
    private fun bin(speedKmh: Double, km: Double, ratio: Double): SpeedBinStats {
        val l100 = default.litersPer100Km(speedKmh) * ratio
        return SpeedBinStats(
            vehicleId = "v",
            binIndex = speedToBinIndex(speedKmh),
            distanceKm = km,
            fuelL = km * l100 / 100.0,
            seconds = km / speedKmh * 3600.0,
            samples = 100,
        )
    }

    @Test
    fun `each bin is shrunk towards the fallback by its own km at its centre`() {
        val learned = LearnedCurve(listOf(bin(97.5, 5.0, 1.0), bin(102.5, 20.0, 0.7)))
        val blended = CurveBlender.blend(learned, default)
        val fallback = default.litersPer100Km(102.5)
        // w = 20 / (20 + 20) = 0.5, independent of the neighbouring 5 km bin.
        assertEquals(0.5 * 0.7 * fallback + 0.5 * fallback, blended.litersPer100Km(102.5), 1e-9)
        assertEquals(default.litersPer100Km(97.5), blended.litersPer100Km(97.5), 1e-9)
    }

    @Test
    fun `highway km in the upper adjacent bin count at 100 km per h`() {
        // Regression: 100 km/h sits exactly between the 97.5 and 102.5 centres; the tie went to the
        // lower (5 km) bin, so 200 km measured at 102.5 barely moved the curve at 100.
        val learned = LearnedCurve(listOf(bin(97.5, 5.0, 1.0), bin(102.5, 200.0, 0.7)))
        val ratio = CurveBlender.blend(learned, default).litersPer100Km(100.0) / default.litersPer100Km(100.0)
        val w = CurveBlender.weight(200.0)
        assertEquals((1.0 + (w * 0.7 + (1.0 - w))) / 2.0, ratio, 1e-9)
        assertTrue("ratio at 100 should follow the heavy bin: $ratio", ratio < 0.9)
    }

    @Test
    fun `adjacent bins are treated symmetrically`() {
        val heavyLow = LearnedCurve(listOf(bin(97.5, 200.0, 0.7), bin(102.5, 5.0, 1.0)))
        val heavyHigh = LearnedCurve(listOf(bin(97.5, 5.0, 1.0), bin(102.5, 200.0, 0.7)))
        val low = CurveBlender.blend(heavyLow, default).litersPer100Km(100.0)
        val high = CurveBlender.blend(heavyHigh, default).litersPer100Km(100.0)
        assertEquals(low, high, 1e-9)
        assertEquals(
            CurveBlender.weightAt(heavyLow, default, 100.0),
            CurveBlender.weightAt(heavyHigh, default, 100.0),
            1e-12,
        )
    }

    @Test
    fun `a speed more than a bin away from any data keeps the fallback`() {
        val learned = LearnedCurve(listOf(bin(52.5, 500.0, 0.7), bin(102.5, 500.0, 0.7)))
        val blended = CurveBlender.blend(learned, default)
        assertEquals(default.litersPer100Km(70.0), blended.litersPer100Km(70.0), 1e-9)
        assertEquals(default.litersPer100Km(80.0), blended.litersPer100Km(80.0), 1e-9)
        assertEquals(0.0, CurveBlender.weightAt(learned, default, 80.0), 1e-12)
    }

    @Test
    fun `crawl bins below 10 km per h do not bend the curve`() {
        val learned = LearnedCurve(listOf(bin(7.5, 50.0, 2.5), bin(62.5, 50.0, 1.0)))
        val blended = CurveBlender.blend(learned, default)
        assertEquals(default.litersPer100Km(10.0), blended.litersPer100Km(10.0), 1e-9)
        assertFalse(CurveBlender.usesLearnedPoint(7.5, 20.0, default))
    }

    @Test
    fun `a rejected bin between used bins is a fallback point`() {
        val learned = LearnedCurve(listOf(bin(92.5, 100.0, 0.8), bin(97.5, 100.0, 0.4), bin(102.5, 100.0, 0.8)))
        val blended = CurveBlender.blend(learned, default)
        assertEquals(default.litersPer100Km(97.5), blended.litersPer100Km(97.5), 1e-9)
        assertEquals(0.0, CurveBlender.weightAt(learned, default, 97.5), 1e-12)
    }

    @Test
    fun `the contribution reproduces the blended curve at every vertex and in between`() {
        val learned = LearnedCurve(
            listOf(bin(42.5, 3.0, 1.2), bin(47.5, 30.0, 1.1), bin(97.5, 40.0, 0.8), bin(102.5, 90.0, 0.75)),
        )
        val blended = CurveBlender.blend(learned, default)
        val vertices = blended.samples().map { it.speedKmh }
        for (speed in vertices + listOf(45.0, 99.0, 100.0, 101.3)) {
            val c = CurveBlender.contribution(learned, default, speed)
            val learnedValue = c.learnedValue ?: c.fallbackValue
            assertEquals("mix at $speed", c.value, c.weight * learnedValue + (1 - c.weight) * c.fallbackValue, 1e-9)
            if (speed in vertices) {
                assertEquals("vertex $speed", blended.litersPer100Km(speed), c.value, 1e-9)
            }
        }
        // At a bin centre the learned input is the measurement itself.
        val atCentre = CurveBlender.contribution(learned, default, 102.5)
        assertEquals(default.litersPer100Km(102.5) * 0.75, atCentre.learnedValue!!, 1e-9)
        assertEquals(CurveBlender.weight(90.0), atCentre.weight, 1e-12)
    }

    @Test
    fun `the blended curve spans the model range`() {
        val learned = LearnedCurve(listOf(bin(12.5, 50.0, 1.2), bin(127.5, 50.0, 0.9)))
        val blended = CurveBlender.blend(learned, default)
        assertEquals(10.0, blended.minSpeedKmh, 1e-9)
        assertEquals(130.0, blended.maxSpeedKmh, 1e-9)
        // The edge point follows the nearest bin's ratio along the fallback's shape.
        val r = CurveBlender.weight(50.0) * 1.2 + (1 - CurveBlender.weight(50.0))
        assertEquals(default.litersPer100Km(10.0) * r, blended.litersPer100Km(10.0), 1e-9)
    }
}