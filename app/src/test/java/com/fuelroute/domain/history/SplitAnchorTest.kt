package com.fuelroute.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The split anchor must be computed from the real trip window, never from a history entry's
 * display timestamp (which is the route-search time for a linked ride).
 */
class SplitAnchorTest {

    @Test
    fun `durationMs is the trip window length`() {
        assertEquals(600_000L, SplitAnchor.durationMs(1_000L, 601_000L))
    }

    @Test
    fun `durationMs is zero when either bound is missing or the window is empty`() {
        assertEquals(0L, SplitAnchor.durationMs(null, 1_000L))
        assertEquals(0L, SplitAnchor.durationMs(1_000L, null))
        assertEquals(0L, SplitAnchor.durationMs(null, null))
        assertEquals(0L, SplitAnchor.durationMs(5_000L, 5_000L))
        assertEquals(0L, SplitAnchor.durationMs(6_000L, 5_000L))
    }

    @Test
    fun `canSplit requires a known positive window`() {
        assertTrue(SplitAnchor.canSplit(0L, 60_000L))
        assertFalse(SplitAnchor.canSplit(null, 60_000L))
        assertFalse(SplitAnchor.canSplit(0L, 0L))
    }

    @Test
    fun `splitAtMs anchors on the trip start not the display timestamp`() {
        // A linked ride: search happened at 0, but the drive ran 100_000..700_000.
        val anchored = SplitAnchor.splitAtMs(100_000L, 700_000L, 0.5f)
        // The midpoint is computed from the trip window, not from the search time 0.
        assertEquals(400_000L, anchored)
    }

    @Test
    fun `splitAtMs stays inside the window for the slider range`() {
        val start = 1_000L
        val end = 601_000L
        assertTrue(SplitAnchor.splitAtMs(start, end, 0.05f)!! in start..end)
        assertTrue(SplitAnchor.splitAtMs(start, end, 0.95f)!! in start..end)
    }

    @Test
    fun `splitAtMs is null when the window is unavailable`() {
        assertNull(SplitAnchor.splitAtMs(null, 1_000L, 0.5f))
        assertNull(SplitAnchor.splitAtMs(1_000L, null, 0.5f))
        assertNull(SplitAnchor.splitAtMs(1_000L, 1_000L, 0.5f))
    }
}