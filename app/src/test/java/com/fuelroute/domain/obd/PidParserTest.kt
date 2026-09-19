package com.fuelroute.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PidParserTest {

    @Test
    fun `parses vehicle speed`() {
        assertEquals(60.0, ElmProtocol.speed("41 0D 3C")!!, 1e-9)
    }

    @Test
    fun `tolerates echo and searching noise`() {
        assertEquals(60.0, ElmProtocol.speed("SEARCHING...\r41 0D 3C\r>")!!, 1e-9)
    }

    @Test
    fun `returns null on protocol errors`() {
        assertNull(ElmProtocol.speed("NO DATA"))
        assertNull(ElmProtocol.speed("?"))
        assertNull(ElmProtocol.speed("UNABLE TO CONNECT"))
        assertNull(ElmProtocol.speed("CAN ERROR"))
        assertNull(ElmProtocol.speed(""))
    }

    @Test
    fun `parses rpm`() {
        assertEquals(1726.0, ElmProtocol.rpm("41 0C 1A F8")!!, 1e-9)
    }

    @Test
    fun `parses mass air flow`() {
        assertEquals(14.0, ElmProtocol.mafGps("41 10 05 78")!!, 1e-9)
    }

    @Test
    fun `parses direct fuel rate`() {
        assertEquals(7.5, ElmProtocol.fuelRateLph("41 5E 00 96")!!, 1e-9)
    }

    @Test
    fun `parses coolant temperature`() {
        assertEquals(83.0, ElmProtocol.coolantTempC("41 05 7B")!!, 1e-9)
    }

    @Test
    fun `parses intake temperature`() {
        assertEquals(24.0, ElmProtocol.intakeTempC("41 0F 40")!!, 1e-9)
    }

    @Test
    fun `parses manifold pressure`() {
        assertEquals(100.0, ElmProtocol.mapKpa("41 0B 64")!!, 1e-9)
    }

    @Test
    fun `parses engine load`() {
        assertEquals(128 * 100.0 / 255.0, ElmProtocol.engineLoadPct("41 04 80")!!, 1e-3)
    }

    @Test
    fun `decodes supported pid bitmap`() {
        val pids = ElmProtocol.supportedPids("41 00 BE 3F A8 13")!!
        assertTrue(pids.contains(0x01))
        assertTrue(pids.contains(0x0D))
    }

    @Test
    fun `ignores the wrong pid in a response`() {
        assertNull(ElmProtocol.speed("41 0C 1A F8"))
    }

    @Test
    fun `parses hex with spaces off (ATS0 clone)`() {
        assertEquals(60.0, ElmProtocol.speed("410D3C")!!, 1e-9)
        assertEquals(1726.0, ElmProtocol.rpm("410C1AF8")!!, 1e-9)
        assertEquals(7.5, ElmProtocol.fuelRateLph("415E0096")!!, 1e-9)
        assertEquals(83.0, ElmProtocol.coolantTempC("41057B")!!, 1e-9)
    }

    @Test
    fun `parses unspaced data after searching noise`() {
        assertEquals(60.0, ElmProtocol.speed("SEARCHING...\r410D3C\r>")!!, 1e-9)
    }

    @Test
    fun `builds mode 01 commands`() {
        assertEquals("010D", ElmProtocol.command(ElmProtocol.PID_SPEED))
        assertEquals("015E", ElmProtocol.command(ElmProtocol.PID_FUEL_RATE))
    }
}