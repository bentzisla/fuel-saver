package com.fuelroute.data.history

import com.fuelroute.data.db.RouteSearchDao
import com.fuelroute.data.db.RouteSearchEntity
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.TripEntity
import com.fuelroute.domain.history.DriveOutcome
import com.fuelroute.domain.history.PredictionAccuracy
import javax.inject.Inject
import javax.inject.Singleton

/** Where a history row sits between "recommended" and "actually measured". */
enum class RideState {
    /** A search exists but no measured OBD outcome is available yet. */
    WAITING_OBD,

    /** Search and drive are paired and both predicted and actual numbers exist. */
    LINKED,

    /** A logged drive with no preceding search, so there is nothing to compare against. */
    NO_PREDICTION,
}

/** A search the user can pick to pair with an unlinked drive. */
data class LinkableSearch(
    val id: Long,
    val originLabel: String,
    val destinationLabel: String,
    val timestampMs: Long,
)

/**
 * One row of the History screen: a route search and, when the drive was logged, the
 * trip that took it. Orphan trips (a drive with no matching search) have a null search
 * side and are still surfaced.
 */
data class DriveHistoryEntry(
    val searchId: Long?,
    val tripId: Long?,
    val originLabel: String?,
    val destinationLabel: String?,
    val timestampMs: Long,
    val predictedCost: Double?,
    val predictedLiters: Double?,
    val predictedMinutes: Double?,
    val distanceKm: Double?,
    val actualCost: Double?,
    val actualLiters: Double?,
    val actualMinutes: Double?,
    val pricePerLiterAtSearch: Double?,
    val pricePerLiterAtTrip: Double?,
    val savedAmount: Double,
) {
    val hasActual: Boolean get() = tripId != null && actualCost != null

    /** A search whose route was never actually driven (no linked OBD trip). */
    val isUndrivenSearch: Boolean get() = searchId != null && !hasActual

    /** A logged drive the user can still pair with a search by hand. */
    val canLinkManually: Boolean get() = tripId != null && searchId == null

    /** The combined "ride" state label shown on the card. */
    val rideState: RideState
        get() = when {
            searchId == null -> RideState.NO_PREDICTION
            hasActual -> RideState.LINKED
            else -> RideState.WAITING_OBD
        }
}

data class DriveHistory(
    val entries: List<DriveHistoryEntry>,
    val accuracyPct: Double?,
)

/**
 * Read-side join of `route_search` and `trip` (trip.routeSearchId) plus the rolling
 * accuracy over the most recent linked drives. Rooms' DAOs expose the two tables
 * separately, so the join is assembled in memory over a bounded recent window.
 */
@Singleton
class DriveHistoryRepository @Inject constructor(
    private val routeSearchDao: RouteSearchDao,
    private val tripDao: TripDao,
    private val tripLinker: TripLinker,
) {

    suspend fun recent(limit: Int = DEFAULT_LIMIT): DriveHistory {
        val searches = routeSearchDao.recent(limit)
        val trips = tripDao.recentClosed(limit)
        val tripBySearchId = trips
            .filter { it.routeSearchId != null }
            .associateBy { it.routeSearchId!!.toLong() }

        val fromSearches = searches.map { it.toEntry(tripBySearchId[it.id]) }
        val orphanTrips = trips.filter { it.routeSearchId == null }.map { it.toOrphanEntry() }
        val entries = (fromSearches + orphanTrips).sortedByDescending { it.timestampMs }

        val accuracy = PredictionAccuracy.mape(
            entries.asSequence()
                .filter { it.tripId != null && (it.predictedCost ?: 0.0) > 0.0 && (it.actualCost ?: 0.0) > 0.0 }
                .take(ACCURACY_WINDOW)
                .map { DriveOutcome(predictedCost = it.predictedCost!!, actualCost = it.actualCost!!) }
                .toList(),
        )

        return DriveHistory(entries = entries, accuracyPct = accuracy)
    }

    /**
     * Searches that no trip owns yet, newest first, for the manual "קשר נסיעה" picker.
     */
    suspend fun linkCandidates(limit: Int = LINK_CANDIDATE_LIMIT): List<LinkableSearch> {
        val linked = tripDao.recentClosed(LINKED_SCAN_LIMIT)
            .mapNotNull { it.routeSearchId?.toLong() }
            .toSet()
        return routeSearchDao.recent(limit)
            .filter { it.id !in linked }
            .map {
                LinkableSearch(
                    id = it.id,
                    originLabel = it.originLabel,
                    destinationLabel = it.destinationLabel,
                    timestampMs = it.timestampMs,
                )
            }
    }

    /** Manual pairing: links an orphan drive to the chosen search. */
    suspend fun linkTrip(tripId: Long, searchId: Long) {
        tripLinker.linkTrip(tripId, searchId)
    }

    /**
     * One-tap pairing: links an orphan drive to the best unlinked search near its start.
     * Returns true when a link was written.
     */
    suspend fun linkTripToNearest(tripId: Long, tripStartMs: Long): Boolean =
        tripLinker.autoLink(tripId, tripStartMs) != null

    private fun RouteSearchEntity.toEntry(trip: TripEntity?): DriveHistoryEntry {
        val cost = selectedPredictedCost.takeIf { it > 0.0 } ?: cheapestCost
        val liters = selectedPredictedLiters.takeIf { it > 0.0 } ?: predictedLiters
        val minutes = selectedPredictedMinutes.takeIf { it > 0.0 } ?: durationMin
        return DriveHistoryEntry(
            searchId = id,
            tripId = trip?.id,
            originLabel = originLabel,
            destinationLabel = destinationLabel,
            timestampMs = timestampMs,
            predictedCost = cost,
            predictedLiters = liters,
            predictedMinutes = minutes,
            distanceKm = trip?.distanceKm ?: distanceKm,
            actualCost = trip?.actualCost,
            actualLiters = trip?.fuelL,
            actualMinutes = trip?.let { (it.endedAtMs - it.startedAtMs) / 60_000.0 },
            pricePerLiterAtSearch = pricePerLiterAtSearch.takeIf { it > 0.0 },
            pricePerLiterAtTrip = trip?.pricePerLiterAtTrip?.takeIf { it > 0.0 },
            savedAmount = savedAmount,
        )
    }

    private fun TripEntity.toOrphanEntry() = DriveHistoryEntry(
        searchId = null,
        tripId = id,
        originLabel = null,
        destinationLabel = null,
        timestampMs = startedAtMs,
        predictedCost = null,
        predictedLiters = null,
        predictedMinutes = null,
        distanceKm = distanceKm,
        actualCost = actualCost.takeIf { it > 0.0 },
        actualLiters = fuelL,
        actualMinutes = (endedAtMs - startedAtMs) / 60_000.0,
        pricePerLiterAtSearch = null,
        pricePerLiterAtTrip = pricePerLiterAtTrip.takeIf { it > 0.0 },
        savedAmount = 0.0,
    )

    companion object {
        const val DEFAULT_LIMIT = 50
        const val ACCURACY_WINDOW = 20
        const val LINK_CANDIDATE_LIMIT = 30
        private const val LINKED_SCAN_LIMIT = 500
    }
}
