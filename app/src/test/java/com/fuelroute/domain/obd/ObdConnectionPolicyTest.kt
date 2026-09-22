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

    @Test
    fun `connect chain is attempted three times`() {
        assertEquals(3, ObdConnectionPolicy.connectAttempts())
        assertEquals(ObdConnectionPolicy.CONNECT_ATTEMPTS, ObdConnectionPolicy.connectAttempts())
        // attempt is 1-based: 1 and 2 may retry, the 3rd is the last.
        assertTrue(ObdConnectionPolicy.shouldRetryConnect(1))
        assertTrue(ObdConnectionPolicy.shouldRetryConnect(2))
        assertFalse(ObdConnectionPolicy.shouldRetryConnect(3))
        assertFalse(ObdConnectionPolicy.shouldRetryConnect(4))
    }

    @Test
    fun `workaround order is secure then insecure then channel 1`() {
        assertEquals(
            listOf(
                ObdConnectionPolicy.ConnectVariant.SECURE_RFCOMM,
                ObdConnectionPolicy.ConnectVariant.INSECURE_RFCOMM,
                ObdConnectionPolicy.ConnectVariant.CHANNEL_1,
            ),
            ObdConnectionPolicy.connectVariants(),
        )
    }

    @Test
    fun `reconnect retries while attempts remain and the dongle is connected`() {
        // 0-based attempt: three attempts (0, 1, 2) are allowed while ACL-connected.
        assertTrue(ObdConnectionPolicy.shouldRetryReconnect(0, deviceConnected = true))
        assertTrue(ObdConnectionPolicy.shouldRetryReconnect(1, deviceConnected = true))
        assertTrue(ObdConnectionPolicy.shouldRetryReconnect(2, deviceConnected = true))
        // Attempt 3 exceeds the cap → give up.
        assertFalse(ObdConnectionPolicy.shouldRetryReconnect(3, deviceConnected = true))
    }

    @Test
    fun `reconnect gives up immediately once the dongle is not connected`() {
        assertFalse(ObdConnectionPolicy.shouldRetryReconnect(0, deviceConnected = false))
        assertFalse(ObdConnectionPolicy.shouldRetryReconnect(1, deviceConnected = false))
        assertFalse(ObdConnectionPolicy.shouldRetryReconnect(2, deviceConnected = false))
    }

    // --- Ignition-off detection (bug #1) ---------------------------------------------------

    @Test
    fun `implausible low voltage readings never stop the engine`() {
        val now = 100_000L
        // Cheap clones answer ATRV with their internal rail (0 / 3.3 / 5 V); none of these
        // is evidence that the engine is off.
        assertFalse(ObdConnectionPolicy.shouldStopForIgnitionOff(null, now, 0.0))
        assertFalse(ObdConnectionPolicy.shouldStopForIgnitionOff(null, now, 3.3))
        assertFalse(ObdConnectionPolicy.shouldStopForIgnitionOff(null, now, 5.0))
        assertFalse(ObdConnectionPolicy.shouldStopForIgnitionOff(null, now, 7.9))
    }

    @Test
    fun `a healthy battery reading does not stop the engine`() {
        assertFalse(ObdConnectionPolicy.shouldStopForIgnitionOff(null, 0L, 12.0))
        assertFalse(ObdConnectionPolicy.shouldStopForIgnitionOff(null, 0L, 12.6))
        assertFalse(ObdConnectionPolicy.shouldStopForIgnitionOff(null, 0L, 14.2))
    }

    @Test
    fun `a plausible but low voltage stops the engine`() {
        assertTrue(ObdConnectionPolicy.shouldStopForIgnitionOff(null, 0L, 11.4))
        assertTrue(ObdConnectionPolicy.shouldStopForIgnitionOff(null, 0L, 8.0))
    }

    @Test
    fun `rpm missing past the timeout stops the engine regardless of voltage`() {
        val since = 1_000L
        assertTrue(
            ObdConnectionPolicy.shouldStopForIgnitionOff(
                rpmNullSinceMs = since,
                nowMs = since + ObdConnectionPolicy.IGNITION_OFF_RPM_TIMEOUT_MS,
                batteryVoltage = 12.6,
            ),
        )
        assertFalse(
            ObdConnectionPolicy.shouldStopForIgnitionOff(
                rpmNullSinceMs = since,
                nowMs = since + ObdConnectionPolicy.IGNITION_OFF_RPM_TIMEOUT_MS - 1,
                batteryVoltage = 12.6,
            ),
        )
    }

    @Test
    fun `null voltage and live rpm never stop the engine`() {
        assertFalse(ObdConnectionPolicy.shouldStopForIgnitionOff(null, 500_000L, null))
    }

    // --- Mid-session auto-reconnect (bug #2) ----------------------------------------------

    @Test
    fun `auto-reconnect is allowed while the budget remains and the dongle is reachable`() {
        for (attempt in 0 until ObdConnectionPolicy.MAX_AUTO_RECONNECT_ATTEMPTS) {
            assertTrue(
                ObdConnectionPolicy.shouldAutoReconnect(
                    attempt = attempt,
                    autoConnect = true,
                    manualDisconnect = false,
                    deviceConnected = true,
                ),
            )
        }
    }

    @Test
    fun `auto-reconnect stops once the attempt budget is exhausted`() {
        assertFalse(
            ObdConnectionPolicy.shouldAutoReconnect(
                attempt = ObdConnectionPolicy.MAX_AUTO_RECONNECT_ATTEMPTS,
                autoConnect = true,
                manualDisconnect = false,
                deviceConnected = true,
            ),
        )
        assertFalse(
            ObdConnectionPolicy.shouldAutoReconnect(
                attempt = ObdConnectionPolicy.MAX_AUTO_RECONNECT_ATTEMPTS + 3,
                autoConnect = true,
                manualDisconnect = false,
                deviceConnected = true,
            ),
        )
    }

    @Test
    fun `auto-reconnect is suppressed by user disconnect, disabled auto-connect or absent dongle`() {
        assertFalse(
            ObdConnectionPolicy.shouldAutoReconnect(0, autoConnect = false, manualDisconnect = false, deviceConnected = true),
        )
        assertFalse(
            ObdConnectionPolicy.shouldAutoReconnect(0, autoConnect = true, manualDisconnect = true, deviceConnected = true),
        )
        assertFalse(
            ObdConnectionPolicy.shouldAutoReconnect(0, autoConnect = true, manualDisconnect = false, deviceConnected = false),
        )
    }
}
