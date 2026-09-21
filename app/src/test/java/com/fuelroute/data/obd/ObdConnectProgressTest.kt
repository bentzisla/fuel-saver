package com.fuelroute.data.obd

import com.fuelroute.domain.obd.ObdConnectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card 43: the connect pipeline must expose a visible stage + start timestamp, and a silent
 * dongle must fail init fast. The engine itself needs Android/Room, so this locks down the
 * pure state contract and the policy constant the engine relies on.
 */
class ObdConnectProgressTest {

    @Test
    fun `fresh state has no connect stage and no start timestamp`() {
        val state = LiveObdState()
        assertNull(state.connectionStage)
        assertNull(state.connectingSinceMs)
    }

    @Test
    fun `connect stages are ordered socket to vin`() {
        assertEquals(
            listOf(
                ObdConnectStage.ConnectingSocket,
                ObdConnectStage.InitializingElm,
                ObdConnectStage.SettlingProtocol,
                ObdConnectStage.NegotiatingPids,
                ObdConnectStage.ReadingVin,
            ),
            ObdConnectStage.entries.toList(),
        )
    }

    @Test
    fun `stage and timestamp are set while connecting and cleared when connected`() {
        val connecting = LiveObdState().copy(
            status = ObdStatus.Connecting,
            connectionStage = ObdConnectStage.InitializingElm,
            connectingSinceMs = 1_234L,
        )
        assertEquals(ObdConnectStage.InitializingElm, connecting.connectionStage)
        assertEquals(1_234L, connecting.connectingSinceMs ?: -1L)

        val connected = connecting.copy(
            status = ObdStatus.Connected,
            connectionStage = null,
            connectingSinceMs = null,
        )
        assertNull(connected.connectionStage)
        assertNull(connected.connectingSinceMs)
    }

    @Test
    fun `init read timeout fails faster than a full connect attempt`() {
        assertTrue(ObdConnectionPolicy.INIT_READ_TIMEOUT_MS > 0)
        assertTrue(ObdConnectionPolicy.INIT_READ_TIMEOUT_MS < ObdConnectionPolicy.CONNECT_TIMEOUT_MS)
        assertEquals("INIT TIMEOUT", ObdConnectionPolicy.ERROR_INIT_TIMEOUT)
    }
}
