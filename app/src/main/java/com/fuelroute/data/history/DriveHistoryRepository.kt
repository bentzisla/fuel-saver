package com.fuelroute.data.history

import com.fuelroute.data.db.RouteSearchDao
import com.fuelroute.data.db.RouteSearchEntity
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.TripEntity
import com.fuelroute.data.db.TripSource
import com.fuelroute.domain.history.DriveOutcome
import com.fuelroute.domain.history.ManualCostCalculator
import com.fuelroute.domain.history.PredictionAccuracy
import com.fuelroute.domain.history.TripSplitCalculator
import com.fuelroute.domain.history.TripTotals
import com.fuelroute.domain.learning.LearnedDataPlausibility
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
    /**
     * Display/sort key. For a linked ride this is the route-search time; for an orphan drive it
     * is the trip start. Use [tripStartedAtMs]/[tripEndedAtMs] for anything that reasons about
     * the actual drive window (merge proximity, split anchor).
     */
    val timestampMs: Long,
    /** Underlying `TripEntity.startedAtMs`; null when the row is an undriven search. */
    val tripStartedAtMs: Long? = null,
    /** Underlying `TripEntity.endedAtMs`; null when the row is an undriven search. */
    val tripEndedAtMs: Long? = null,
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
    /** True for a simulated "הדגמה" ride, which must never be mistaken for a real drive. */
    val isDemo: Boolean = false,
    /** Manual post-drive entry recorded without OBD; when present it wins over OBD values. */
    val manualCost: Double? = null,
    val manualDistanceKm: Double? = null,
    val manualLitersPer100Km: Double? = null,
) {
    val hasActual: Boolean get() = tripId != null && actualCost != null

    /** True when the user recorded this drive's cost by hand (no OBD). */
    val hasManualEntry: Boolean get() = manualCost != null

    /**
     * True whenever there is a ride to attach a manual cost to: a driven trip (with or without
     * a usable OBD cost — an existing OBD/manual value can always be overridden), or a search
     * that was never driven with OBD at all. The only entries this excludes are ones with
     * neither id, which [rideState] never actually produces.
     */
    val canEnterManualCost: Boolean
        get() = tripId != null || searchId != null

    /**
     * Stable key for multi-select: the trip id, or a negated search id for an undriven search
     * (trip ids are positive autoincrement values, so the two never collide).
     */
    val selectionId: Long? get() = tripId ?: searchId?.let { -it }

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

    /**
     * Recent history for [vehicleId]. Trips (and therefore the linked/orphan side) are scoped to
     * the active vehicle; route searches stay global because a search is not tied to a car until
     * it is actually driven.
     */
    suspend fun recent(vehicleId: String, limit: Int = DEFAULT_LIMIT): DriveHistory {
        val searches = routeSearchDao.recent(limit)
        val trips = tripDao.recentClosedForVehicle(vehicleId, limit)
        val tripBySearchId = trips
            .filter { it.routeSearchId != null }
            .associateBy { it.routeSearchId!!.toLong() }

        val fromSearches = searches.map { it.toEntry(tripBySearchId[it.id]) }
        val orphanTrips = trips.filter { it.routeSearchId == null }.map { it.toOrphanEntry() }
        val entries = (fromSearches + orphanTrips).sortedByDescending { it.timestampMs }

        val accuracy = PredictionAccuracy.mape(
            entries.asSequence()
                .filter {
                    it.tripId != null && !it.isDemo &&
                        (it.predictedCost ?: 0.0) > 0.0 && (it.actualCost ?: 0.0) > 0.0
                }
                .take(ACCURACY_WINDOW)
                .map { DriveOutcome(predictedCost = it.predictedCost!!, actualCost = it.actualCost!!) }
                .toList(),
        )

        return DriveHistory(entries = entries, accuracyPct = accuracy)
    }

    /**
     * Searches that no trip of [vehicleId] owns yet, newest first, for the manual
     * "קשר נסיעה" picker. The linked-set scan is scoped to the active vehicle.
     */
    suspend fun linkCandidates(vehicleId: String, limit: Int = LINK_CANDIDATE_LIMIT): List<LinkableSearch> {
        val linked = tripDao.recentClosedForVehicle(vehicleId, LINKED_SCAN_LIMIT)
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

    /**
     * Deletes one History entry and the row(s) behind it:
     * - a drive ([DriveHistoryEntry.tripId] != null) deletes the trip; a search it was linked
     *   to simply reverts to an undriven search;
     * - an undriven search ([DriveHistoryEntry.searchId] != null with no trip) deletes the
     *   search after first clearing the link on any trip that still owns it.
     */
    suspend fun delete(entry: DriveHistoryEntry) {
        val tripId = entry.tripId
        if (tripId != null) {
            tripDao.deleteById(tripId)
            return
        }
        val searchId = entry.searchId ?: return
        tripDao.unlinkTripsForSearch(searchId.toInt())
        routeSearchDao.deleteById(searchId)
    }

    /**
     * Persists a manual post-drive entry for [tripId]. Pass null distance/consumption for
     * direct-cost mode; the derived cost is what the UI already previewed. Overwrites any
     * previous manual entry on the trip, so this also serves as "edit" for one already set.
     */
    suspend fun setManualCost(
        tripId: Long,
        cost: Double,
        distanceKm: Double? = null,
        litersPer100Km: Double? = null,
        enteredAtMs: Long = System.currentTimeMillis(),
    ) {
        tripDao.updateManualCost(
            tripId = tripId,
            cost = cost,
            distanceKm = distanceKm,
            litersPer100Km = litersPer100Km,
            enteredAtMs = enteredAtMs,
        )
    }

    /**
     * Records a manual post-drive cost for [entry], the common entry point the History UI uses
     * (it covers both "enter" and "edit"). When the ride already has a trip — OBD-recorded or a
     * previously created manual one — the entry is written onto it with [setManualCost]. When it
     * is an undriven search (the everyday case this feature was missing for: a search the user
     * drove without OBD logging running), a manual [TripEntity] is created and linked to the
     * search directly, so History's existing search<->trip join, the savings total and the
     * accuracy % all pick it up with no further changes.
     *
     * The manual trip's window is the search's departure time (falling back to when it was
     * searched) plus its predicted duration; its distance is the search's predicted distance.
     * [fallbackPricePerLiter] (normally the live price the dialog already previewed with) is used
     * as the trip's `pricePerLiterAtTrip` only when the search itself never recorded a price, so
     * a direct-cost entry can still resolve to liters for [DriveHistoryEntry.actualLiters]
     * (used by prediction accuracy and calibration).
     *
     * Double-count note: a manual trip is created already linked (`routeSearchId` set), so both
     * [TripLinker.autoLink] and [linkCandidates] treat that search as already taken — an OBD trip
     * recorded afterwards for the same drive will NOT silently attach to it. It stays an orphan
     * the user can link by hand (which will, in turn, replace this manual entry as the search's
     * linked trip); nothing here merges the two automatically.
     *
     * Returns the id of the trip the entry was written to (existing or newly created).
     */
    suspend fun recordManualCost(
        vehicleId: String,
        entry: DriveHistoryEntry,
        cost: Double,
        distanceKm: Double? = null,
        litersPer100Km: Double? = null,
        fallbackPricePerLiter: Double = 0.0,
        enteredAtMs: Long = System.currentTimeMillis(),
    ): Long {
        val tripId = entry.tripId
        if (tripId != null) {
            setManualCost(tripId, cost, distanceKm, litersPer100Km, enteredAtMs)
            return tripId
        }
        val searchId = requireNotNull(entry.searchId) {
            "Manual cost entry needs a trip or a search to attach to"
        }
        return createManualTrip(vehicleId, searchId, cost, distanceKm, litersPer100Km, fallbackPricePerLiter, enteredAtMs)
    }

    /** Builds and inserts the manual [TripEntity] described in [recordManualCost]. */
    private suspend fun createManualTrip(
        vehicleId: String,
        searchId: Long,
        cost: Double,
        distanceKm: Double?,
        litersPer100Km: Double?,
        fallbackPricePerLiter: Double,
        enteredAtMs: Long,
    ): Long {
        val search = requireNotNull(routeSearchDao.findById(searchId)) {
            "Manual cost entry: route search $searchId not found"
        }
        val startedAtMs = search.departureTimeMs ?: search.timestampMs
        val minutes = (search.selectedPredictedMinutes.takeIf { it > 0.0 } ?: search.durationMin)
            .takeIf { it > 0.0 } ?: DEFAULT_MANUAL_TRIP_MINUTES
        val endedAtMs = startedAtMs + (minutes * 60_000.0).toLong()
        val price = search.pricePerLiterAtSearch.takeIf { it > 0.0 } ?: fallbackPricePerLiter
        val trip = TripEntity(
            vehicleId = vehicleId,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            distanceKm = search.distanceKm,
            fuelL = 0.0,
            avgSpeedKmh = search.distanceKm / (minutes / 60.0),
            maxSpeedKmh = 0.0,
            idleSeconds = 0.0,
            isOpen = 0,
            routeSearchId = searchId.toInt(),
            actualCost = 0.0,
            pricePerLiterAtTrip = price,
            linkedAtMs = enteredAtMs,
            source = TripSource.MANUAL,
            manualCost = cost,
            manualDistanceKm = distanceKm,
            manualLitersPer100Km = litersPer100Km,
            manualEnteredAtMs = enteredAtMs,
        )
        return tripDao.insert(trip)
    }

    /**
     * Bulk delete for the History multi-select action. Mirrors [delete] per entry: trips are
     * removed first, then each undriven search is unlinked and deleted.
     */
    suspend fun deleteMany(entries: List<DriveHistoryEntry>) {
        val tripIds = entries.mapNotNull { it.tripId }
        if (tripIds.isNotEmpty()) tripDao.deleteByIds(tripIds)
        entries.asSequence()
            .filter { it.tripId == null }
            .mapNotNull { it.searchId }
            .forEach { searchId ->
                tripDao.unlinkTripsForSearch(searchId.toInt())
                routeSearchDao.deleteById(searchId)
            }
    }

    /**
     * Merges 2+ trips of the same vehicle into one long drive and deletes the originals.
     * Distance/fuel/idle are summed, the time span is min-start..max-end, and avg speed is
     * recomputed. `actualCost` is recomputed from the latest trip's price (falling back to the
     * sum of the originals when no price is known). Returns the new trip id, or null when the
     * ids are not a valid same-vehicle set.
     */
    suspend fun mergeTrips(ids: List<Long>): Long? {
        val uniqueIds = ids.distinct()
        if (uniqueIds.size < 2) return null
        val trips = uniqueIds.mapNotNull { tripDao.findById(it) }
        if (trips.size < 2) return null
        val vehicleId = trips.first().vehicleId
        if (trips.any { it.vehicleId != vehicleId }) return null
        // Never mix simulated ("הדגמה") rides with real drives: all inputs must agree.
        val demoCount = trips.count { it.source == TripSource.DEMO }
        if (demoCount != 0 && demoCount != trips.size) return null
        val totals = TripSplitCalculator.merge(trips.map { it.toTotals() }) ?: return null

        val latest = trips.maxByOrNull { it.startedAtMs }
        val price = latest?.pricePerLiterAtTrip?.takeIf { it > 0.0 }
            ?: trips.mapNotNull { it.pricePerLiterAtTrip.takeIf { p -> p > 0.0 } }.maxOrNull()
            ?: 0.0
        val cost = if (price > 0.0) totals.fuelL * price else trips.sumOf { it.actualCost }

        val merged = TripEntity(
            vehicleId = vehicleId,
            startedAtMs = totals.startedAtMs,
            endedAtMs = totals.endedAtMs,
            distanceKm = totals.distanceKm,
            fuelL = totals.fuelL,
            avgSpeedKmh = TripSplitCalculator.avgSpeedKmh(
                totals.distanceKm,
                totals.startedAtMs,
                totals.endedAtMs,
            ),
            maxSpeedKmh = totals.maxSpeedKmh,
            idleSeconds = totals.idleSeconds,
            isOpen = 0,
            // The merged span covers 2+ drives, so no single route search fully describes it.
            // Clearing the link leaves the originals' searches as undriven (they are not deleted),
            // and the merge deletes the originals via replaceTrips, so none double-appear.
            routeSearchId = null,
            coldStartFuelL = trips.sumOf { it.coldStartFuelL },
            actualCost = cost,
            pricePerLiterAtTrip = price,
            source = if (trips.all { it.source == TripSource.DEMO }) TripSource.DEMO else TripSource.REAL,
        )
        return tripDao.replaceTrips(uniqueIds, listOf(merged)).firstOrNull()
    }

    /**
     * Splits one trip at [splitAtMs] into two, apportioning distance/fuel/idle by elapsed time
     * (best-effort; see [TripSplitCalculator]). The first half keeps the original cold-start fuel
     * and route-search link, the second is unlinked. Returns the two new trip ids, or null when
     * the split point is not strictly inside the drive.
     */
    suspend fun splitTrip(id: Long, splitAtMs: Long): Pair<Long, Long>? {
        val trip = tripDao.findById(id) ?: return null
        val (firstTotals, secondTotals) =
            TripSplitCalculator.split(trip.toTotals(), splitAtMs) ?: return null
        val price = trip.pricePerLiterAtTrip

        val first = trip.copy(
            id = 0,
            startedAtMs = firstTotals.startedAtMs,
            endedAtMs = firstTotals.endedAtMs,
            distanceKm = firstTotals.distanceKm,
            fuelL = firstTotals.fuelL,
            avgSpeedKmh = TripSplitCalculator.avgSpeedKmh(
                firstTotals.distanceKm,
                firstTotals.startedAtMs,
                firstTotals.endedAtMs,
            ),
            maxSpeedKmh = firstTotals.maxSpeedKmh,
            idleSeconds = firstTotals.idleSeconds,
            isOpen = 0,
            actualCost = splitCost(firstTotals.fuelL, trip.fuelL, trip.actualCost, price),
            manualCost = null,
            manualDistanceKm = null,
            manualLitersPer100Km = null,
            manualEnteredAtMs = null,
        )
        val second = trip.copy(
            id = 0,
            startedAtMs = secondTotals.startedAtMs,
            endedAtMs = secondTotals.endedAtMs,
            distanceKm = secondTotals.distanceKm,
            fuelL = secondTotals.fuelL,
            avgSpeedKmh = TripSplitCalculator.avgSpeedKmh(
                secondTotals.distanceKm,
                secondTotals.startedAtMs,
                secondTotals.endedAtMs,
            ),
            maxSpeedKmh = secondTotals.maxSpeedKmh,
            idleSeconds = secondTotals.idleSeconds,
            isOpen = 0,
            routeSearchId = null,
            linkedAtMs = null,
            coldStartFuelL = 0.0,
            actualCost = splitCost(secondTotals.fuelL, trip.fuelL, trip.actualCost, price),
            manualCost = null,
            manualDistanceKm = null,
            manualLitersPer100Km = null,
            manualEnteredAtMs = null,
        )
        val inserted = tripDao.replaceTrips(listOf(id), listOf(first, second))
        if (inserted.size < 2) return null
        return inserted[0] to inserted[1]
    }

    /** Recomputes a split half's cost from its apportioned fuel, or apportions the original cost. */
    private fun splitCost(
        partFuelL: Double,
        totalFuelL: Double,
        originalCost: Double,
        price: Double,
    ): Double = when {
        price > 0.0 -> partFuelL * price
        totalFuelL > 0.0 -> originalCost * (partFuelL / totalFuelL)
        else -> 0.0
    }

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
            tripStartedAtMs = trip?.startedAtMs,
            tripEndedAtMs = trip?.endedAtMs,
            predictedCost = cost,
            predictedLiters = liters,
            predictedMinutes = minutes,
            distanceKm = trip?.effectiveDistanceKm() ?: distanceKm,
            actualCost = trip?.displayActualCost(),
            actualLiters = trip?.displayFuelL(),
            actualMinutes = trip?.let { (it.endedAtMs - it.startedAtMs) / 60_000.0 },
            pricePerLiterAtSearch = pricePerLiterAtSearch.takeIf { it > 0.0 },
            pricePerLiterAtTrip = trip?.pricePerLiterAtTrip?.takeIf { it > 0.0 },
            // Savings of the route the user actually picked vs the fastest one; the stored
            // savedAmount is always relative to the top-ranked route, so choosing the fastest
            // route must not still count as a saving.
            savedAmount = if (fastestCost > 0.0) (fastestCost - cost).coerceAtLeast(0.0) else savedAmount,
            isDemo = trip?.source == TripSource.DEMO,
            manualCost = trip?.manualCost,
            manualDistanceKm = trip?.manualDistanceKm,
            manualLitersPer100Km = trip?.manualLitersPer100Km,
        )
    }

    /**
     * A trip whose stored OBD fuel is physically impossible (recorded before the 0.7 OBD-data
     * fixes and not repairable from raw samples) shows no actual liters/cost instead of an
     * absurd one — unless the user entered a manual cost, which always wins. Checked on the raw
     * OBD columns, not on the manual overrides.
     */
    private fun TripEntity.isObdFuelPlausible(): Boolean =
        LearnedDataPlausibility.isTripPlausible(distanceKm, fuelL, (endedAtMs - startedAtMs) / 1000.0)

    /** History display cost: manual entry, else OBD cost when plausible, else null. */
    private fun TripEntity.displayActualCost(): Double? =
        manualCost ?: actualCost.takeIf { isObdFuelPlausible() }

    /** History display liters: manual entry, else OBD fuel when plausible, else null. */
    private fun TripEntity.displayFuelL(): Double? =
        manualLiters() ?: fuelL.takeIf { isObdFuelPlausible() }

    private fun TripEntity.toOrphanEntry() = DriveHistoryEntry(
        searchId = null,
        tripId = id,
        originLabel = null,
        destinationLabel = null,
        timestampMs = startedAtMs,
        tripStartedAtMs = startedAtMs,
        tripEndedAtMs = endedAtMs,
        predictedCost = null,
        predictedLiters = null,
        predictedMinutes = null,
        distanceKm = effectiveDistanceKm(),
        actualCost = displayActualCost()?.takeIf { it > 0.0 },
        actualLiters = displayFuelL(),
        actualMinutes = (endedAtMs - startedAtMs) / 60_000.0,
        pricePerLiterAtSearch = null,
        pricePerLiterAtTrip = pricePerLiterAtTrip.takeIf { it > 0.0 },
        savedAmount = 0.0,
        isDemo = source == TripSource.DEMO,
        manualCost = manualCost,
        manualDistanceKm = manualDistanceKm,
        manualLitersPer100Km = manualLitersPer100Km,
    )

    /**
     * The user's manual entry wins over the OBD measurement, but only as a read-time view:
     * the raw OBD columns are never overwritten.
     */

    private fun TripEntity.effectiveDistanceKm(): Double = manualDistanceKm ?: distanceKm

    private fun TripEntity.effectiveFuelL(): Double = manualLiters() ?: fuelL

    /** Liters implied by the manual entry, from distance+consumption or from cost/price. */
    private fun TripEntity.manualLiters(): Double? {
        val distance = manualDistanceKm
        val consumption = manualLitersPer100Km
        if (distance != null && consumption != null && pricePerLiterAtTrip > 0.0) {
            ManualCostCalculator.estimate(distance, consumption, pricePerLiterAtTrip)
                ?.let { return it.liters }
        }
        val cost = manualCost
        if (cost != null && pricePerLiterAtTrip > 0.0) return cost / pricePerLiterAtTrip
        return null
    }

    /** Pure view of a trip for the merge/split math (manual values win, as in History). */
    private fun TripEntity.toTotals() = TripTotals(
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        distanceKm = effectiveDistanceKm(),
        fuelL = effectiveFuelL(),
        idleSeconds = idleSeconds,
        maxSpeedKmh = maxSpeedKmh,
    )

    companion object {
        const val DEFAULT_LIMIT = 50
        const val ACCURACY_WINDOW = 20
        const val LINK_CANDIDATE_LIMIT = 30
        private const val LINKED_SCAN_LIMIT = 500

        /** Fallback trip duration for a manual entry whose search has no usable predicted minutes. */
        private const val DEFAULT_MANUAL_TRIP_MINUTES = 20.0
    }
}
