package com.fuelroute.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElmProtocolBatteryTest {

    @Test
    fun `parses battery voltage with suffix`() {
        assertEquals(12.3, ElmProtocol.batteryVoltage("12.3V")!!, 1e-9)
    }

    @Test
    fun `parses battery voltage with trailing whitespace`() {
        assertEquals(12.6, ElmProtocol.batteryVoltage("12.6V\r")!!, 1e-9)
    }

    @Test
    fun `parses bare integer voltage`() {
        assertEquals(14.0, ElmProtocol.batteryVoltage("14")!!, 1e-9)
    }

    @Test
    fun `parses clone logic-rail voltages`() {
        assertEquals(3.3, ElmProtocol.batteryVoltage("3.3V")!!, 1e-9)
        assertEquals(0.0, ElmProtocol.batteryVoltage("0.0V")!!, 1e-9)
    }

    @Test
    fun `returns null for no data and connect errors`() {
        assertNull(ElmProtocol.batteryVoltage("NO DATA"))
        assertNull(ElmProtocol.batteryVoltage("UNABLE TO CONNECT"))
        assertNull(ElmProtocol.batteryVoltage(""))
    }

    @Test
    fun `returns null for the ELM error prompt`() {
        assertNull(ElmProtocol.batteryVoltage("?"))
    }

    @Test
    fun `low voltage is detected as ignition off`() {
        assertEquals(true, ObdConnectionPolicy.shouldStopForIgnitionOff(null, 0L, 11.2))
        assertEquals(false, ObdConnectionPolicy.shouldStopForIgnitionOff(null, 0L, 12.4))
    }

    @Test
    fun `rpm timeout is detected as ignition off`() {
        val since = 1_000L
        assertEquals(false, ObdConnectionPolicy.shouldStopForIgnitionOff(since, since + 30_000L, 12.4))
        assertEquals(true, ObdConnectionPolicy.shouldStopForIgnitionOff(since, since + 60_000L, 12.4))
    }
}
