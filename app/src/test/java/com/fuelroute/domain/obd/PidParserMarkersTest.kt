package com.fuelroute.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the diagnostic parser gaps added for real-dongle capture: `SEARCHING...` (and the
 * other clone markers) must parse as null rather than silently yielding garbage, and echo
 * tolerance must hold even when the whole command is echoed before the response.
 */
class PidParserMarkersTest {

    @Test
    fun `searching yields null`() {
        assertNull(ElmProtocol.speed("SEARCHING..."))
    }

    @Test
    fun `clone markers yield null`() {
        assertNull(ElmProtocol.speed("ACT ALERT"))
        assertNull(ElmProtocol.speed("LVP RESET"))
        assertNull(ElmProtocol.speed("RTR TIMEOUT"))
    }

    @Test
    fun `parses speed hex`() {
        assertEquals(60.0, ElmProtocol.speed("41 0D 3C")!!, 1e-9)
    }

    @Test
    fun `echo tolerance with command prefix`() {
        assertEquals(60.0, ElmProtocol.speed("010D\r41 0D 3C\r")!!, 1e-9)
    }

    @Test
    fun `no data yields null`() {
        assertNull(ElmProtocol.speed("NO DATA"))
        assertNull(ElmProtocol.speed("NODATA"))
    }
}