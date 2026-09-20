package com.fuelroute.domain.history

/** A route search that could be matched to a trip, as seen by the pure matcher. */
data class RouteSearchCandidate(
    val id: Long,
    val timestampMs: Long,
    val destinationPlaceId: String? = null,
    val destinationLabel: String? = null,
)

/**
 * Pure "which search did this drive belong to?" rule. Kept free of Android/Room so
 * [TripLinker][com.fuelroute.data.history.TripLinker] can hand it plain rows and tests
 * can exercise the rule directly.
 */
object TripMatcher {

    /** A search older than this before the trip start is no longer considered. */
    const val LINK_WINDOW_MS = 30L * 60 * 1000

    /**
     * Picks the search that best matches a trip starting at [tripStartMs].
     *
     * Preference order:
     * 1. A search whose destination matches the trip destination — by exact
     *    [tripDestinationPlaceId], or by trimmed case-insensitive [tripDestinationLabel].
     * 2. Otherwise the nearest search in the window.
     *
     * A search at/before the trip start always beats one after it. A search up to
     * [afterWindowMs] *after* the start is only used when nothing before qualifies, so a
     * user who starts moving and then searches the route still gets linked. Searches already
     * linked to another trip are never reused. Returns null when nothing qualifies.
     */
    fun bestMatch(
        tripStartMs: Long,
        candidates: List<RouteSearchCandidate>,
        linkedSearchIds: Set<Long> = emptySet(),
        windowMs: Long = LINK_WINDOW_MS,
        afterWindowMs: Long = 0L,
        tripDestinationPlaceId: String? = null,
        tripDestinationLabel: String? = null,
    ): RouteSearchCandidate? {
        val eligible = candidates.asSequence()
            .filter { it.id !in linkedSearchIds }
            .filter { candidate ->
                val before = candidate.timestampMs <= tripStartMs &&
                    tripStartMs - candidate.timestampMs <= windowMs
                val after = afterWindowMs > 0L &&
                    candidate.timestampMs > tripStartMs &&
                    candidate.timestampMs - tripStartMs <= afterWindowMs
                before || after
            }
            .toList()
        if (eligible.isEmpty()) return null

        val destinationMatches = eligible.filter {
            matchesDestination(it, tripDestinationPlaceId, tripDestinationLabel)
        }
        return (destinationMatches.ifEmpty { eligible })
            .maxWithOrNull(nearestTo(tripStartMs))
    }

    /** True when [candidate]'s destination identifies the same place as the trip. */
    private fun matchesDestination(
        candidate: RouteSearchCandidate,
        placeId: String?,
        label: String?,
    ): Boolean {
        if (!placeId.isNullOrBlank() && candidate.destinationPlaceId == placeId) return true
        val wanted = label?.trim()
        return !wanted.isNullOrEmpty() &&
            candidate.destinationLabel?.trim().equals(wanted, ignoreCase = true)
    }

    /**
     * Orders candidates so the "greatest" is the nearest match: the latest search at/before
     * the start wins, and only when there is none does the earliest search after the start
     * win.
     */
    private fun nearestTo(tripStartMs: Long) = Comparator<RouteSearchCandidate> { a, b ->
        val aBefore = a.timestampMs <= tripStartMs
        val bBefore = b.timestampMs <= tripStartMs
        when {
            aBefore != bBefore -> if (aBefore) 1 else -1
            aBefore -> a.timestampMs.compareTo(b.timestampMs)
            else -> b.timestampMs.compareTo(a.timestampMs)
        }
    }
}
