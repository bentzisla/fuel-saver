package com.fuelroute.domain.fuel

import com.fuelroute.domain.model.SpeedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCurveValidatorTest {

    @Test
    fun `sorts points ascending by speed`() {
        val result = ManualCurveValidator.validate(
            listOf(SpeedPoint(100.0, 6.0), SpeedPoint(30.0, 8.0), SpeedPoint(60.0, 5.0)),
        )
        assertTrue(result is ManualCurveResult.Valid)
        assertEquals(
            listOf(30.0, 60.0, 100.0),
            (result as ManualCurveResult.Valid).points.map { it.speedKmh },
        )
    }

    @Test
    fun `duplicate speeds keep the last value`() {
        val result = ManualCurveValidator.normalize(
            listOf(
                SpeedPoint(50.0, 9.0),
                SpeedPoint(20.0, 7.0),
                SpeedPoint(50.0, 4.0),
                SpeedPoint(80.0, 6.0),
            ),
        )
        assertEquals(listOf(20.0, 50.0, 80.0), result.map { it.speedKmh })
        assertEquals(4.0, result.first { it.speedKmh == 50.0 }.litersPer100Km, 1e-9)
    }

    @Test
    fun `drops non-finite and non-positive values`() {
        val result = ManualCurveValidator.normalize(
            listOf(
                SpeedPoint(30.0, 7.0),
                SpeedPoint(40.0, 0.0),
                SpeedPoint(50.0, -1.0),
                SpeedPoint(60.0, Double.NaN),
                SpeedPoint(70.0, 8.0),
                SpeedPoint(Double.NaN, 9.0),
                SpeedPoint(-20.0, 6.0),
            ),
        )
        assertEquals(listOf(30.0, 70.0), result.map { it.speedKmh })
    }

    @Test
    fun `clamps speeds above the maximum`() {
        val result = ManualCurveValidator.normalize(
            listOf(SpeedPoint(200.0, 12.0), SpeedPoint(50.0, 6.0)),
        )
        assertEquals(listOf(50.0, ManualCurveValidator.MAX_SPEED_KMH), result.map { it.speedKmh })
    }

    @Test
    fun `fewer than two usable points is invalid`() {
        val one = ManualCurveValidator.validate(listOf(SpeedPoint(50.0, 6.0)))
        assertEquals(
            ManualCurveError.TOO_FEW_POINTS,
            (one as ManualCurveResult.Invalid).reason,
        )

        // Two supplied points, but one is dropped -> only one remains.
        val dropped = ManualCurveValidator.validate(
            listOf(SpeedPoint(50.0, 6.0), SpeedPoint(60.0, 0.0)),
        )
        assertTrue(dropped is ManualCurveResult.Invalid)
    }

    @Test
    fun `empty list clears the curve`() {
        assertEquals(ManualCurveResult.Cleared, ManualCurveValidator.validate(emptyList()))
        assertTrue(ManualCurveValidator.normalize(emptyList()).isEmpty())
        assertNull(ManualCurveValidator.normalizedOrNull(emptyList()))
    }

    @Test
    fun `normalizedOrNull returns the cleaned curve`() {
        val curve = ManualCurveValidator.normalizedOrNull(
            listOf(SpeedPoint(100.0, 6.0), SpeedPoint(40.0, 8.0)),
        )
        assertEquals(listOf(40.0, 100.0), curve?.map { it.speedKmh })
    }
}