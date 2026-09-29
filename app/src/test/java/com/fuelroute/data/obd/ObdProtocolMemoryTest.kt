package com.fuelroute.data.obd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ObdProtocolMemoryTest {

    private val writes = mutableListOf<Pair<String, Int?>>()

    @Before
    fun setUp() {
        ObdProtocolMemory.load(emptyMap())
        ObdProtocolMemory.persister = { address, protocol -> writes += address to protocol }
    }

    @After
    fun tearDown() {
        ObdProtocolMemory.persister = null
        ObdProtocolMemory.load(emptyMap())
    }

    @Test
    fun `remembers per address, case-insensitively, and persists only changes`() {
        ObdProtocolMemory.remember("00:00:00:33:33:33", 6)
        ObdProtocolMemory.remember("00:00:00:33:33:33", 6)
        ObdProtocolMemory.remember("aa:bb:cc:dd:ee:ff", 3)

        assertEquals(6, ObdProtocolMemory.get("00:00:00:33:33:33"))
        assertEquals(3, ObdProtocolMemory.get("AA:BB:CC:DD:EE:FF"))
        assertEquals(listOf("00:00:00:33:33:33" to 6, "AA:BB:CC:DD:EE:FF" to 3), writes)
    }

    @Test
    fun `unknown, null and fake transports have no protocol`() {
        ObdProtocolMemory.remember("00:00:00:33:33:33", null)
        ObdProtocolMemory.remember(null, 6)
        assertNull(ObdProtocolMemory.get("00:00:00:33:33:33"))
        assertNull(ObdProtocolMemory.get(null))
        assertEquals(emptyList<Pair<String, Int?>>(), writes)
    }

    @Test
    fun `hydration does not write back, forget does`() {
        ObdProtocolMemory.load(mapOf("00:00:00:33:33:33" to 8))
        assertEquals(8, ObdProtocolMemory.get("00:00:00:33:33:33"))
        assertEquals(emptyList<Pair<String, Int?>>(), writes)

        ObdProtocolMemory.forget("00:00:00:33:33:33")
        assertNull(ObdProtocolMemory.get("00:00:00:33:33:33"))
        assertEquals(listOf<Pair<String, Int?>>("00:00:00:33:33:33" to null), writes)
    }
}
