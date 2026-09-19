package com.fuelroute.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mode 09 PID 02 (VIN) must decode the three shapes a real adapter produces:
 * a single CAN frame, the legacy multi-line counter format, and CAN ISO-TP
 * continuation lines.
 */
class PidParserVinTest {

    @Test
    fun `parses single-line CAN VIN`() {
        val raw = "49 02 01 31 48 47 43 4D 38 32 36 33 33 41 30 30 34 33 35 32"
        assertEquals("1HGCM82633A004352", PidParser.parseVin(raw))
        assertEquals("1HGCM82633A004352", ElmProtocol.vin(raw))
    }

    @Test
    fun `parses legacy multi-line numeric counters`() {
        val raw = listOf(
            "49 02 01 00 00 00 31",
            "49 02 02 47 31 4A 43",
            "49 02 03 35 34 34 34",
            "49 02 04 52 37 32 35",
            "49 02 05 32 34 36 37",
        ).joinToString("\r")
        assertEquals("1G1JC5444R7252467", PidParser.parseVin(raw))
    }

    @Test
    fun `parses legacy multi-line ascii counters`() {
        val raw = listOf(
            "49 02 31 31 48 47 43",
            "49 02 32 4D 38 32 36",
            "49 02 33 33 33 41 30",
            "49 02 34 30 34 33 35",
            "49 02 35 32 00 00 00",
        ).joinToString("\r")
        assertEquals("1HGCM82633A004352", PidParser.parseVin(raw))
    }

    @Test
    fun `parses CAN ISO-TP continuation lines`() {
        val raw = listOf(
            "0: 49 02 01 31 48 47 43 4D",
            "1: 38 32 36 33 33 41 30",
            "2: 30 34 33 35 32",
        ).joinToString("\r")
        assertEquals("1HGCM82633A004352", PidParser.parseVin(raw))
    }

    @Test
    fun `returns null for errors and short payloads`() {
        assertNull(PidParser.parseVin("NO DATA"))
        assertNull(PidParser.parseVin(""))
        assertNull(PidParser.parseVin("49 02 01 31 48"))
    }

    @Test
    fun `mode09DataBytes ignores frame framing`() {
        val data = PidParser.mode09DataBytes(
            "49 02 01 31 48 47 43 4D 38 32 36 33 33 41 30 30 34 33 35 32",
            ElmProtocol.PID_VIN,
        )
        assertEquals(17, data?.size)
        assertEquals(0x31, data?.first())
    }
}
