package com.fuelroute.ui.history

import com.fuelroute.data.history.DriveHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merge proximity must be computed on the underlying trip start, not the entry's display timestamp
 * (the route-search time for a linked ride), and its demo rule must be symmetric.
 */
class MergeCandidatesTest {

    @Test
    fun `proximity uses trip start rather than the display timestamp`() {
        val anchor = entry(id = 1, startMs = 1_000_000, timestampMs = 5_000_000)
        // Display timestamps are 85 h apart, but the drives started 500 s apart.
        val nearByTripTime = entry(id = 2, startMs = 1_500_000, timestampMs = 90_000_000)
        // Display timestamps look close, but the drives started ~14 h apart.
        val farByTripTime = entry(id = 3, startMs = 50_000_000, timestampMs = 1_200_000)

        val candidates = mergeCandidates(listOf(anchor, nearByTripTime, farByTripTime), anchor)

        assertEquals(listOf(2L), candidates.map { it.tripId })
    }

    @Test
    fun `allows real-real and demo-demo but never mixes them`() {
        val anchor = entry(id = 1, startMs = 1_000_000, isDemo = false)
        val real = entry(id = 2, startMs = 1_100_000, isDemo = false)
        val demo = entry(id = 3, startMs = 1_200_000, isDemo = true)

        assertEquals(listOf(2L), mergeCandidates(listOf(anchor, real, demo), anchor).map { it.tripId })

        val demoAnchor = entry(id = 4, startMs = 1_000_000, isDemo = true)
        assertEquals(
            listOf(3L),
            mergeCandidates(listOf(demoAnchor, real, demo), demoAnchor).map { it.tripId },
        )
    }

    @Test
    fun `excludes undriven searches and the anchor itself`() {
        val anchor = entry(id = 1, startMs = 1_000_000)
        val undriven = entry(id = null, startMs = null, timestampMs = 1_100_000)

        assertTrue(mergeCandidates(listOf(anchor, undriven), anchor).isEmpty())
    }

    @Test
    fun `an anchor without a trip window has no candidates`() {
        val anchor = entry(id = 1, startMs = null)
        val other = entry(id = 2, startMs = 1_000_000)

        assertTrue(mergeCandidates(listOf(anchor, other), anchor).isEmpty())
    }

    private fun entry(
        id: Long?,
        startMs: Long?,
        timestampMs: Long = startMs ?: 0L,
        isDemo: Boolean = false,
    ) = DriveHistoryEntry(
        searchId = if (id == null) 1L else null,
        tripId = id,
        originLabel = null,
        destinationLabel = null,
        timestampMs = timestampMs,
        tripStartedAtMs = startMs,
        tripEndedAtMs = startMs?.plus(600_000L),
        predictedCost = null,
        predictedLiters = null,
        predictedMinutes = null,
        distanceKm = 1.0,
        actualCost = 1.0,
        actualLiters = 0.1,
        actualMinutes = 10.0,
        pricePerLiterAtSearch = null,
        pricePerLiterAtTrip = null,
        savedAmount = 0.0,
        isDemo = isDemo,
    )
}