package com.fuelroute.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeModelTest {

    @Test
    fun `flat segment has no grade term`() {
        assertEquals(0.0, GradeModel.extraLiters(0.0, massKg = 1350.0), 1e-9)
    }

    @Test
    fun `500m climb at 1350kg costs roughly 0point8 liters`() {
        // m*g*h / (32 MJ/L * 0.25 efficiency): 1350 * 9.80665 * 500 / 1e6 / 8 ≈ 0.827 L.
        val liters = GradeModel.extraLiters(elevationDeltaM = 500.0, massKg = 1350.0)
        assertTrue("expected ~0.7-0.95 L, got $liters", liters in 0.7..0.95)
    }

    @Test
    fun `climb liters scale linearly with mass`() {
        val light = GradeModel.extraLiters(elevationDeltaM = 300.0, massKg = 1000.0)
        val heavy = GradeModel.extraLiters(elevationDeltaM = 300.0, massKg = 2000.0)
        assertEquals(heavy, light * 2.0, 1e-9)
    }

    @Test
    fun `climb liters scale linearly with elevation gain`() {
        val small = GradeModel.extraLiters(elevationDeltaM = 100.0, massKg = 1350.0)
        val large = GradeModel.extraLiters(elevationDeltaM = 400.0, massKg = 1350.0)
        assertEquals(large, small * 4.0, 1e-9)
    }

    @Test
    fun `descent is a credit but only a fraction of the equivalent climb`() {
        val climb = GradeModel.extraLiters(elevationDeltaM = 500.0, massKg = 1350.0)
        val descent = GradeModel.extraLiters(elevationDeltaM = -500.0, massKg = 1350.0)

        assertTrue(descent < 0.0)
        assertEquals(-climb * GradeModel.DESCENT_RECOVERY_FRACTION, descent, 1e-9)
        // The credit must never be as large as the climb would have cost - descending never
        // "pays" more than a fraction of what climbing costs.
        assertTrue(-descent < climb)
    }

    @Test
    fun `diesel energy density changes the result but stays finite and positive for a climb`() {
        val gasoline = GradeModel.extraLiters(500.0, 1350.0, GradeModel.GASOLINE_MJ_PER_L)
        val diesel = GradeModel.extraLiters(500.0, 1350.0, GradeModel.DIESEL_MJ_PER_L)

        assertTrue(diesel > 0.0 && diesel.isFinite())
        // Diesel is more energy-dense per liter, so the same climb needs fewer liters.
        assertTrue(diesel < gasoline)
    }

    @Test
    fun `non-finite or non-positive inputs degrade to zero instead of throwing or NaN`() {
        assertEquals(0.0, GradeModel.extraLiters(Double.NaN, 1350.0), 0.0)
        assertEquals(0.0, GradeModel.extraLiters(500.0, massKg = 0.0), 0.0)
        assertEquals(0.0, GradeModel.extraLiters(500.0, massKg = Double.NaN), 0.0)
        assertEquals(0.0, GradeModel.extraLiters(500.0, 1350.0, energyDensityMjPerL = 0.0), 0.0)
        assertEquals(0.0, GradeModel.extraLiters(500.0, 1350.0, engineEfficiency = -1.0), 0.0)
    }

    @Test
    fun `Beit Shemesh to Jerusalem uphill drive prices in a plausible range`() {
        // ~32km, net +500m climb, split into a handful of segments the way real polyline
        // sampling would (mixed micro-ups-and-downs, net positive). Speed-curve consumption is
        // handled by FuelModel/ConsumptionCurve; this test only asserts the grade contribution
        // itself is in the physically expected ballpark for the reported bug (a ~30-35km uphill
        // drive priced under 10 NIS, which requires under 4.5 L/100km - implausible).
        val segmentDeltas = listOf(80.0, -20.0, 150.0, -10.0, 200.0, 100.0) // net = 500m
        assertEquals(500.0, segmentDeltas.sum(), 1e-9)

        val totalGradeLiters = segmentDeltas.sumOf { delta -> GradeModel.extraLiters(delta, massKg = 1350.0) }

        // Expect close to the ~0.83L a pure 500m/1350kg climb would cost, since descents are
        // only partially credited and are a small fraction of the profile here.
        assertTrue(
            "expected ~0.6-0.9L of grade fuel for a net +500m climb, got $totalGradeLiters",
            totalGradeLiters in 0.6..0.9,
        )

        // Sanity check against the user's report: a 32km drive at 6.5 L/100km flat consumption
        // (~2.08L) plus this grade term, at 7.3 NIS/L, must land comfortably above 10 NIS and in
        // the 14-20 NIS range the bug report calls plausible.
        val flatLiters = 32.0 * 6.5 / 100.0
        val totalCost = (flatLiters + totalGradeLiters) * 7.3
        assertTrue("expected 10-25 NIS, got $totalCost", totalCost in 10.0..25.0)
    }
}
