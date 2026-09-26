package com.fuelroute.domain.fuel

import com.fuelroute.domain.learning.LearnedCurve
import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteSegment
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.speedToBinIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the reported bug: a Herzliya -> Beit Shemesh drive (~73.4 km, 53 min) priced
 * at ~1.24 NIS - about 25x too low for a typical ~6.5 L/100km car at ~7 NIS/L (which should cost
 * roughly 30-40 NIS). Reproduces the most likely multiplicative failure points end-to-end
 * (a near-zero learned curve gaining full trust via [CurveBlender], and an unclamped fitted
 * [FuelModelOverrides.fuelCorrection]) and asserts the full pipeline stays physically plausible.
 */
class PoisonedCurveRegressionTest {

    private val ratedL100 = 6.5
    private val distanceKm = 73.4
    private val pricePerLiter = 7.0

    @Test
    fun `a near-zero learned curve is rejected by the blend, not trusted`() {
        val fallback = DefaultCurve.forVehicle(ratedL100)
        // Plenty of distance in every bin so the blend weight is near 1.0 - i.e. the learned
        // value would fully replace the fallback if CurveBlender's plausibility guard did not
        // step in. fuelL is set to ~0.04x what the rated curve would burn (the same order of
        // magnitude as the reported ~25x-too-cheap bug).
        val poisoned = LearnedCurve(
            (10..130 step 10).map { speed ->
                val binIndex = speedToBinIndex(speed.toDouble())
                val plausibleFuelL = 500.0 * ratedL100 / 100.0
                SpeedBinStats(
                    vehicleId = "v",
                    binIndex = binIndex,
                    distanceKm = 500.0,
                    fuelL = plausibleFuelL * 0.04,
                    seconds = 500.0 / speed * 3600.0,
                    samples = 1_000,
                )
            },
        )

        val blended = CurveBlender.blend(poisoned, fallback)
        for (speed in listOf(30.0, 60.0, 90.0, 120.0)) {
            assertEquals(
                "blend at $speed must fall back rather than trust the poisoned curve",
                fallback.litersPer100Km(speed),
                blended.litersPer100Km(speed),
                1e-6,
            )
        }
    }

    @Test
    fun `a flat 73km route never prices below what is physically plausible`() {
        val fuelModel = FuelModel(curve = DefaultCurve.forVehicle(ratedL100), idleLitersPerHour = 0.8)
        val route = Route(
            id = "herzliya-beit-shemesh",
            distanceMeters = distanceKm * 1000.0,
            staticDurationSeconds = 53.0 * 60.0,
            durationSeconds = 53.0 * 60.0,
            segments = listOf(RouteSegment(distanceKm * 1000.0, 53.0 * 60.0)),
        )

        val cost = fuelModel.cost(route, pricePerLiter)

        assertTrue("expected > 20 NIS for a 73km drive, got ${cost.totalCost}", cost.totalCost > 20.0)
    }

    @Test
    fun `a stored fuelCorrection of 0point022 (the exact reported factor) is clamped, not applied`() {
        // Confirmed root cause from the user's per-segment breakdown: overrides.fuelCorrection
        // persisted at ~0.022 (a CalibrationFitter.fitCorrection over pairs that were not
        // filtered for demo rides / outliers), applied verbatim in FuelModel as
        // segmentLiters = (base + stopGo) * correction. A Herzliya -> Beit Shemesh-like 73.4km
        // drive at 7.75 NIS/L must stay physically plausible (> 20 NIS) even with this exact
        // override still sitting in SettingsRepository from before the fix.
        val fuelModel = FuelModel(
            curve = DefaultCurve.forVehicle(ratedL100),
            idleLitersPerHour = 0.8,
            overrides = FuelModelOverrides(fuelCorrection = 0.022),
        )
        val route = Route(
            id = "herzliya-beit-shemesh-0022",
            distanceMeters = 73.4 * 1000.0,
            staticDurationSeconds = 53.0 * 60.0,
            durationSeconds = 53.0 * 60.0,
            segments = listOf(RouteSegment(73.4 * 1000.0, 53.0 * 60.0)),
        )

        val cost = fuelModel.cost(route, pricePerLiter = 7.75)

        assertTrue("expected > 20 NIS at 7.75 NIS/L, got ${cost.totalCost}", cost.totalCost > 20.0)
    }

    @Test
    fun `an implausibly low manually-entered correction is clamped, not applied verbatim`() {
        // The exact failure mode that would otherwise produce ~1.24 NIS from a ~34 NIS route:
        // a correction override far below what any real car's accuracy justifies.
        val fuelModel = FuelModel(
            curve = DefaultCurve.forVehicle(ratedL100),
            idleLitersPerHour = 0.8,
            overrides = FuelModelOverrides(fuelCorrection = 0.036),
        )
        val route = Route(
            id = "herzliya-beit-shemesh-corrected",
            distanceMeters = distanceKm * 1000.0,
            staticDurationSeconds = 53.0 * 60.0,
            durationSeconds = 53.0 * 60.0,
            segments = listOf(RouteSegment(distanceKm * 1000.0, 53.0 * 60.0)),
        )

        val cost = fuelModel.cost(route, pricePerLiter)

        assertTrue("expected > 20 NIS even with a poisoned correction override, got ${cost.totalCost}", cost.totalCost > 20.0)
    }
}
