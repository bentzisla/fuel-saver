package com.fuelroute.domain.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectPolicyTest {

    @Test
    fun `non-terminal statuses never stop the service`() {
        assertFalse(ConnectPolicy.shouldAutoStop(ConnectPolicy.STATUS_CONNECTING, 60_000L, false))
        assertFalse(ConnectPolicy.shouldAutoStop(ConnectPolicy.STATUS_CONNECTED, 60_000L, true))
    }

    @Test
    fun `a terminal state without data waits out the startup grace`() {
        // The engine's initial Disconnected value must not kill a freshly started service.
        assertFalse(ConnectPolicy.shouldAutoStop(ConnectPolicy.STATUS_DISCONNECTED, 0L, false))
        assertFalse(ConnectPolicy.shouldAutoStop(ConnectPolicy.STATUS_DISCONNECTED, 1_999L, false))
        assertFalse(ConnectPolicy.shouldAutoStop(ConnectPolicy.STATUS_ERROR, 1_999L, false))
    }

    @Test
    fun `a terminal state without data stops once the grace has elapsed`() {
        assertTrue(ConnectPolicy.shouldAutoStop(ConnectPolicy.STATUS_DISCONNECTED, 2_000L, false))
        assertTrue(ConnectPolicy.shouldAutoStop(ConnectPolicy.STATUS_ERROR, 2_000L, false))
        assertTrue(ConnectPolicy.shouldAutoStop(ConnectPolicy.STATUS_ERROR, 30_000L, false))
    }

    @Test
    fun `a terminal state after the engine was active stops immediately`() {
        // sawData means the connect attempt was actually entered, so Disconnected/Error is
        // unambiguous even inside the grace window (e.g. ignition off right after connect).
        assertTrue(ConnectPolicy.shouldAutoStop(ConnectPolicy.STATUS_DISCONNECTED, 10L, true))
        assertTrue(ConnectPolicy.shouldAutoStop(ConnectPolicy.STATUS_ERROR, 10L, true))
    }

    @Test
    fun `adapter-on only starts for a resolved target that is actually connected`() {
        assertFalse(ConnectPolicy.shouldAutoStartOnAdapterOn(null, setOf("AA:BB")))
        assertFalse(ConnectPolicy.shouldAutoStartOnAdapterOn("", setOf("AA:BB")))
        assertFalse(ConnectPolicy.shouldAutoStartOnAdapterOn("AA:BB", emptySet()))
        assertFalse(ConnectPolicy.shouldAutoStartOnAdapterOn("AA:BB", setOf("CC:DD")))
    }

    @Test
    fun `adapter-on starts for a connected target regardless of address case`() {
        assertTrue(ConnectPolicy.shouldAutoStartOnAdapterOn("AA:BB", setOf("AA:BB")))
        assertTrue(ConnectPolicy.shouldAutoStartOnAdapterOn("aa:bb", setOf("AA:BB")))
        assertTrue(ConnectPolicy.shouldAutoStartOnAdapterOn("AA:BB", setOf("CC:DD", "AA:BB")))
    }
}
