package com.fuelroute.domain.history

/**
 * Pure math for the History merge/split dialog's split anchor. No Android dependencies.
 *
 * A history entry's display timestamp is *not* always the drive's start: for a linked ride it is
 * the route-search time. Anchoring a split on that value can push the split point outside the real
 * `[startedAtMs, endedAtMs]` window, so the dialog must anchor on the trip times instead.
 */
object SplitAnchor {

    /** Duration of the `[startedAtMs, endedAtMs]` window, or 0 when either bound is missing. */
    fun durationMs(startedAtMs: Long?, endedAtMs: Long?): Long {
        if (startedAtMs == null || endedAtMs == null) return 0L
        return (endedAtMs - startedAtMs).coerceAtLeast(0L)
    }

    /** True when the trip window is known and has a positive duration. */
    fun canSplit(startedAtMs: Long?, endedAtMs: Long?): Boolean =
        durationMs(startedAtMs, endedAtMs) > 0L

    /**
     * The split timestamp at [fraction] through the drive, anchored on the trip window rather than
     * the entry's display timestamp. Returns null when the window is unknown or empty.
     */
    fun splitAtMs(startedAtMs: Long?, endedAtMs: Long?, fraction: Float): Long? {
        val duration = durationMs(startedAtMs, endedAtMs)
        if (duration <= 0L || startedAtMs == null) return null
        return startedAtMs + (duration * fraction).toLong()
    }
}