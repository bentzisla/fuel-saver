package com.fuelroute.domain.learning

import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.speedToBinIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearnedCurveTest {

    private fun bin(speedKmh: Double, km: Double) = SpeedBinStats(
        vehicleId = "v",
        binIndex = speedToBinIndex(speedKmh),
        distanceKm = km,
        fuelL = km * 0.06,
        seconds = km / speedKmh * 3600.0,
        samples = 10,
    )

    private fun idle(seconds: Double, lph: Double) = SpeedBinStats(
        vehicleId = "v",
        binIndex = 0,
        fuelL = lph * seconds / 3600.0,
        seconds = seconds,
        samples = seconds.toInt(),
    )

    @Test
    fun `confidence between two bins takes both neighbours by distance`() {
        // Regression: a nearest-point search always picked the lower bin on a tie.
        val curve = LearnedCurve(listOf(bin(97.5, 5.0), bin(102.5, 200.0)))
        assertEquals(102.5, curve.confidenceKm(100.0), 1e-9)
        assertEquals(200.0, curve.confidenceKm(102.5), 1e-9)
        assertEquals(5.0, curve.confidenceKm(97.5), 1e-9)
    }

    @Test
    fun `confidence is zero a bin width away from any data`() {
        val curve = LearnedCurve(listOf(bin(52.5, 100.0)))
        assertEquals(50.0, curve.confidenceKm(55.0), 1e-9)
        assertEquals(0.0, curve.confidenceKm(57.5), 1e-9)
        assertEquals(0.0, curve.confidenceKm(70.0), 1e-9)
    }

    @Test
    fun `a few seconds of idling do not replace the default idle rate`() {
        val curve = LearnedCurve(listOf(idle(seconds = 8.0, lph = 3.0)))
        assertNull(curve.idleLitersPerHour)
        // Blended: 8 s against 300 s of confidence barely moves the 0.8 L/h default.
        val blended = curve.blendedIdleLitersPerHour(0.8)
        assertEquals(0.8 + 8.0 / 308.0 * (3.0 - 0.8), blended, 1e-9)
    }

    @Test
    fun `enough idling reports the measured rate and dominates the blend`() {
        val curve = LearnedCurve(listOf(idle(seconds = 3_000.0, lph = 1.2)))
        assertEquals(1.2, curve.idleLitersPerHour!!, 1e-9)
        assertEquals(3_000.0, curve.idleSeconds, 1e-9)
        val w = 3_000.0 / 3_300.0
        assertEquals(w * 1.2 + (1 - w) * 0.8, curve.blendedIdleLitersPerHour(0.8), 1e-9)
    }

    @Test
    fun `no idle bin keeps the default`() {
        val curve = LearnedCurve(listOf(bin(52.5, 10.0)))
        assertNull(curve.idleLitersPerHour)
        assertEquals(0.8, curve.blendedIdleLitersPerHour(0.8), 1e-12)
    }
}
