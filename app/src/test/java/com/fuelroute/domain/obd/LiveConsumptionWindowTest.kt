package com.fuelroute.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveConsumptionWindowTest {

    @Test
    fun `steady cruise gives fuel over distance`() {
        val window = LiveConsumptionWindow()
        var last: LiveConsumption = LiveConsumption.EMPTY
        for (i in 0..20) last = window.add(i * 1000L, 90.0, 6.0)
        assertEquals(6.6667, last.litersPer100Km!!, 1e-3)
        assertEquals(6.0, last.litersPerHour!!, 1e-9)
    }

    @Test
    fun `crawling shows liters per hour and no liters per 100 km`() {
        val window = LiveConsumptionWindow()
        var last: LiveConsumption = LiveConsumption.EMPTY
        // Pulling away: 2-3 km/h at 15-20 L/h used to show 500-1000 L/100 km.
        listOf(0.0 to 0.8, 2.0 to 15.0, 3.0 to 18.0, 5.0 to 20.0).forEachIndexed { i, (v, r) ->
            last = window.add(i * 1000L, v, r)
            assertNull("L/100 at $v km/h must be hidden", last.litersPer100Km)
        }
        assertNotNull(last.litersPerHour)
        assertTrue(last.litersPerHour!! in 0.8..20.0)
    }

    @Test
    fun `display is capped and never absurd while accelerating`() {
        val window = LiveConsumptionWindow()
        var max = 0.0
        val speeds = listOf(0, 2, 4, 6, 8, 9, 10, 12, 14, 16, 18, 20)
        speeds.forEachIndexed { i, v ->
            window.add(i * 1000L, v.toDouble(), 25.0).litersPer100Km?.let { max = maxOf(max, it) }
        }
        assertTrue("max=$max", max <= LiveConsumptionWindow.MAX_DISPLAY_L100)
    }

    @Test
    fun `hysteresis keeps liters per 100 km between exit and enter thresholds`() {
        val window = LiveConsumptionWindow()
        for (i in 0..10) window.add(i * 1000L, 30.0, 5.0)
        // Slowing to 6 km/h: still "moving" (exit threshold is 5).
        val slow = window.add(11_000L, 6.0, 1.5)
        assertNotNull(slow.litersPer100Km)
        val stopped = window.add(12_000L, 0.0, 0.8)
        assertNull(stopped.litersPer100Km)
    }

    @Test
    fun `a long gap resets the window`() {
        val window = LiveConsumptionWindow()
        for (i in 0..10) window.add(i * 1000L, 50.0, 5.0)
        val afterGap = window.add(60_000L, 50.0, 5.0)
        assertNull(afterGap.litersPer100Km)
    }

    @Test
    fun `missing values are skipped not guessed`() {
        val window = LiveConsumptionWindow()
        window.add(0L, 50.0, 5.0)
        window.add(1000L, null, 5.0)
        val r = window.add(2000L, 50.0, null)
        assertNull(r.litersPer100Km)
    }
}
