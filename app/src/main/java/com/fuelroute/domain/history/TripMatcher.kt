package com.fuelroute.domain.history

/** A route search that could be matched to a trip, as seen by the pure matcher. */
data class RouteSearchCandidate(
    val id: Long,
    val timestampMs: Long,
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
     * Picks the search that best matches a trip starting at [tripStartMs]: the nearest
     * (latest) search within [windowMs] *before* the start that is not already linked.
     * Searches after the trip start never match. Returns null when nothing qualifies.
     */
    fun bestMatch(
        tripStartMs: Long,
        candidates: List<RouteSearchCandidate>,
        linkedSearchIds: Set<Long> = emptySet(),
        windowMs: Long = LINK_WINDOW_MS,
    ): RouteSearchCandidate? =
        candidates.asSequence()
            .filter { it.id !in linkedSearchIds }
            .filter { it.timestampMs <= tripStartMs && tripStartMs - it.timestampMs <= windowMs }
            .maxByOrNull { it.timestampMs }
}