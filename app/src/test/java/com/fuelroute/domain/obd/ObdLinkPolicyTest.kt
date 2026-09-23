package com.fuelroute.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules behind the obd-link fixes (clone-tolerant init, RFCOMM hygiene, error codes). */
class ObdLinkPolicyTest {

    @Test
    fun `banner with garbage bytes or a stale question mark is accepted`() {
        assertTrue(ElmProtocol.isAcceptedAdapterBanner("ÿ\u0000ELM327 v1.5"))
        assertTrue(ElmProtocol.isAcceptedAdapterBanner("?\r\rELM327 v1.5"))
        assertTrue(ElmProtocol.isAcceptedAdapterBanner("ATZ\r\r\rELM327 v1.5\r\r"))
        assertTrue(ElmProtocol.isAcceptedAdapterBanner("OBDII to RS232 Interpreter"))
        assertTrue(ElmProtocol.isAcceptedAdapterBanner("v2.1"))
    }

    @Test
    fun `out-of-sync or interrupted replies are not a banner`() {
        assertFalse(ElmProtocol.isAcceptedAdapterBanner("OK"))
        assertFalse(ElmProtocol.isAcceptedAdapterBanner("STOPPED"))
        assertFalse(ElmProtocol.isAcceptedAdapterBanner("ATZ"))
        assertFalse(ElmProtocol.isAcceptedAdapterBanner("ATZ\r\r"))
        assertFalse(ElmProtocol.isAcceptedAdapterBanner("?"))
    }

    @Test
    fun `reset sequence retries ATZ then warm-starts`() {
        assertEquals(listOf("ATZ", "ATZ", "ATWS"), ElmProtocol.resetSequence)
        assertEquals("ATZ", ElmProtocol.initializationCommands.first())
        assertFalse(ElmProtocol.configurationCommands.contains("ATZ"))
        assertTrue(ObdConnectionPolicy.isResetCommand("atws"))
        assertEquals(ObdConnectionPolicy.ATZ_READ_TIMEOUT_MS, ObdConnectionPolicy.initReadTimeoutMs("ATWS"))
    }

    @Test
    fun `protocol search replies are classified`() {
        assertEquals(
            ElmProtocol.SearchOutcome.LOCKED,
            ElmProtocol.classifySearchReply("SEARCHING...\r41 00 BE 3F A8 13\r"),
        )
        assertEquals(ElmProtocol.SearchOutcome.NO_DATA, ElmProtocol.classifySearchReply("SEARCHING...\rNO DATA"))
        assertEquals(
            ElmProtocol.SearchOutcome.BUS_ERROR,
            ElmProtocol.classifySearchReply("SEARCHING...\rUNABLE TO CONNECT"),
        )
        assertEquals(ElmProtocol.SearchOutcome.BUS_ERROR, ElmProtocol.classifySearchReply("BUS INIT: ...ERROR"))
        assertEquals(ElmProtocol.SearchOutcome.BUS_ERROR, ElmProtocol.classifySearchReply("SEARCHING..."))
        assertEquals(ElmProtocol.SearchOutcome.NO_REPLY, ElmProtocol.classifySearchReply(""))
    }

    @Test
    fun `only bus errors are retried, a bounded number of times`() {
        val busError = ElmProtocol.SearchOutcome.BUS_ERROR
        assertTrue(ObdConnectionPolicy.shouldRetryProtocolSearch(busError, 1))
        assertFalse(
            ObdConnectionPolicy.shouldRetryProtocolSearch(busError, ObdConnectionPolicy.PROTOCOL_SETTLE_ATTEMPTS),
        )
        assertFalse(ObdConnectionPolicy.shouldRetryProtocolSearch(ElmProtocol.SearchOutcome.NO_DATA, 1))
        assertFalse(ObdConnectionPolicy.shouldRetryProtocolSearch(ElmProtocol.SearchOutcome.NO_REPLY, 1))
    }

    @Test
    fun `rfcomm release wait honours the last close`() {
        assertEquals(0L, ObdConnectionPolicy.rfcommReleaseWaitMs(null, 5_000))
        assertEquals(700L, ObdConnectionPolicy.rfcommReleaseWaitMs(10_000, 10_300))
        assertEquals(0L, ObdConnectionPolicy.rfcommReleaseWaitMs(10_000, 11_500))
        assertEquals(ObdConnectionPolicy.RFCOMM_RELEASE_MS, ObdConnectionPolicy.rfcommReleaseWaitMs(10_000, 9_000))
        assertTrue(ObdConnectionPolicy.RFCOMM_RELEASE_MS >= 1_000L)
    }

    @Test
    fun `init failures map to distinct error codes`() {
        assertEquals("INIT TIMEOUT", ObdConnectionPolicy.initErrorCode(ElmLinkFailure.TIMEOUT))
        assertEquals("INIT TIMEOUT", ObdConnectionPolicy.initErrorCode(null))
        assertEquals("INIT WRITE FAILED", ObdConnectionPolicy.initErrorCode(ElmLinkFailure.WRITE_FAILED))
        assertEquals("INIT EOF", ObdConnectionPolicy.initErrorCode(ElmLinkFailure.EOF))
        assertEquals("INIT READ ERROR", ObdConnectionPolicy.initErrorCode(ElmLinkFailure.READ_ERROR))
        assertEquals("INIT LINK CLOSED", ObdConnectionPolicy.initErrorCode(ElmLinkFailure.LINK_CLOSED))
        val codes = ElmLinkFailure.entries.map { ObdConnectionPolicy.initErrorCode(it) }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `first engine reconnect is attempted even when the ACL already dropped`() {
        assertTrue(ObdConnectionPolicy.shouldAttemptReconnect(0, deviceConnected = false))
        assertFalse(ObdConnectionPolicy.shouldAttemptReconnect(1, deviceConnected = false))
        assertTrue(ObdConnectionPolicy.shouldAttemptReconnect(1, deviceConnected = true))
        assertFalse(
            ObdConnectionPolicy.shouldAttemptReconnect(ObdConnectionPolicy.MAX_RECONNECT_ATTEMPTS, true),
        )
    }

    @Test
    fun `session open is retried exactly once`() {
        assertTrue(ObdConnectionPolicy.shouldRetrySessionOpen(1))
        assertFalse(ObdConnectionPolicy.shouldRetrySessionOpen(2))
    }

    @Test
    fun `acl disconnect during our own connect is ignored`() {
        assertTrue(ObdConnectionPolicy.shouldIgnoreAclDisconnect(connectInProgress = true))
        assertFalse(ObdConnectionPolicy.shouldIgnoreAclDisconnect(connectInProgress = false))
    }

    @Test
    fun `stop is bounded and deadlines are ordered sensibly`() {
        val stopBudget = ObdConnectionPolicy.STOP_GRACE_MS + ObdConnectionPolicy.STOP_CANCEL_JOIN_MS +
            ObdConnectionPolicy.STOP_FORCE_JOIN_MS
        assertTrue(stopBudget <= 10_000L)
        assertTrue(ObdConnectionPolicy.INIT_READ_TIMEOUT_MS < ObdConnectionPolicy.ATZ_READ_TIMEOUT_MS)
        assertTrue(ObdConnectionPolicy.ATZ_READ_TIMEOUT_MS < ObdConnectionPolicy.COMMAND_TIMEOUT_MS)
        assertTrue(ObdConnectionPolicy.COMMAND_TIMEOUT_MS < ObdConnectionPolicy.PROTOCOL_SEARCH_TIMEOUT_MS)
    }
}
