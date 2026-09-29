package com.fuelroute.domain.obd

import com.fuelroute.domain.obd.ObdProbePolicy.Action
import com.fuelroute.domain.obd.ObdProbePolicy.Engine
import com.fuelroute.domain.obd.ObdProbePolicy.Outcome
import com.fuelroute.domain.obd.ObdProbePolicy.Skip
import com.fuelroute.domain.obd.ObdProbePolicy.Voltage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdProbePolicyTest {

    @Test
    fun `a voltage below the charging band is ambiguous, never engine off`() {
        // Smart (regenerative) alternators hold a running car at 12.4-12.8 V.
        assertEquals(Voltage.AMBIGUOUS, ObdProbePolicy.classifyVoltage(12.4))
        assertEquals(Voltage.AMBIGUOUS, ObdProbePolicy.classifyVoltage(12.8))
        assertEquals(Voltage.AMBIGUOUS, ObdProbePolicy.classifyVoltage(13.1))
    }

    @Test
    fun `a charging voltage is only a hint`() {
        assertEquals(Voltage.CHARGING, ObdProbePolicy.classifyVoltage(13.2))
        assertEquals(Voltage.CHARGING, ObdProbePolicy.classifyVoltage(14.1))
    }

    @Test
    fun `a clone reporting its own logic rail is unknown`() {
        assertEquals(Voltage.UNKNOWN, ObdProbePolicy.classifyVoltage(0.0))
        assertEquals(Voltage.UNKNOWN, ObdProbePolicy.classifyVoltage(3.3))
        assertEquals(Voltage.UNKNOWN, ObdProbePolicy.classifyVoltage(5.0))
        assertEquals(Voltage.UNKNOWN, ObdProbePolicy.classifyVoltage(null))
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
    fun `a probe that yielded to the engine does nothing`() {
        assertEquals(Action.NOTHING, ObdProbePolicy.afterProbe(Outcome.YIELDED, manualDisconnect = false))
        assertEquals(Action.NOTHING, ObdProbePolicy.afterProbe(Outcome.YIELDED, manualDisconnect = true))
    }

    @Test
    fun `quiet probes stretch the interval 5 - 15 - 30 min`() {
        assertEquals(5, ObdProbePolicy.nextDelayMin(5, quietStreak = 0))
        assertEquals(5, ObdProbePolicy.nextDelayMin(5, quietStreak = 2))
        assertEquals(15, ObdProbePolicy.nextDelayMin(5, quietStreak = 3))
        assertEquals(15, ObdProbePolicy.nextDelayMin(5, quietStreak = 5))
        assertEquals(30, ObdProbePolicy.nextDelayMin(5, quietStreak = 6))
        assertEquals(30, ObdProbePolicy.nextDelayMin(5, quietStreak = 100))
        // Never shorter than the configured interval.
        assertEquals(60, ObdProbePolicy.nextDelayMin(60, quietStreak = 100))
        assertFalse(ObdProbePolicy.isBackedOff(5, 2))
        assertTrue(ObdProbePolicy.isBackedOff(5, 3))
        assertFalse(ObdProbePolicy.isBackedOff(60, 10))
    }

    @Test
    fun `the quiet streak grows on absent or off and ends with a drive`() {
        assertEquals(1, ObdProbePolicy.nextQuietStreak(0, Outcome.ABSENT))
        assertEquals(4, ObdProbePolicy.nextQuietStreak(3, Outcome.ENGINE_OFF))
        assertEquals(3, ObdProbePolicy.nextQuietStreak(3, Outcome.UNRESPONSIVE))
        assertEquals(0, ObdProbePolicy.nextQuietStreak(7, Outcome.ENGINE_RUNNING))
        assertEquals(0, ObdProbePolicy.nextQuietStreak(7, Outcome.YIELDED))
    }

    @Test
    fun `the off streak counts engine-off probes only`() {
        assertEquals(1, ObdProbePolicy.nextOffStreak(0, Outcome.ENGINE_OFF))
        assertEquals(2, ObdProbePolicy.nextOffStreak(2, Outcome.ABSENT))
        assertEquals(2, ObdProbePolicy.nextOffStreak(2, Outcome.UNRESPONSIVE))
        assertEquals(0, ObdProbePolicy.nextOffStreak(5, Outcome.ENGINE_RUNNING))
    }

    @Test
    fun `a parked car's remembered protocol is trusted without a search`() {
        assertTrue(ObdProbePolicy.fallbackSearchAllowed(0))
        assertTrue(ObdProbePolicy.fallbackSearchAllowed(ObdProbePolicy.TRUST_PROTOCOL_AFTER_OFF - 1))
        assertFalse(ObdProbePolicy.fallbackSearchAllowed(ObdProbePolicy.TRUST_PROTOCOL_AFTER_OFF))
    }

    @Test
    fun `the fallback notification uses a new channel id`() {
        // Importance cannot be raised on an existing channel: the high-importance one needs its own id.
        assertTrue(ObdProbePolicy.FALLBACK_CHANNEL_ID != ObdProbePolicy.LEGACY_FALLBACK_CHANNEL_ID)
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
