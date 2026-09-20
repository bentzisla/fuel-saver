package com.fuelroute.data.price

import com.fuelroute.domain.model.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FuelGradesTest {

    @Test
    fun `gasoline vehicles are offered octane 95 and 98`() {
        assertEquals(
            listOf(FuelGrades.GASOLINE_95, FuelGrades.GASOLINE_98),
            FuelGrades.forFuelType(FuelType.GASOLINE),
        )
    }

    @Test
    fun `hybrid vehicles are offered octane 95 and 98`() {
        assertEquals(
            listOf(FuelGrades.GASOLINE_95, FuelGrades.GASOLINE_98),
            FuelGrades.forFuelType(FuelType.HYBRID),
        )
    }

    @Test
    fun `diesel vehicles only see the diesel grade`() {
        assertEquals(
            listOf(FuelGrades.DIESEL),
            FuelGrades.forFuelType(FuelType.DIESEL),
        )
    }

    @Test
    fun `diesel is never an octane option for gasoline or hybrid`() {
        val gasoline = FuelGrades.forFuelType(FuelType.GASOLINE)
        val hybrid = FuelGrades.forFuelType(FuelType.HYBRID)
        assertFalse(gasoline.contains(FuelGrades.DIESEL))
        assertFalse(hybrid.contains(FuelGrades.DIESEL))
    }

    @Test
    fun `gasoline octanes are never offered to diesel`() {
        val diesel = FuelGrades.forFuelType(FuelType.DIESEL)
        assertFalse(diesel.contains(FuelGrades.GASOLINE_95))
        assertFalse(diesel.contains(FuelGrades.GASOLINE_98))
    }
}