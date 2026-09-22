package com.fuelroute.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `ATZ` reply used to be rejected unless it contained "ELM327", which broke recommended
 * STN/OBDLink adapters. Any non-blank, non-error banner is now accepted.
 */
class ElmProtocolBannerTest {

    @Test
    fun `accepts ELM327 STN and OBDLink banners`() {
        assertTrue(ElmProtocol.isAcceptedAdapterBanner("ELM327 v1.5"))
        assertTrue(ElmProtocol.isAcceptedAdapterBanner("ELM327 v2.3"))
        assertTrue(ElmProtocol.isAcceptedAdapterBanner("STN1170 v5.1.3"))
        assertTrue(ElmProtocol.isAcceptedAdapterBanner("OBDLink MX+"))
    }

    @Test
    fun `rejects blank and no-answer banners`() {
        assertFalse(ElmProtocol.isAcceptedAdapterBanner(""))
        assertFalse(ElmProtocol.isAcceptedAdapterBanner("   "))
        assertFalse(ElmProtocol.isAcceptedAdapterBanner("NO DATA"))
        assertFalse(ElmProtocol.isAcceptedAdapterBanner("?"))
        assertFalse(ElmProtocol.isAcceptedAdapterBanner("UNABLE TO CONNECT"))
    }

    @Test
    fun `atz gets a longer read window than the other init commands`() {
        assertTrue(ObdConnectionPolicy.initReadTimeoutMs("ATZ") >= 2_000L)
        assertTrue(
            ObdConnectionPolicy.initReadTimeoutMs("ATZ") >
                ObdConnectionPolicy.INIT_READ_TIMEOUT_MS,
        )
        assertEquals(
            ObdConnectionPolicy.INIT_READ_TIMEOUT_MS,
            ObdConnectionPolicy.initReadTimeoutMs("ATE0"),
        )
        assertEquals(
            ObdConnectionPolicy.INIT_READ_TIMEOUT_MS,
            ObdConnectionPolicy.initReadTimeoutMs("ATSP0"),
        )
    }

    @Test
    fun `negotiation failure is distinct from a supported but empty set`() {
        val failed = ElmProtocol.negotiateSupport(
            mapOf(
                ElmProtocol.PID_SUPPORTED_01_20 to "NO DATA",
                ElmProtocol.PID_SUPPORTED_21_40 to "NO DATA",
                ElmProtocol.PID_SUPPORTED_41_60 to "?",
                ElmProtocol.PID_SUPPORTED_61_80 to "",
            ),
        )
        assertTrue(failed.negotiationFailed)
        assertTrue(failed.pids.isEmpty())

        val succeededButEmpty = ElmProtocol.negotiateSupport(
            mapOf(
                ElmProtocol.PID_SUPPORTED_01_20 to "41 00 00 00 00 00",
                ElmProtocol.PID_SUPPORTED_21_40 to "NO DATA",
                ElmProtocol.PID_SUPPORTED_41_60 to "NO DATA",
                ElmProtocol.PID_SUPPORTED_61_80 to "NO DATA",
            ),
        )
        assertFalse(succeededButEmpty.negotiationFailed)
        assertTrue(succeededButEmpty.pids.isEmpty())
    }
}