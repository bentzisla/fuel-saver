package com.fuelroute.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Remembered bus protocol: `ATDPN` parsing, `ATSP<n>` selection and the `ATSP0` fallback. */
class ElmProtocolNumberTest {

    @Test
    fun `ATDPN replies parse to the protocol number`() {
        assertEquals(6, ElmProtocol.parseProtocolNumber("A6"))
        assertEquals(6, ElmProtocol.parseProtocolNumber("6\r\r>"))
        assertEquals(3, ElmProtocol.parseProtocolNumber(" A3 "))
        assertEquals(0xC, ElmProtocol.parseProtocolNumber("C"))
        assertEquals(0xA, ElmProtocol.parseProtocolNumber("AA"))
    }

    @Test
    fun `unusable ATDPN replies are null`() {
        assertNull(ElmProtocol.parseProtocolNumber("0"))
        assertNull(ElmProtocol.parseProtocolNumber("A0"))
        assertNull(ElmProtocol.parseProtocolNumber("?"))
        assertNull(ElmProtocol.parseProtocolNumber(""))
        assertNull(ElmProtocol.parseProtocolNumber("NO DATA"))
        assertNull(ElmProtocol.parseProtocolNumber("D"))
    }

    @Test
    fun `a remembered protocol replaces the automatic search in the init sequence`() {
        assertEquals("ATSP6", ElmProtocol.setProtocolCommand(6))
        assertEquals("ATSPA", ElmProtocol.setProtocolCommand(0xA))
        assertEquals("ATSP0", ElmProtocol.setProtocolCommand(null))
        assertEquals("ATSP0", ElmProtocol.setProtocolCommand(0))
        assertEquals("ATSP0", ElmProtocol.setProtocolCommand(13))

        val fixed = ElmProtocol.configurationCommands(6)
        assertEquals(ElmProtocol.configurationCommands.size, fixed.size)
        assertEquals(1, fixed.count { it == "ATSP6" })
        assertEquals(0, fixed.count { it == "ATSP0" })
        assertEquals(ElmProtocol.configurationCommands, ElmProtocol.configurationCommands(null))
    }

    @Test
    fun `a failing remembered protocol falls back to the automatic search once`() {
        assertEquals("ATSP0", ElmProtocol.recoveryCommandAfterBusError(fixedProtocol = true, attempt = 1))
        assertEquals("ATPC", ElmProtocol.recoveryCommandAfterBusError(fixedProtocol = true, attempt = 2))
        assertEquals("ATPC", ElmProtocol.recoveryCommandAfterBusError(fixedProtocol = false, attempt = 1))
    }
}
