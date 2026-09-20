package com.fuelroute.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripMatcherTest {

    private val tripStart = 10_000_000L

    private fun at(offsetMs: Long, id: Long) = RouteSearchCandidate(id = id, timestampMs = tripStart + offsetMs)

    @Test
    fun `picks the nearest search inside the window`() {
        val far = at(-25 * 60_000L, id = 1)
        val near = at(-5 * 60_000L, id = 2)
        val nearest = at(-1 * 60_000L, id = 3)

        val match = TripMatcher.bestMatch(tripStart, listOf(far, near, nearest))

        assertEquals(3L, match?.id)
    }

    @Test
    fun `returns none when every search is outside the window`() {
        val tooOld = at(-(TripMatcher.LINK_WINDOW_MS + 1), id = 1)

        assertNull(TripMatcher.bestMatch(tripStart, listOf(tooOld)))
    }

    @Test
    fun `returns none when the only search is after the trip start`() {
        val afterStart = at(60_000L, id = 1)

        assertNull(TripMatcher.bestMatch(tripStart, listOf(afterStart)))
    }

    @Test
    fun `ignores searches already linked to another trip`() {
        val linked = at(-2 * 60_000L, id = 1)
        val free = at(-10 * 60_000L, id = 2)

        val match = TripMatcher.bestMatch(tripStart, listOf(linked, free), linkedSearchIds = setOf(1L))

        assertEquals(2L, match?.id)
    }

    @Test
    fun `null when all candidates are linked`() {
        val linked = at(-2 * 60_000L, id = 1)

        assertNull(TripMatcher.bestMatch(tripStart, listOf(linked), linkedSearchIds = setOf(1L)))
    }

    @Test
    fun `empty candidate list returns null`() {
        assertNull(TripMatcher.bestMatch(tripStart, emptyList()))
    }
}