package com.fuelroute.data.obd

import com.fuelroute.domain.obd.ObdProbePolicy
import com.fuelroute.domain.obd.ObdProbePolicy.Outcome
import com.fuelroute.testutil.Fixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The probe's ELM conversation: RPM decides, the remembered protocol avoids the search. */
class ObdProbeConversationTest {

    /** Answers from a command map; records every command. The bare-CR line clear answers `>`. */
    private class MapTransport(private val answer: (String) -> String?) : ObdTransport {
        val commands = mutableListOf<String>()
        override var isConnected = true
        override val deviceName = "probe-test"
        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {
            isConnected = false
        }
        override suspend fun sendCommand(command: String): String {
            commands += command
            return answer(command) ?: "NO DATA"
        }
        override suspend fun sendSoft(command: String, timeoutMs: Long): String =
            if (command.isEmpty()) ">" else sendCommand(command) + ">"
    }

    /** Replays a recorded script strictly in order, asserting each command. */
    private class ScriptTransport(private val script: List<FakeObdTransport.ScriptedResponse>) : ObdTransport {
        var index = 0
        override val isConnected = true
        override val deviceName = "probe-replay"
        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() = Unit
        override suspend fun sendCommand(command: String): String {
            val entry = script[index++]
            assertEquals("command #$index", entry.command, command)
            return entry.response
        }
        override suspend fun sendSoft(command: String, timeoutMs: Long): String =
            if (command.isEmpty()) ">" else sendCommand(command) + ">"
    }

    private fun car(
        volts: String = "12.6V",
        protocol: Int? = 6,
        runningRpmReply: String? = "41 0C 0B 68",
        fixedFailReply: String = "NO DATA",
    ): (String) -> String? {
        var selected: String? = null
        return { command ->
            when {
                command == "ATZ" -> "ELM327 v1.5"
                command == "ATRV" -> volts
                command.startsWith("ATSP") -> { selected = command; "OK" }
                command == "010C" -> when {
                    runningRpmReply == null -> fixedFailReply
                    selected == "ATSP0" -> "SEARCHING...\r$runningRpmReply"
                    selected == "ATSP" + protocol?.toString(16)?.uppercase() -> runningRpmReply
                    else -> fixedFailReply
                }
                command == "ATDPN" -> if (selected == "ATSP0") "A${protocol?.toString(16)?.uppercase()}" else protocol?.toString(16)?.uppercase()
                else -> "OK"
            }
        }
    }

    @Test
    fun `recorded smart alternator drive at 12_6 V is detected as running`() = runTest {
        val script = FakeObdTransport.parseScript(Fixtures.read("fixtures/obd/probe-smart-alternator.txt"))
        val transport = ScriptTransport(script)

        val result = ObdProbeConversation().run(transport, knownProtocol = null, offStreak = 0)

        assertEquals(Outcome.ENGINE_RUNNING, result.outcome)
        assertEquals(6, result.lockedProtocol)
        assertEquals(12.6, result.volts!!, 1e-9)
        assertEquals(script.size, transport.index)
    }

    @Test
    fun `a known protocol is selected directly instead of searching`() = runTest {
        val transport = MapTransport(car(protocol = 6))
        val result = ObdProbeConversation().run(transport, knownProtocol = 6, offStreak = 0)

        assertEquals(Outcome.ENGINE_RUNNING, result.outcome)
        assertEquals(6, result.lockedProtocol)
        assertTrue(transport.commands.toString(), "ATSP6" in transport.commands)
        assertFalse(transport.commands.toString(), "ATSP0" in transport.commands)
    }

    @Test
    fun `a failing remembered protocol falls back to the automatic search`() = runTest {
        // The dongle moved to a car on protocol 3 (ISO 9141); the remembered 6 gets no answer.
        val transport = MapTransport(car(protocol = 3, fixedFailReply = "CAN ERROR"))
        val result = ObdProbeConversation().run(transport, knownProtocol = 6, offStreak = 0)

        assertEquals(listOf("ATSP6", "ATSP0"), transport.commands.filter { it.startsWith("ATSP") })
        assertEquals(Outcome.ENGINE_RUNNING, result.outcome)
        assertEquals(3, result.lockedProtocol)
    }

    @Test
    fun `a parked car's remembered protocol is trusted without a search`() = runTest {
        val transport = MapTransport(car(protocol = 6, runningRpmReply = null))
        val result = ObdProbeConversation().run(
            transport,
            knownProtocol = 6,
            offStreak = ObdProbePolicy.TRUST_PROTOCOL_AFTER_OFF,
        )

        assertEquals(Outcome.ENGINE_OFF, result.outcome)
        assertEquals(listOf("ATSP6"), transport.commands.filter { it.startsWith("ATSP") })
        assertEquals(1, transport.commands.count { it == "010C" })
        assertNull(result.lockedProtocol)
    }

    @Test
    fun `ignition off with an unknown protocol reads engine off after one search`() = runTest {
        val transport = MapTransport(car(runningRpmReply = null, fixedFailReply = "SEARCHING...\rUNABLE TO CONNECT"))
        val result = ObdProbeConversation().run(transport, knownProtocol = null, offStreak = 0)

        assertEquals(Outcome.ENGINE_OFF, result.outcome)
        assertEquals(listOf("ATSP0"), transport.commands.filter { it.startsWith("ATSP") })
        assertFalse("ATDPN" in transport.commands)
    }

    @Test
    fun `a clone's own rail voltage does not stop the RPM check`() = runTest {
        for (volts in listOf("0.0V", "3.3V", "5.0V", "?")) {
            val transport = MapTransport(car(volts = volts))
            val result = ObdProbeConversation().run(transport, knownProtocol = 6, offStreak = 0)
            assertEquals(volts, Outcome.ENGINE_RUNNING, result.outcome)
        }
    }

    @Test
    fun `ignition on with the engine stopped is engine off`() = runTest {
        val transport = MapTransport(car(runningRpmReply = "41 0C 00 00"))
        val result = ObdProbeConversation().run(transport, knownProtocol = 6, offStreak = 0)
        assertEquals(Outcome.ENGINE_OFF, result.outcome)
        assertEquals(6, result.lockedProtocol)
    }

    @Test
    fun `a silent adapter is unresponsive`() = runTest {
        val transport = MapTransport { "" }
        val result = ObdProbeConversation().run(transport, knownProtocol = 6, offStreak = 0)
        assertEquals(Outcome.UNRESPONSIVE, result.outcome)
    }
}
