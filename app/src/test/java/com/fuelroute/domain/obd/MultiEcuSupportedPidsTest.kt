package com.fuelroute.domain.obd

import com.fuelroute.data.obd.FakeObdTransport
import com.fuelroute.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Supported-PID negotiation when several ECUs answer (fixture
 * `fixtures/obd/multi-ecu-supported-pids.txt`): the bitmaps of all ECUs must be OR-ed, whatever
 * the reply order and whether the adapter prints headers or not.
 */
class MultiEcuSupportedPidsTest {

    private val script = FakeObdTransport.parseScript(Fixtures.read("fixtures/obd/multi-ecu-supported-pids.txt"))

    /** The four block replies (0100/0120/0140/0160) of scenario [index] (0-based). */
    private fun scenario(index: Int): Map<Int, String> {
        val entries = script.subList(index * 4, index * 4 + 4)
        return ElmProtocol.supportedPidBlocks.zip(entries).associate { (base, entry) ->
            assertEquals(ElmProtocol.command(base), entry.command)
            base to entry.response
        }
    }

    private val ecmOnly = setOf(0x03, 0x06, 0x07, 0x0B, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x14, 0x15, 0x1C, 0x1F, 0x46, 0x49, 0x5E)
    private val shared = setOf(0x01, 0x04, 0x05, 0x0C, 0x0D, 0x20, 0x21, 0x42)

    @Test
    fun `fixture has four scenarios`() {
        assertEquals(16, script.size)
    }

    @Test
    fun `transmission answering first no longer hides the engine ECU's PIDs`() {
        for (index in 0 until 4) {
            val support = ElmProtocol.negotiateSupport(scenario(index))
            assertFalse("scenario ${index + 1}", support.negotiationFailed)
            assertEquals("scenario ${index + 1}", ecmOnly + shared, support.pids)
            assertTrue(ElmProtocol.PID_MAF in support.pids)
            assertTrue(ElmProtocol.PID_FUEL_RATE in support.pids)
            assertTrue(ElmProtocol.PID_MAP in support.pids)
        }
    }

    @Test
    fun `a single reply with two ECUs is the union of both bitmaps`() {
        val raw = "41 00 98 18 00 01\r41 00 BE 3F B8 13\r\r>"
        val reversed = "41 00 BE 3F B8 13\r41 00 98 18 00 01\r\r>"
        val union = PidParser.parseSupportedPids(raw, 0x00)
        assertEquals(union, PidParser.parseSupportedPids(reversed, 0x00))
        assertEquals(PidParser.parseSupportedPids("41 00 BE 3F B8 13", 0x00), union)
    }

    @Test
    fun `the protocol search still locks on a multi ECU reply with headers`() {
        assertEquals(
            ElmProtocol.SearchOutcome.LOCKED,
            ElmProtocol.classifySearchReply("SEARCHING...\r7E9 06 41 00 98 18 00 01\r7E8 06 41 00 BE 3F B8 13\r"),
        )
    }

    @Test
    fun `a truncated second ECU does not poison the first one`() {
        val pids = PidParser.parseSupportedPids("41 00 BE 3F B8 13\r41 00 98 18", 0x00)
        assertEquals(PidParser.parseSupportedPids("41 00 BE 3F B8 13", 0x00), pids)
    }

    @Test
    fun `header tolerance never reads a bitmap out of another PID's data`() {
        // Stale 010C reply whose data bytes happen to look like "41 00 ..." at a header offset.
        assertNull(PidParser.parseSupportedPids("41 0C 41 00 BE 3F B8 13", 0x00))
        // Wrong PCI length for an 11-bit header.
        assertNull(PidParser.parseSupportedPids("7E8 04 41 00 BE 3F B8 13", 0x00))
        // The strict single-PID parser still ignores header lines (the app runs with ATH0).
        assertNull(PidParser.parseMode01Bytes("7E8 03 41 0D 3C", ElmProtocol.PID_SPEED))
    }

    @Test
    fun `every ECU's data bytes are reported in reply order`() {
        val replies = PidParser.parseMode01Replies("7E9 03 41 0D 00\r7E8 03 41 0D 3C\r", ElmProtocol.PID_SPEED)
        assertEquals(listOf(listOf(0x00), listOf(0x3C)), replies)
    }
}
