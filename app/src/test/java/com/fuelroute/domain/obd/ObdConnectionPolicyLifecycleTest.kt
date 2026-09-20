package com.fuelroute.domain.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle/gating rules that used to live in the (now merged) `ConnectPolicy`:
 * logging-service auto-stop, adapter-on auto-start, the manual-disconnect latch,
 * and reconnect continuation.
 */
class ObdConnectionPolicyLifecycleTest {

    @Test
    fun `non-terminal statuses never stop the service`() {
        assertFalse(ObdConnectionPolicy.shouldAutoStop(ObdConnectionPolicy.STATUS_CONNECTING, 60_000L, false))
        assertFalse(ObdConnectionPolicy.shouldAutoStop(ObdConnectionPolicy.STATUS_CONNECTED, 60_000L, true))
    }

    @Test
    fun `a terminal state without data waits out the startup grace`() {
        // The engine's initial Disconnected value must not kill a freshly started service.
        assertFalse(ObdConnectionPolicy.shouldAutoStop(ObdConnectionPolicy.STATUS_DISCONNECTED, 0L, false))
        assertFalse(ObdConnectionPolicy.shouldAutoStop(ObdConnectionPolicy.STATUS_DISCONNECTED, 1_999L, false))
        assertFalse(ObdConnectionPolicy.shouldAutoStop(ObdConnectionPolicy.STATUS_ERROR, 1_999L, false))
    }

    @Test
    fun `a terminal state without data stops once the grace has elapsed`() {
        assertTrue(ObdConnectionPolicy.shouldAutoStop(ObdConnectionPolicy.STATUS_DISCONNECTED, 2_000L, false))
        assertTrue(ObdConnectionPolicy.shouldAutoStop(ObdConnectionPolicy.STATUS_ERROR, 2_000L, false))
        assertTrue(ObdConnectionPolicy.shouldAutoStop(ObdConnectionPolicy.STATUS_ERROR, 30_000L, false))
    }

    @Test
    fun `a terminal state after the engine was active stops immediately`() {
        // sawData means the connect attempt was actually entered, so Disconnected/Error is
        // unambiguous even inside the grace window (e.g. ignition off right after connect).
        assertTrue(ObdConnectionPolicy.shouldAutoStop(ObdConnectionPolicy.STATUS_DISCONNECTED, 10L, true))
        assertTrue(ObdConnectionPolicy.shouldAutoStop(ObdConnectionPolicy.STATUS_ERROR, 10L, true))
    }

    @Test
    fun `adapter-on only starts for a resolved target that is actually connected`() {
        assertFalse(ObdConnectionPolicy.shouldAutoStartOnAdapterOn(null, setOf("AA:BB")))
        assertFalse(ObdConnectionPolicy.shouldAutoStartOnAdapterOn("", setOf("AA:BB")))
        assertFalse(ObdConnectionPolicy.shouldAutoStartOnAdapterOn("AA:BB", emptySet()))
        assertFalse(ObdConnectionPolicy.shouldAutoStartOnAdapterOn("AA:BB", setOf("CC:DD")))
    }

    @Test
    fun `adapter-on starts for a connected target regardless of address case`() {
        assertTrue(ObdConnectionPolicy.shouldAutoStartOnAdapterOn("AA:BB", setOf("AA:BB")))
        assertTrue(ObdConnectionPolicy.shouldAutoStartOnAdapterOn("aa:bb", setOf("AA:BB")))
        assertTrue(ObdConnectionPolicy.shouldAutoStartOnAdapterOn("AA:BB", setOf("CC:DD", "AA:BB")))
    }

    @Test
    fun `manual disconnect latch suppresses auto-connect until cleared`() {
        // Set by StatsViewModel.disconnect()/reset(); sticky until an explicit reconnect.
        assertTrue(ObdConnectionPolicy.shouldSuppressAutoConnect(manualDisconnect = true))
        // Cleared by connect()/connectDemo()/autoConnect()/retry().
        assertFalse(ObdConnectionPolicy.shouldSuppressAutoConnect(manualDisconnect = false))
    }

    @Test
    fun `reconnect loop continues only while no stop was requested`() {
        assertTrue(ObdConnectionPolicy.shouldContinueReconnect(stopRequested = false))
        assertFalse(ObdConnectionPolicy.shouldContinueReconnect(stopRequested = true))
    }
}
