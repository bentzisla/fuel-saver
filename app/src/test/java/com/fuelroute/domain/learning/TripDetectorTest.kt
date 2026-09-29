package com.fuelroute.domain.learning

import com.fuelroute.domain.model.ObdSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDetectorTest {

    private val detector = TripDetector(stopAfterEngineOffMs = 180_000L)

    private fun sample(tMs: Long, speed: Double?, rpm: Double? = 800.0) =
        ObdSample(timestampMs = tMs, speedKmh = speed, rpm = rpm)

    @Test
    fun `ignores samples while parked with the engine off`() {
        assertSame(TripDetector.TripTransition.None, detector.onSample(sample(0, speed = 0.0, rpm = 0.0)))
        assertFalse(detector.isActive)
        assertNull(detector.startedAtMs)
    }

    @Test
    fun `starts on the first moving sample`() {
        assertSame(TripDetector.TripTransition.None, detector.onSample(sample(0, speed = 0.0)))
        assertSame(TripDetector.TripTransition.Started, detector.onSample(sample(1_000, speed = 30.0)))

        assertTrue(detector.isActive)
        assertEquals(1_000L, detector.startedAtMs)
        assertEquals(1_000L, detector.lastActiveMs)
    }

    @Test
    fun `starts on movement even when the clone never answers RPM`() {
        assertSame(TripDetector.TripTransition.Started, detector.onSample(sample(0, speed = 30.0, rpm = null)))
    }

    @Test
    fun `a long standstill with the engine running keeps the trip open`() {
        detector.onSample(sample(0, speed = 30.0))

        // Ten minutes in a queue / drive-through with the engine idling: still the same drive,
        // so its idle fuel stays with it (used to end after 3 minutes without movement).
        for (t in 10_000L..600_000L step 10_000L) {
            assertSame(TripDetector.TripTransition.None, detector.onSample(sample(t, speed = 0.0)))
        }
        assertTrue(detector.isActive)
    }

    @Test
    fun `ends after the engine has been off for the threshold, at the last active sample`() {
        detector.onSample(sample(0, speed = 30.0))
        detector.onSample(sample(60_000, speed = 0.0)) // idling, engine on

        assertSame(TripDetector.TripTransition.None, detector.onSample(sample(61_000, speed = 0.0, rpm = 0.0)))
        assertSame(TripDetector.TripTransition.None, detector.onSample(sample(200_000, speed = 0.0, rpm = null)))
        val transition = detector.onSample(sample(240_000, speed = 0.0, rpm = null))

        assertEquals(TripDetector.TripTransition.Ended(0L, 60_000L), transition)
        assertFalse(detector.isActive)
        assertNull(detector.startedAtMs)
    }

    @Test
    fun `engine restart before the threshold keeps the trip`() {
        detector.onSample(sample(0, speed = 30.0))
        detector.onSample(sample(100_000, speed = 0.0, rpm = 0.0))
        detector.onSample(sample(200_000, speed = 0.0, rpm = 900.0)) // restarted
        assertSame(TripDetector.TripTransition.None, detector.onSample(sample(300_000, speed = 0.0, rpm = 0.0)))
        assertEquals(
            TripDetector.TripTransition.Ended(0L, 200_000L),
            detector.onSample(sample(380_000, speed = 0.0, rpm = 0.0)),
        )
    }

    @Test
    fun `hybrid moving with the engine off is still active`() {
        detector.onSample(sample(0, speed = 30.0))
        assertSame(TripDetector.TripTransition.None, detector.onSample(sample(300_000, speed = 20.0, rpm = 0.0)))
        assertTrue(detector.isActive)
        assertEquals(300_000L, detector.lastActiveMs)
    }

    @Test
    fun `forceEnd closes an active trip`() {
        detector.onSample(sample(0, speed = 30.0))

        assertEquals(TripDetector.TripTransition.Ended(0L, 5_000L), detector.forceEnd(5_000L))
        assertFalse(detector.isActive)
    }

    @Test
    fun `forceEnd without a trip does nothing`() {
        assertSame(TripDetector.TripTransition.None, detector.forceEnd(5_000L))
    }
}
