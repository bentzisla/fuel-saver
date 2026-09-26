package com.fuelroute.domain.fuel

import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelModelOverridesTest {

    private val curve = DefaultCurve.forVehicle(7.0)

    @Test
    fun `null override fields fall back to ModelConstants`() {
        val overrides = FuelModelOverrides()

        assertEquals(ModelConstants.SLOW_FACTOR, overrides.effectiveSlowFactor, 1e-9)
        assertEquals(ModelConstants.JAM_FACTOR, overrides.effectiveJamFactor, 1e-9)
        assertEquals(ModelConstants.STOP_GO_WEIGHT, overrides.effectiveStopGoWeight, 1e-9)
        assertEquals(ModelConstants.COLD_START_DEFAULT_L, overrides.effectiveColdStartDefaultL, 1e-9)
        assertEquals(ModelConstants.IDLE_LPH_DEFAULT, overrides.effectiveIdleLphDefault, 1e-9)
        assertEquals(1.0, overrides.effectiveFuelCorrection, 1e-9)
    }

    @Test
    fun `set override fields win over ModelConstants`() {
        val overrides = FuelModelOverrides(
            slowFactor = 0.7,
            jamFactor = 0.4,
            stopGoWeight = 0.9,
            coldStartDefaultL = 0.2,
            idleLphDefault = 1.1,
            fuelCorrection = 1.25,
        )

        assertEquals(0.7, overrides.effectiveSlowFactor, 1e-9)
        assertEquals(0.4, overrides.effectiveJamFactor, 1e-9)
        assertEquals(0.9, overrides.effectiveStopGoWeight, 1e-9)
        assertEquals(0.2, overrides.effectiveColdStartDefaultL, 1e-9)
        assertEquals(1.1, overrides.effectiveIdleLphDefault, 1e-9)
        assertEquals(1.25, overrides.effectiveFuelCorrection, 1e-9)
    }

    @Test
    fun `a manually typed correction far below rated is clamped, not trusted`() {
        // Regression: an unclamped low correction (e.g. mistyped, or fit over one bad drive)
        // could silently halve every predicted route cost.
        val tooLow = FuelModelOverrides(fuelCorrection = 0.3)
        val tooHigh = FuelModelOverrides(fuelCorrection = 5.0)

        assertEquals(CalibrationFitter.MIN_CORRECTION, tooLow.effectiveFuelCorrection, 1e-9)
        assertEquals(CalibrationFitter.MAX_CORRECTION, tooHigh.effectiveFuelCorrection, 1e-9)
    }

    @Test
    fun `stop-go weight override changes the route cost`() {
        val route = congestedRoute()
        val withoutStopGo = FuelModel(
            curve = curve,
            idleLitersPerHour = 0.8,
            overrides = FuelModelOverrides(stopGoWeight = 0.0),
        ).cost(route, 7.0)
        val fullStopGo = FuelModel(
            curve = curve,
            idleLitersPerHour = 0.8,
            overrides = FuelModelOverrides(stopGoWeight = 1.0),
        ).cost(route, 7.0)

        assertTrue(
            "stop-go weight must affect fuel: ${fullStopGo.fuelLiters} vs ${withoutStopGo.fuelLiters}",
            fullStopGo.fuelLiters > withoutStopGo.fuelLiters,
        )
    }

    @Test
    fun `global correction scales every predicted liter`() {
        val route = plainRoute()
        val base = FuelModel(curve, 0.8).cost(route, 7.0)
        val corrected = FuelModel(
            curve = curve,
            idleLitersPerHour = 0.8,
            overrides = FuelModelOverrides(fuelCorrection = 1.5),
        ).cost(route, 7.0)

        assertEquals(base.fuelLiters * 1.5, corrected.fuelLiters, 1e-9)
        assertEquals(base.totalCost * 1.5, corrected.totalCost, 1e-9)
    }

    @Test
    fun `jam factor override changes a congested multi-segment route`() {
        val route = congestedRoute()
        val defaultJam = FuelModel(curve, 0.8).cost(route, 7.0)
        val raisedJam = FuelModel(
            curve = curve,
            idleLitersPerHour = 0.8,
            overrides = FuelModelOverrides(jamFactor = 0.5),
        ).cost(route, 7.0)

        assertTrue(
            "raising the jam clamp must change fuel: ${raisedJam.fuelLiters} vs ${defaultJam.fuelLiters}",
            raisedJam.fuelLiters != defaultJam.fuelLiters,
        )
    }

    private fun plainRoute() = Route(
        id = "plain",
        distanceMeters = 10_000.0,
        staticDurationSeconds = 600.0,
        durationSeconds = 600.0,
        segments = listOf(RouteSegment(10_000.0, 600.0)),
    )

    private fun congestedRoute() = Route(
        id = "congested",
        distanceMeters = 10_000.0,
        staticDurationSeconds = 600.0,
        durationSeconds = 900.0,
        segments = listOf(
            RouteSegment(5_000.0, 300.0, congestionFactor = 1.0),
            RouteSegment(5_000.0, 300.0, congestionFactor = 0.25),
        ),
    )
}