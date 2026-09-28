package com.fuelroute.domain.obd

import com.fuelroute.domain.obd.ObdProbePolicy.Action
import com.fuelroute.domain.obd.ObdProbePolicy.Engine
import com.fuelroute.domain.obd.ObdProbePolicy.Outcome
import com.fuelroute.domain.obd.ObdProbePolicy.Skip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdProbePolicyTest {

    @Test
    fun `a resting battery ends the probe without asking the car`() {
        assertEquals(Engine.OFF, ObdProbePolicy.engineFromVoltage(12.4))
        assertEquals(Engine.OFF, ObdProbePolicy.engineFromVoltage(12.9))
    }

    @Test
    fun `a charging voltage is confirmed with RPM, never trusted alone`() {
        assertNull(ObdProbePolicy.engineFromVoltage(14.1))
        assertNull(ObdProbePolicy.engineFromVoltage(13.0))
    }

    @Test
    fun `a clone reporting its own logic rail is ignored`() {
        assertNull(ObdProbePolicy.engineFromVoltage(0.0))
        assertNull(ObdProbePolicy.engineFromVoltage(3.3))
        assertNull(ObdProbePolicy.engineFromVoltage(5.0))
        assertNull(ObdProbePolicy.engineFromVoltage(null))
    }

    @Test
    fun `RPM decides the engine state`() {
        assertEquals(Engine.RUNNING, ObdProbePolicy.engineFromRpm(780.0, replied = true))
        assertEquals(Engine.OFF, ObdProbePolicy.engineFromRpm(0.0, replied = true))
        // UNABLE TO CONNECT / NO DATA: the ignition is off.
        assertEquals(Engine.OFF, ObdProbePolicy.engineFromRpm(null, replied = true))
        // Silence says nothing.
        assertEquals(Engine.UNKNOWN, ObdProbePolicy.engineFromRpm(null, replied = false))
    }

    @Test
    fun `a running engine starts logging unless the user disconnected this drive`() {
        assertEquals(Action.START_LOGGING, ObdProbePolicy.afterProbe(Outcome.ENGINE_RUNNING, manualDisconnect = false))
        assertEquals(Action.NOTHING, ObdProbePolicy.afterProbe(Outcome.ENGINE_RUNNING, manualDisconnect = true))
    }

    @Test
    fun `the manual disconnect is lifted once the car is seen off`() {
        assertEquals(Action.CLEAR_MANUAL_DISCONNECT, ObdProbePolicy.afterProbe(Outcome.ENGINE_OFF, manualDisconnect = true))
        assertEquals(Action.NOTHING, ObdProbePolicy.afterProbe(Outcome.ENGINE_OFF, manualDisconnect = false))
    }

    @Test
    fun `a dongle out of reach lifts the manual disconnect only after a long absence`() {
        // A clone that hung mid-drive looks absent too: one miss must not re-arm logging.
        assertEquals(
            Action.NOTHING,
            ObdProbePolicy.afterProbe(Outcome.ABSENT, manualDisconnect = true, absentForMs = 5 * 60_000L),
        )
        assertEquals(
            Action.CLEAR_MANUAL_DISCONNECT,
            ObdProbePolicy.afterProbe(Outcome.ABSENT, manualDisconnect = true, absentForMs = ObdProbePolicy.ABSENT_ENDS_DRIVE_MS),
        )
        assertEquals(Action.NOTHING, ObdProbePolicy.afterProbe(Outcome.UNRESPONSIVE, manualDisconnect = true))
    }

    @Test
    fun `probes are skipped cheaply when there is nothing to do`() {
        fun skip(
            enabled: Boolean = true,
            autoConnect: Boolean = true,
            hasDevice: Boolean = true,
            hasPermission: Boolean = true,
            bluetoothOn: Boolean = true,
            loggingActive: Boolean = false,
        ) = ObdProbePolicy.skipReason(enabled, autoConnect, hasDevice, hasPermission, bluetoothOn, loggingActive)

        assertNull(skip())
        assertEquals(Skip.DISABLED, skip(enabled = false))
        assertEquals(Skip.DISABLED, skip(autoConnect = false))
        assertEquals(Skip.NO_DEVICE, skip(hasDevice = false))
        assertEquals(Skip.NO_PERMISSION, skip(hasPermission = false))
        assertEquals(Skip.BLUETOOTH_OFF, skip(bluetoothOn = false))
        assertEquals(Skip.ALREADY_LOGGING, skip(loggingActive = true))
    }

    @Test
    fun `ACL broadcasts are ours while probing and for a grace period after`() {
        assertTrue(ObdProbePolicy.isAclSuppressed(probeActive = true, suppressUntilMs = 0L, nowMs = 1_000L))
        assertTrue(ObdProbePolicy.isAclSuppressed(probeActive = false, suppressUntilMs = 5_000L, nowMs = 1_000L))
        assertFalse(ObdProbePolicy.isAclSuppressed(probeActive = false, suppressUntilMs = 5_000L, nowMs = 5_000L))
    }

    @Test
    fun `the interval is clamped to a sane range`() {
        assertEquals(1, ObdProbePolicy.clampIntervalMin(0))
        assertEquals(5, ObdProbePolicy.clampIntervalMin(5))
        assertEquals(60, ObdProbePolicy.clampIntervalMin(600))
    }
}
