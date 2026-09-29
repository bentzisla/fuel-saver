package com.fuelroute.domain.learning

import com.fuelroute.domain.fuel.ConsumptionCurve
import com.fuelroute.domain.fuel.DefaultCurve
import com.fuelroute.domain.fuel.FuelModelOverrides
import com.fuelroute.domain.fuel.ModelConstants
import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.SpeedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cold-start baseline and default: same effective curve and overrides as routing. */
class ColdStartBaselineTest {

    @Test
    fun `untrusted stats fall back to the calibrated default override`() {
        val stats = ColdStartStats(meanExtraL = 0.5, count = 1)
        val overrides = FuelModelOverrides(coldStartDefaultL = 0.3)

        assertEquals(0.3, stats.effectiveExtraL(overrides.effectiveColdStartDefaultL), 1e-9)
        // Without an override the built-in default applies, as before.
        assertEquals(ModelConstants.COLD_START_DEFAULT_L, stats.effectiveExtraL, 1e-9)
    }

    @Test
    fun `trusted stats ignore the default`() {
        val stats = ColdStartStats(meanExtraL = 0.5, count = ColdStartStats.MIN_COLD_STARTS)
        assertEquals(0.5, stats.effectiveExtraL(0.3), 1e-9)
    }

    @Test
    fun `warm baseline is anchored to the measured level, not the raw rated default`() {
        // Rated 10 L/100 typed in, but 300 km measured at ~5.5 L/100 around 90 km/h.
        val default = DefaultCurve.forVehicle(10.0, FuelType.GASOLINE)
        val bins = listOf(
            SpeedBinStats("v1", binIndex = 18, distanceKm = 300.0, fuelL = 16.5, seconds = 12_000.0, samples = 48_000),
        )

        val baseline = ColdStartLearner.warmBaseline(LearnedCurve(bins), manual = null, default = default)

        assertTrue(baseline.litersPer100Km(92.0) < default.litersPer100Km(92.0) * 0.8)
        assertTrue(baseline.litersPer100Km(40.0) < default.litersPer100Km(40.0))
    }

    @Test
    fun `warm baseline uses the manual curve as the fallback when nothing is learned`() {
        val manual = ConsumptionCurve(listOf(SpeedPoint(0.0, 6.0), SpeedPoint(130.0, 6.0)))
        val default = DefaultCurve.forVehicle(10.0, FuelType.GASOLINE)

        val baseline = ColdStartLearner.warmBaseline(LearnedCurve(emptyList()), manual, default)

        assertEquals(6.0, baseline.litersPer100Km(70.0), 1e-9)
    }
}
