package com.fuelroute.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationFitterTest {

    @Test
    fun `fit recovers an exact constant multiplier`() {
        val pairs = listOf(
            10.0 to 11.0,
            4.0 to 4.4,
            2.5 to 2.75,
        )

        assertEquals(1.1, CalibrationFitter.fitCorrection(pairs)!!, 1e-9)
    }

    @Test
    fun `fit returns null for empty input`() {
        assertNull(CalibrationFitter.fitCorrection(emptyList()))
    }

    @Test
    fun `fit returns null when the denominator is zero`() {
        val pairs = listOf(0.0 to 5.0, 0.0 to 3.0, -1.0 to 2.0)

        assertNull(CalibrationFitter.fitCorrection(pairs))
    }

    @Test
    fun `suggested mape improves with the fitted factor`() {
        val pairs = listOf(
            10.0 to 11.0,
            20.0 to 22.0,
            5.0 to 5.5,
        )
        val factor = CalibrationFitter.fitCorrection(pairs)!!

        val before = CalibrationFitter.suggestedMape(pairs, 1.0)
        val after = CalibrationFitter.suggestedMape(pairs, factor)

        assertTrue("after ($after) should be better than before ($before)", after < before)
        assertEquals(0.0, after, 1e-9)
    }

    @Test
    fun `suggested mape is zero when there is nothing usable`() {
        assertEquals(0.0, CalibrationFitter.suggestedMape(emptyList(), 1.0), 1e-9)
        assertEquals(0.0, CalibrationFitter.suggestedMape(listOf(0.0 to 1.0), 1.0), 1e-9)
    }
}