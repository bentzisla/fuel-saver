package com.fuelroute.ui.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleInputRulesTest {

    @Test
    fun `displacement in litres is accepted and blank is optional`() {
        assertNull(VehicleInputRules.displacementIssue("1.8"))
        assertNull(VehicleInputRules.displacementIssue(" 1.6 "))
        assertNull(VehicleInputRules.displacementIssue(""))
    }

    @Test
    fun `displacement typed in cc is flagged as cc with the litre suggestion`() {
        assertEquals(FieldIssue.LooksLikeCc, VehicleInputRules.displacementIssue("1800"))
        assertEquals(1.8, VehicleInputRules.ccToLitres("1800")!!, 1e-9)
    }

    @Test
    fun `implausible displacement is out of range`() {
        assertEquals(FieldIssue.OutOfRange, VehicleInputRules.displacementIssue("0.2"))
        assertEquals(FieldIssue.OutOfRange, VehicleInputRules.displacementIssue("12"))
        assertEquals(FieldIssue.NotANumber, VehicleInputRules.displacementIssue("abc"))
    }

    @Test
    fun `rated consumption must be plausible L per 100km and blank uses the default`() {
        assertNull(VehicleInputRules.ratedCombinedIssue("6.5"))
        assertNull(VehicleInputRules.ratedCombinedIssue(""))
        assertEquals(FieldIssue.NotANumber, VehicleInputRules.ratedCombinedIssue("6,5"))
        assertEquals(FieldIssue.OutOfRange, VehicleInputRules.ratedCombinedIssue("65"))
    }

    @Test
    fun `tank capacity is optional and range checked`() {
        assertNull(VehicleInputRules.tankIssue(""))
        assertNull(VehicleInputRules.tankIssue("50"))
        assertEquals(FieldIssue.OutOfRange, VehicleInputRules.tankIssue("5"))
    }
}
