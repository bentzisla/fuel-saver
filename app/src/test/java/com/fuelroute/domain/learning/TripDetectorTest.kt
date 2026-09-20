package com.fuelroute.domain.learning

import com.fuelroute.domain.model.ObdSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDetectorTest {

    private val detector = TripDetector(stopAfterIdleMs = 180_000L)

    private fun sample(tMs: Long, speed: Double, rpm: Double = 800.0) =
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
        assertEquals(1_000L, detector.lastMovementMs)
    }

    @Test
    fun `keeps the trip open through a stop under the threshold`() {
        detector.onSample(sample(0, speed = 30.0))

        assertSame(TripDetector.TripTransition.None, detector.onSample(sample(60_000, speed = 0.0)))
        assertTrue(detector.isActive)
    }

    @Test
    fun `ends the trip after the idle threshold`() {
        detector.onSample(sample(0, speed = 30.0))
        detector.onSample(sample(60_000, speed = 0.0))

        val transition = detector.onSample(sample(60_000 + 180_000, speed = 0.0))

        assertEquals(TripDetector.TripTransition.Ended(0L, 240_000L), transition)
        assertFalse(detector.isActive)
        assertNull(detector.startedAtMs)
    }

    @Test
    fun `movement resets the idle clock`() {
        detector.onSample(sample(0, speed = 30.0))
        detector.onSample(sample(100_000, speed = 0.0))

        // Moves again before the threshold, so the idle window restarts from here.
        detector.onSample(sample(200_000, speed = 20.0))
        detector.onSample(sample(250_000, speed = 0.0))

        // 250s since the original movement would have ended it; only 50s since the reset.
        assertSame(TripDetector.TripTransition.None, detector.onSample(sample(250_000, speed = 0.0)))
        assertEquals(
            TripDetector.TripTransition.Ended(0L, 500_000L),
            detector.onSample(sample(500_000, speed = 0.0)),
        )
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