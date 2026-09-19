package com.fuelroute.domain.obd

import com.fuelroute.data.obd.ObdTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scripted transport: returns canned replies, then empty strings after [failAfter] calls. */
private class ScriptedTransport(
    private val responses: Map<String, String>,
    private val failAfter: Int = Int.MAX_VALUE,
) : ObdTransport {

    private var calls = 0
    private var connected = false

    override val isConnected: Boolean
        get() = connected

    override val deviceName: String = "scripted"

    override suspend fun connect(): Result<Unit> {
        connected = true
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun sendCommand(command: String): String {
        calls++
        if (calls > failAfter) return ""
        return responses[command.trim().uppercase()] ?: ""
    }
}

class ObdConnectionPolicyTest {

    private suspend fun negotiate(transport: ObdTransport): Set<Int> {
        val replies = mutableMapOf<Int, String>()
        for (base in ElmProtocol.supportedPidBlocks) {
            replies[base] = transport.sendCommand(ElmProtocol.command(base))
        }
        return ElmProtocol.supportedPids(replies)
    }

    @Test
    fun `negotiation excludes 5E when 0100 omits it`() = runBlocking {
        val transport = ScriptedTransport(
            mapOf(
                "0100" to "41 00 BE 3F A8 13",
                "0120" to "NO DATA",
                "0140" to "NO DATA",
                "0160" to "NO DATA",
            ),
        )
        val supported = negotiate(transport)
        assertTrue(supported.contains(ElmProtocol.PID_SPEED))
        assertTrue(supported.contains(ElmProtocol.PID_RPM))
        assertTrue(supported.contains(ElmProtocol.PID_MAF))
        assertFalse(supported.contains(ElmProtocol.PID_FUEL_RATE))
    }

    @Test
    fun `negotiation includes 5E when the 0x40 bitmap advertises it`() = runBlocking {
        val transport = ScriptedTransport(
            mapOf(
                "0100" to "41 00 FF FF FF FF",
                "0120" to "41 20 FF FF FF FF",
                "0140" to "41 40 00 00 00 04",
                "0160" to "NO DATA",
            ),
        )
        val supported = negotiate(transport)
        assertTrue(supported.contains(ElmProtocol.PID_FUEL_RATE))
    }

    @Test
    fun `five consecutive empty speed replies trigger reconnect`() = runBlocking {
        val transport = ScriptedTransport(mapOf("010D" to "41 0D 3C"), failAfter = 2)
        var failures = 0
        repeat(7) {
            val reply = transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_SPEED))
            if (ElmProtocol.speed(reply) != null) failures = 0 else failures++
        }
        assertEquals(5, failures)
        assertTrue(ObdConnectionPolicy.shouldReconnect(failures))
    }

    @Test
    fun `a good reply resets the failure counter`() = runBlocking {
        val transport = ScriptedTransport(mapOf("010D" to "41 0D 3C"), failAfter = 0)
        var failures = 0
        repeat(4) {
            val reply = transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_SPEED))
            if (ElmProtocol.speed(reply) != null) failures = 0 else failures++
        }
        assertEquals(4, failures)
        assertFalse(ObdConnectionPolicy.shouldReconnect(failures))
    }

    @Test
    fun `backoff grows exponentially then plateaus at 60s`() {
        assertEquals(2_000L, ObdConnectionPolicy.backoffDelayMs(0))
        assertEquals(4_000L, ObdConnectionPolicy.backoffDelayMs(1))
        assertEquals(8_000L, ObdConnectionPolicy.backoffDelayMs(2))
        assertEquals(16_000L, ObdConnectionPolicy.backoffDelayMs(3))
        assertEquals(32_000L, ObdConnectionPolicy.backoffDelayMs(4))
        assertEquals(60_000L, ObdConnectionPolicy.backoffDelayMs(5))
        assertEquals(60_000L, ObdConnectionPolicy.backoffDelayMs(20))
    }
}
