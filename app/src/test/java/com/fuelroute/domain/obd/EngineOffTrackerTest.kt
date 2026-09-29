package com.fuelroute.domain.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineOffTrackerTest {

    private val tracker = EngineOffTracker(rpmTimeoutMs = 60_000L, engineOffCapMs = 600_000L)

    @Test
    fun `RPM missing for the timeout stops the run`() {
        tracker.onSample(0L, rpm = null, speedKmh = null, rpmPidSupported = true)
        assertFalse(tracker.shouldStop(59_999L, batteryVoltage = null))
        tracker.onSample(60_000L, rpm = null, speedKmh = null, rpmPidSupported = true)
        assertTrue(tracker.shouldStop(60_000L, batteryVoltage = null))
    }

    @Test
    fun `the clock survives a reconnect gap - only a valid RPM resets it`() {
        // NO DATA for 40 s, then the link drops and is rebuilt (no polls for ~14 s), then NO
        // DATA again: the parked car must still reach the timeout at 60 s, not 60 s after the
        // reconnect (the old engine cleared the clock on every reconnect).
        tracker.onSample(0L, rpm = null, speedKmh = null, rpmPidSupported = true)
        tracker.onSample(40_000L, rpm = null, speedKmh = null, rpmPidSupported = true)
        tracker.onSample(55_000L, rpm = null, speedKmh = null, rpmPidSupported = true)
        tracker.onSample(60_500L, rpm = null, speedKmh = null, rpmPidSupported = true)
        assertTrue(tracker.shouldStop(60_500L, batteryVoltage = null))
    }

    @Test
    fun `a running engine resets both clocks`() {
        tracker.onSample(0L, rpm = null, speedKmh = null, rpmPidSupported = true)
        tracker.onSample(50_000L, rpm = 800.0, speedKmh = 0.0, rpmPidSupported = true)
        assertNull(tracker.rpmNullSinceMs)
        assertNull(tracker.engineOffSinceMs)
        assertFalse(tracker.shouldStop(100_000L, batteryVoltage = null))
    }

    @Test
    fun `key on with the engine off (RPM 0) stops at the run cap`() {
        for (t in 0L..600_000L step 10_000L) {
            tracker.onSample(t, rpm = 0.0, speedKmh = 0.0, rpmPidSupported = true)
        }
        assertTrue(tracker.shouldStop(600_000L, batteryVoltage = null))
    }

    @Test
    fun `RPM 0 while moving (hybrid in EV mode) is not engine off`() {
        for (t in 0L..900_000L step 10_000L) {
            tracker.onSample(t, rpm = 0.0, speedKmh = 30.0, rpmPidSupported = true)
        }
        assertFalse(tracker.shouldStop(900_000L, batteryVoltage = null))
    }

    @Test
    fun `a clone without PID 0C does not stop mid-drive`() {
        for (t in 0L..300_000L step 10_000L) {
            tracker.onSample(t, rpm = null, speedKmh = 60.0, rpmPidSupported = false)
        }
        assertFalse(tracker.shouldStop(300_000L, batteryVoltage = null))
    }

    @Test
    fun `plausible low battery voltage stops at once`() {
        tracker.onSample(0L, rpm = null, speedKmh = null, rpmPidSupported = true)
        assertTrue(tracker.shouldStop(1_000L, batteryVoltage = 11.0))
    }
}
