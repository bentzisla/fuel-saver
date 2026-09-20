package com.fuelroute.data.history

import com.fuelroute.data.db.RouteSearchDao
import com.fuelroute.data.db.TripDao
import com.fuelroute.domain.history.RouteSearchCandidate
import com.fuelroute.domain.history.TripMatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the trip <-> route-search link. Auto-linking happens when a trip closes; the
 * "יצאתי במסלול הזה" action uses [linkTrip]/[linkLatestUnlinkedTrip] for an explicit match.
 *
 * A search already linked to another trip is never reused, so a trip and a search stay
 * one-to-one.
 */
@Singleton
class TripLinker @Inject constructor(
    private val tripDao: TripDao,
    private val routeSearchDao: RouteSearchDao,
) {

    /**
     * Links a just-closed [tripId] to the nearest unlinked search within
     * [TripMatcher.LINK_WINDOW_MS] before [tripStartMs]. No-op when nothing qualifies.
     */
    suspend fun autoLink(tripId: Long, tripStartMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val candidates = routeSearchDao
            .recentWithinWindow(tripStartMs - TripMatcher.LINK_WINDOW_MS)
            .map { RouteSearchCandidate(id = it.id, timestampMs = it.timestampMs) }
        val match = TripMatcher.bestMatch(tripStartMs, candidates, linkedSearchIds()) ?: return
        tripDao.linkToRouteSearch(tripId, match.id.toInt(), nowMs)
    }

    /** Explicit "יצאתי במסלול הזה": links [tripId] to [routeSearchId]. */
    suspend fun linkTrip(tripId: Long, routeSearchId: Long, nowMs: Long = System.currentTimeMillis()) {
        tripDao.linkToRouteSearch(tripId, routeSearchId.toInt(), nowMs)
    }

    /**
     * Manual arm for the most recent closed-but-unlinked trip (within
     * [MANUAL_LINK_WINDOW_MS]). Returns the linked trip id, or null when there is none.
     */
    suspend fun linkLatestUnlinkedTrip(
        routeSearchId: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Long? {
        val trip = tripDao.findUnlinkedSince(nowMs - MANUAL_LINK_WINDOW_MS).firstOrNull() ?: return null
        linkTrip(trip.id, routeSearchId, nowMs)
        return trip.id
    }

    /**
     * Search ids that already own a trip. The candidate window is tiny (a search only
     * competes for 30 min), so scanning recent closed trips is sufficient to keep the
     * link one-to-one without a dedicated DAO query.
     */
    private suspend fun linkedSearchIds(): Set<Long> =
        tripDao.recentClosed(LINKED_SCAN_LIMIT)
            .mapNotNull { it.routeSearchId?.toLong() }
            .toSet()

    companion object {
        const val MANUAL_LINK_WINDOW_MS = 6L * 60 * 60 * 1000
        private const val LINKED_SCAN_LIMIT = 500
    }
}