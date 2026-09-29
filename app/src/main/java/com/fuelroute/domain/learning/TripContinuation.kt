package com.fuelroute.domain.learning

/**
 * PLAN.md 5.5: "the trip continues if the link comes back within 3 minutes". When a run ends
 * (reconnect gave up, service re-arm, ignition blip) the trip row is closed; the next run must
 * continue that row instead of fragmenting one drive into several.
 */
object TripContinuation {

    /** A closed trip may be continued when the next one starts less than this after its end. */
    const val MAX_GAP_MS = 180_000L

    /** The last trip row of the vehicle, reduced to what the rule needs. */
    data class LastTrip(
        val endedAtMs: Long,
        val isOpen: Boolean,
        val source: String,
        /** The user typed a manual post-drive entry: the row is theirs now, never reopen it. */
        val hasManualEntry: Boolean,
    )

    /** True when a trip of [source] starting at [startMs] should continue [last]. */
    fun canContinue(last: LastTrip?, source: String, startMs: Long): Boolean {
        if (last == null || last.isOpen || last.hasManualEntry) return false
        if (last.source != source) return false
        // A negative gap (the row's last checkpoint is a little after this start) is an overlap
        // of the same drive and continues it too.
        return startMs - last.endedAtMs < MAX_GAP_MS
    }
}
