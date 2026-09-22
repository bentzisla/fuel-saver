package com.fuelroute.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripSplitCalculatorTest {

    private val delta = 1e-9

    @Test
    fun `merge sums distance fuel and idle and takes the time span`() {
        val merged = TripSplitCalculator.merge(
            listOf(
                TripTotals(1_000, 2_000, distanceKm = 5.0, fuelL = 0.4, idleSeconds = 10.0, maxSpeedKmh = 60.0),
                TripTotals(3_000, 5_000, distanceKm = 12.0, fuelL = 1.0, idleSeconds = 20.0, maxSpeedKmh = 90.0),
            ),
        )!!

        assertEquals(1_000L, merged.startedAtMs)
        assertEquals(5_000L, merged.endedAtMs)
        assertEquals(17.0, merged.distanceKm, delta)
        assertEquals(1.4, merged.fuelL, delta)
        assertEquals(30.0, merged.idleSeconds, delta)
        assertEquals(90.0, merged.maxSpeedKmh, delta)
    }

    @Test
    fun `merge of an empty list is null`() {
        assertNull(TripSplitCalculator.merge(emptyList()))
    }

    @Test
    fun `avgSpeed divides distance by elapsed hours`() {
        // 30 km over 30 minutes = 60 km/h.
        assertEquals(
            60.0,
            TripSplitCalculator.avgSpeedKmh(30.0, 0L, 30L * 60_000),
            delta,
        )
    }

    @Test
    fun `avgSpeed is zero for a zero-length window`() {
        assertEquals(0.0, TripSplitCalculator.avgSpeedKmh(30.0, 1_000L, 1_000L), delta)
    }

    @Test
    fun `split apportions distance fuel and idle proportionally to elapsed time`() {
        val trip = TripTotals(
            startedAtMs = 0,
            endedAtMs = 10_000,
            distanceKm = 20.0,
            fuelL = 2.0,
            idleSeconds = 100.0,
            maxSpeedKmh = 80.0,
        )

        val (first, second) = TripSplitCalculator.split(trip, splitAtMs = 3_000)!!

        assertEquals(0L, first.startedAtMs)
        assertEquals(3_000L, first.endedAtMs)
        assertEquals(6.0, first.distanceKm, delta)
        assertEquals(0.6, first.fuelL, delta)
        assertEquals(30.0, first.idleSeconds, delta)

        assertEquals(3_000L, second.startedAtMs)
        assertEquals(10_000L, second.endedAtMs)
        assertEquals(14.0, second.distanceKm, delta)
        assertEquals(1.4, second.fuelL, delta)
        assertEquals(70.0, second.idleSeconds, delta)
        // Max speed is carried on both halves (best-effort).
        assertEquals(80.0, first.maxSpeedKmh, delta)
        assertEquals(80.0, second.maxSpeedKmh, delta)
    }

    @Test
    fun `split halves add back up to the original totals`() {
        val trip = TripTotals(1_000, 9_000, distanceKm = 12.5, fuelL = 1.1, idleSeconds = 33.0)
        val (first, second) = TripSplitCalculator.split(trip, splitAtMs = 5_000)!!

        assertEquals(trip.distanceKm, first.distanceKm + second.distanceKm, delta)
        assertEquals(trip.fuelL, first.fuelL + second.fuelL, delta)
        assertEquals(trip.idleSeconds, first.idleSeconds + second.idleSeconds, delta)
    }

    @Test
    fun `split rejects a point outside the drive or a zero-length drive`() {
        val trip = TripTotals(1_000, 2_000, 5.0, 0.5, 1.0)
        assertNull(TripSplitCalculator.split(trip, 1_000))
        assertNull(TripSplitCalculator.split(trip, 2_000))
        assertNull(TripSplitCalculator.split(trip, 500))
        assertNull(TripSplitCalculator.split(trip, 3_000))
        assertNull(TripSplitCalculator.split(trip.copy(endedAtMs = 1_000), 1_000))
    }
}
