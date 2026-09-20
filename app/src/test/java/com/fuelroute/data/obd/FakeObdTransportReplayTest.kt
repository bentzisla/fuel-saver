package com.fuelroute.data.obd

import com.fuelroute.domain.obd.ElmProtocol
import com.fuelroute.domain.obd.PidParser
import com.fuelroute.testutil.Fixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the recorded session in `fixtures/obd/ta-jerusalem-session.txt` through
 * the same [PidParser]/[ElmProtocol] pipeline the service uses, including a
 * `SEARCHING...` marker, a `NO DATA` reply and a multi-frame VIN.
 */
class FakeObdTransportReplayTest {

    private fun recordedScript() = FakeObdTransport.parseScript(
        Fixtures.read("fixtures/obd/ta-jerusalem-session.txt"),
    )

    @Test
    fun `parses the script format ignoring comments and honouring delays`() {
        val script = FakeObdTransport.parseScript(
            """
            # comment
            delay=20
            ATZ> ELM327 v1.5

            0902> SEARCHING...\n49 02 01 31
            """.trimIndent(),
        )

        assertEquals(2, script.size)
        assertEquals(FakeObdTransport.ScriptedResponse("ATZ", "ELM327 v1.5", 20L), script[0])
        assertTrue(script[1].response.contains('\n'))
        assertEquals(0L, script[1].delayMs)
    }

    @Test
    fun `replays init vin and samples through the parser`() = runTest {
        val transport = FakeObdTransport(recordedScript(), deviceName = "replay")

        assertTrue(transport.connect().isSuccess)
        assertTrue(transport.isConnected)

        listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH0", "ATSP0", "ATST64", "0100", "0120", "0140", "0160")
            .forEach { transport.sendCommand(it) }

        val vinReply = transport.sendCommand("0902")
        assertTrue(vinReply.contains("SEARCHING"))
        assertEquals("1HGCM82633A004352", PidParser.parseVin(vinReply))

        assertEquals(60.0, ElmProtocol.speed(transport.sendCommand("010D"))!!, 1e-9)
        assertEquals(1726.0, ElmProtocol.rpm(transport.sendCommand("010C"))!!, 1e-9)
        assertEquals(14.0, ElmProtocol.mafGps(transport.sendCommand("0110"))!!, 1e-9)
        assertEquals(7.5, ElmProtocol.fuelRateLph(transport.sendCommand("015E"))!!, 1e-9)
        assertEquals(83.0, ElmProtocol.coolantTempC(transport.sendCommand("0105"))!!, 1e-9)

        // Second recorded sample: faster, and fuel rate is unavailable on this reply.
        assertEquals(90.0, ElmProtocol.speed(transport.sendCommand("010D"))!!, 1e-9)
        assertEquals(2000.0, ElmProtocol.rpm(transport.sendCommand("010C"))!!, 1e-9)
        assertEquals(22.0, ElmProtocol.mafGps(transport.sendCommand("0110"))!!, 1e-9)
        assertNull(ElmProtocol.fuelRateLph(transport.sendCommand("015E")))
        assertEquals(85.0, ElmProtocol.coolantTempC(transport.sendCommand("0105"))!!, 1e-9)

        assertEquals(22, transport.replayedCount)
    }

    @Test
    fun `map mode still answers by command and returns no data when exhausted`() = runTest {
        val transport = FakeObdTransport()
        transport.connect()

        assertEquals("41 0D 3C", transport.sendCommand("010d"))
        assertEquals("NO DATA", transport.sendCommand("9999"))
    }

    @Test
    fun `script mode returns no data once the recording is exhausted`() = runTest {
        val transport = FakeObdTransport(
            listOf(FakeObdTransport.ScriptedResponse("ATZ", "ELM327 v1.5")),
        )
        transport.connect()

        assertEquals("ELM327 v1.5", transport.sendCommand("ATZ"))
        assertEquals("NO DATA", transport.sendCommand("ATZ"))
    }
}