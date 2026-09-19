package com.fuelroute.domain.fuel

import com.fuelroute.domain.model.SpeedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumptionCurveTest {

    @Test
    fun `interpolates linearly between points`() {
        val curve = ConsumptionCurve(listOf(SpeedPoint(10.0, 10.0), SpeedPoint(20.0, 20.0)))
        assertEquals(15.0, curve.litersPer100Km(15.0), 1e-9)
    }

    @Test
    fun `clamps below the defined range`() {
        val curve = ConsumptionCurve(listOf(SpeedPoint(10.0, 10.0), SpeedPoint(20.0, 20.0)))
        assertEquals(10.0, curve.litersPer100Km(0.0), 1e-9)
    }

    @Test
    fun `clamps above the defined range`() {
        val curve = ConsumptionCurve(listOf(SpeedPoint(10.0, 10.0), SpeedPoint(20.0, 20.0)))
        assertEquals(20.0, curve.litersPer100Km(200.0), 1e-9)
    }

    @Test
    fun `default curve scales with rated consumption`() {
        val economical = DefaultCurve.forVehicle(6.0)
        val thirsty = DefaultCurve.forVehicle(12.0)
        assertEquals(2.0 * economical.litersPer100Km(70.0), thirsty.litersPer100Km(70.0), 1e-9)
    }

    @Test
    fun `default curve has its minimum near 70 kmh`() {
        val curve = DefaultCurve.forVehicle(7.0)
        val at70 = curve.litersPer100Km(70.0)
        assertTrue(at70 < curve.litersPer100Km(30.0))
        assertTrue(at70 < curve.litersPer100Km(120.0))
    }

    @Test
    fun `hybrid curve is flatter in traffic`() {
        val gasoline = DefaultCurve.forVehicle(7.0)
        val hybrid = DefaultCurve.forVehicle(7.0, com.fuelroute.domain.model.FuelType.HYBRID)
        assertTrue(hybrid.litersPer100Km(20.0) < gasoline.litersPer100Km(20.0))
    }
}