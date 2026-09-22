package com.fuelroute.domain.history

import org.junit.Assert.assertEquals
import org.junit.Test

class TripCloseTimeTest {

    @Test
    fun `preserves an endedAtMs that advanced past the start`() {
        // A trip checkpointed to a real end time must keep it even if recovery happens days later.
        assertEquals(5_000L, TripCloseTime.preservedEndMs(startedAtMs = 1_000L, endedAtMs = 5_000L, nowMs = 900_000L))
    }

    @Test
    fun `stamps now only when the trip never advanced`() {
        assertEquals(900_000L, TripCloseTime.preservedEndMs(startedAtMs = 1_000L, endedAtMs = 1_000L, nowMs = 900_000L))
    }
}