package com.fuelroute.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapter pathologies that could turn into absurd values (0.7 obd-data, Bug A). */
class PidParserRobustnessTest {

    @Test
    fun `a reply to a different pid is never accepted even if its data contains 41 pid`() {
        // Late RPM reply whose data bytes happen to be "41 0D": the old scanner took 0x7E = 126 km/h.
        assertNull(ElmProtocol.speed("41 0C 41 0D 7E"))
        assertNull(ElmProtocol.speed("41 5E 01 7C"))
        assertNull(ElmProtocol.rpm("41 0D 3C"))
    }

    @Test
    fun `a stale reply followed by the right one picks the right one`() {
        assertEquals(60.0, ElmProtocol.speed("41 0C 1A F8\r41 0D 3C\r>")!!, 1e-9)
    }

    @Test
    fun `multiple ECUs - first complete message wins and bytes are never concatenated`() {
        assertEquals(14.0, ElmProtocol.mafGps("41 10 05 78\r41 10 00 00\r")!!, 1e-9)
        // First ECU's reply is truncated: the old parser concatenated 01 + 41 -> (0x0141)/20 = 16.05 L/h.
        assertEquals(20.0, ElmProtocol.fuelRateLph("41 5E 01\r41 5E 01 90\r")!!, 1e-9)
    }

    @Test
    fun `truncated replies without prompt are rejected`() {
        assertNull(ElmProtocol.rpm("41 0C 1A"))
        assertNull(ElmProtocol.mafGps("41 10 0F"))
        assertNull(ElmProtocol.speed("41 0D"))
        assertNull(ElmProtocol.speed("41 0D 3"))
    }

    @Test
    fun `adapter corruption markers invalidate the whole reply`() {
        assertNull(ElmProtocol.speed("41 0D 3C <DATA ERROR"))
        assertNull(ElmProtocol.speed("41 0D 3C\r<RX ERROR"))
        assertNull(ElmProtocol.speed("41 0D 3C\rSTOPPED"))
        assertNull(ElmProtocol.speed("CAN ERROR\r41 0D 3C"))
        assertNull(ElmProtocol.speed("BUFFER FULL"))
        assertNull(ElmProtocol.speed("ERR94"))
    }

    @Test
    fun `status noise in front of data is tolerated`() {
        assertEquals(12.0, ElmProtocol.speed("BUS INIT: ...OK\r41 0D 0C")!!, 1e-9)
        assertEquals(12.0, ElmProtocol.speed("SEARCHING...41 0D 0C")!!, 1e-9)
        assertEquals(12.0, ElmProtocol.speed("010D\r41 0D 0C\r\r>")!!, 1e-9)
        assertNull(ElmProtocol.speed("BUS INIT: ...ERROR"))
    }

    @Test
    fun `CAN ISO-TP multi-frame mode 01 reply is joined`() {
        val raw = "00A\r0: 41 00 BE 3F A8\r1: 13 00 00 00 00 00 00\r>"
        val pids = ElmProtocol.supportedPids(raw)!!
        assertTrue(pids.contains(0x0D))
        assertTrue(pids.contains(0x01))
    }

    @Test
    fun `messages splits lines and drops non hex`() {
        val messages = PidParser.messages("SEARCHING...\r41 0D 3C\rNO DATA\r410C1AF8\r>")
        assertEquals(listOf(listOf(0x41, 0x0D, 0x3C), listOf(0x41, 0x0C, 0x1A, 0xF8)), messages)
    }
}
