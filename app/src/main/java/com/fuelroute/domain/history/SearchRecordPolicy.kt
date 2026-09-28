package com.fuelroute.domain.history

import kotlin.math.abs

/**
 * The identity of a route search for duplicate detection: the same origin and destination searched
 * again shortly after is a refresh ("רענן", a changed departure time, re-opening the app), not a
 * second ride.
 */
data class SearchKey(
    val id: Long,
    val timestampMs: Long,
    val originLabel: String,
    val destinationLabel: String,
    val destinationPlaceId: String?,
    val distanceKm: Double,
    /** True when a trip already owns this search (it was driven). */
    val linked: Boolean,
    /** End of the trip that owns this search, when [linked]. */
    val tripEndedAtMs: Long? = null,
)

/**
 * Pure rules that keep History at one row per ride. Before this, every search and every refresh
 * inserted its own `route_search` row: one drive left several "waiting for OBD" twins behind, and
 * entering the actual cost on one of them created a second, manual copy of the same drive (double
 * counted in the savings total and the accuracy %).
 */
object SearchRecordPolicy {

    /** Searches for the same origin/destination closer together than this are one ride. */
    const val DUPLICATE_WINDOW_MS = 45L * 60 * 1000

    /**
     * A refresh whose predicted distance fell below this share of the previous one was made from
     * part-way along the route (a "current location" origin that moved), so it does not describe
     * the whole ride and must not replace the full-route prediction.
     */
    const val PARTIAL_ROUTE_RATIO = 0.7

    enum class Action {
        /** A new ride: insert a new row. */
        INSERT,

        /** A pre-departure refresh: overwrite the previous row (same id) with the newer prediction. */
        REPLACE,

        /** A mid-drive refresh: keep the previous row untouched and record nothing. */
        SKIP,
    }

    /**
     * Decides how to record a new search, given the most recent earlier search.
     *
     * @param previous the latest recorded search, or null when there is none.
     * @param driveUnderway true while an OBD trip that started after [previous] is still open.
     */
    fun decide(previous: SearchKey?, next: SearchKey, driveUnderway: Boolean): Action {
        if (previous == null || previous.linked || !isSameRide(previous, next)) return Action.INSERT
        if (driveUnderway) return Action.SKIP
        if (previous.distanceKm > 0.0 && next.distanceKm < previous.distanceKm * PARTIAL_ROUTE_RATIO) {
            return Action.SKIP
        }
        return Action.REPLACE
    }

    /** Same origin and destination, within [DUPLICATE_WINDOW_MS] of each other. */
    fun isSameRide(a: SearchKey, b: SearchKey): Boolean {
        if (abs(a.timestampMs - b.timestampMs) > DUPLICATE_WINDOW_MS) return false
        if (normalize(a.originLabel) != normalize(b.originLabel)) return false
        val placeA = a.destinationPlaceId?.takeIf { it.isNotBlank() }
        val placeB = b.destinationPlaceId?.takeIf { it.isNotBlank() }
        if (placeA != null && placeB != null) return placeA == placeB
        return normalize(a.destinationLabel) == normalize(b.destinationLabel)
    }

    /**
     * Ids of searches History should hide because they are duplicates of another search for the
     * same ride (rows recorded before [decide] existed). Within a group of same-ride searches, an
     * undriven search is hidden when
     *  - a later search of the group exists (it was superseded by that refresh, or its drive was
     *    linked to the later one), or
     *  - it was made before a driven search of the group finished its drive (a refresh from the
     *    road while the drive was already underway).
     * Linked searches are never hidden. An undriven search made after the group's drive ended is
     * kept, since it can be the next ride (e.g. the way back).
     */
    fun hiddenDuplicates(searches: List<SearchKey>): Set<Long> {
        val groups = mutableListOf<MutableList<SearchKey>>()
        for (search in searches.sortedBy { it.timestampMs }) {
            // Chain to the group whose newest member is the same ride, so a long run of refreshes
            // (each within the window of the previous one) stays one ride.
            val group = groups.lastOrNull { isSameRide(it.last(), search) }
            if (group != null) group.add(search) else groups.add(mutableListOf(search))
        }
        return groups.flatMap { group ->
            val drivenUntilMs = group.mapNotNull { it.tripEndedAtMs }.maxOrNull()
            group.filterIndexed { index, search ->
                !search.linked && (
                    index < group.lastIndex ||
                        (drivenUntilMs != null && search.timestampMs <= drivenUntilMs)
                    )
            }
        }.map { it.id }.toSet()
    }

    private fun normalize(label: String): String = label.trim().lowercase()
}
