package com.fuelroute.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDeviceMatcherTest {

    @Test
    fun `matches the listed ELM name patterns case-insensitively`() {
        listOf("OBD", "ELM327", "Vgate", "viecar", "KONNWEI").forEach { pattern ->
            assertTrue(pattern, ObdDeviceMatcher.matchesName(pattern))
            assertTrue(pattern.lowercase(), ObdDeviceMatcher.matchesName(pattern.lowercase()))
            assertTrue(pattern.uppercase(), ObdDeviceMatcher.matchesName(pattern.uppercase()))
        }
    }

    @Test
    fun `matches ELM patterns embedded in a longer device name`() {
        assertTrue(ObdDeviceMatcher.matchesName("Vgate iCar Pro"))
        assertTrue(ObdDeviceMatcher.matchesName("KONNWEI KW902"))
        assertTrue(ObdDeviceMatcher.matchesName("OBDII Scanner"))
        assertTrue(ObdDeviceMatcher.matchesName("elm327 v1.5"))
    }

    @Test
    fun `ignores unrelated bonded devices`() {
        listOf("JBL Flip 5", "Galaxy Buds2", "Sony WH-1000XM4", "Car Multimedia", "", null).forEach {
            assertFalse("$it should not match", ObdDeviceMatcher.matchesName(it))
        }
    }

    @Test
    fun `resolveConnected prefers lastDeviceAddress over a name match`() {
        assertNull(
            ObdDeviceMatcher.resolveConnected(
                autoConnect = true,
                lastDeviceAddress = "AA:BB:CC:DD:EE:01",
                connectedAddress = "AA:BB:CC:DD:EE:02",
                connectedName = "ELM327",
            ),
        )
        assertEquals(
            "AA:BB:CC:DD:EE:01",
            ObdDeviceMatcher.resolveConnected(
                autoConnect = true,
                lastDeviceAddress = "AA:BB:CC:DD:EE:01",
                connectedAddress = "AA:BB:CC:DD:EE:01",
                connectedName = "whatever",
            ),
        )
    }

    @Test
    fun `resolveConnected falls back to a name match when no device was picked yet`() {
        assertEquals(
            "AA:BB:CC:DD:EE:09",
            ObdDeviceMatcher.resolveConnected(true, null, "AA:BB:CC:DD:EE:09", "Vgate iCar Pro"),
        )
        assertNull(ObdDeviceMatcher.resolveConnected(true, null, "AA:BB:CC:DD:EE:0A", "Galaxy Buds2"))
    }

    @Test
    fun `resolveConnected does nothing when autoConnect is off`() {
        assertNull(ObdDeviceMatcher.resolveConnected(false, null, "AA:BB", "ELM327"))
        assertNull(ObdDeviceMatcher.resolveConnected(false, "AA:BB", "AA:BB", "ELM327"))
    }

    @Test
    fun `resolveBonded prefers lastDeviceAddress over a name match`() {
        val bonded = listOf(
            BondedObdDevice("AA:BB", "Vgate iCar Pro"),
            BondedObdDevice("CC:DD", "JBL Flip 5"),
        )
        assertEquals("AA:BB", ObdDeviceMatcher.resolveBonded(true, "AA:BB", bonded))
    }

    @Test
    fun `resolveBonded falls back to the first ELM name match`() {
        val bonded = listOf(
            BondedObdDevice("CC:DD", "JBL Flip 5"),
            BondedObdDevice("AA:BB", "KONNWEI KW902"),
        )
        assertEquals("AA:BB", ObdDeviceMatcher.resolveBonded(true, null, bonded))
        assertNull(
            ObdDeviceMatcher.resolveBonded(true, null, listOf(BondedObdDevice("CC:DD", "JBL Flip 5"))),
        )
    }

    @Test
    fun `resolveBonded does nothing when autoConnect is off`() {
        assertNull(
            ObdDeviceMatcher.resolveBonded(false, null, listOf(BondedObdDevice("AA:BB", "ELM327"))),
        )
    }

    @Test
    fun `isTargetDevice matches the last address or a name match`() {
        assertTrue(ObdDeviceMatcher.isTargetDevice("AA:BB", "AA:BB", "whatever"))
        assertFalse(ObdDeviceMatcher.isTargetDevice("AA:BB", "CC:DD", "ELM327"))
        assertTrue(ObdDeviceMatcher.isTargetDevice(null, "CC:DD", "ELM327"))
        assertFalse(ObdDeviceMatcher.isTargetDevice(null, "CC:DD", "JBL Flip"))
    }
}
