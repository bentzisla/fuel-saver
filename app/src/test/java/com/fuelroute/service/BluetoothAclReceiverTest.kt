package com.fuelroute.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothAclReceiverTest {

    @Test
    fun `autoConnect off means the receiver decision does nothing`() {
        assertNull(BluetoothAclReceiver.decideStart(false, null, "AA:BB", "ELM327"))
        assertNull(BluetoothAclReceiver.decideStart(false, "AA:BB", "AA:BB", "ELM327"))
    }

    @Test
    fun `receiver starts the remembered device and ignores others`() {
        assertEquals("AA:BB", BluetoothAclReceiver.decideStart(true, "AA:BB", "AA:BB", null))
        assertNull(BluetoothAclReceiver.decideStart(true, "AA:BB", "CC:DD", "ELM327"))
    }

    @Test
    fun `receiver auto-resolves an ELM name on first use`() {
        assertEquals("CC:DD", BluetoothAclReceiver.decideStart(true, null, "CC:DD", "Vgate iCar Pro"))
    }
}
