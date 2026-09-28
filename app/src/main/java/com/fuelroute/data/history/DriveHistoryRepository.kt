package com.fuelroute.data.history

import com.fuelroute.data.db.RouteSearchDao
import com.fuelroute.data.db.RouteSearchEntity
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.TripEntity
import com.fuelroute.data.db.TripSource
import com.fuelroute.domain.history.DriveOutcome
import com.fuelroute.domain.history.PredictionAccuracy
import com.fuelroute.domain.history.SearchKey
import com.fuelroute.domain.history.SearchRecordPolicy
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
    /** Average speed of the driven trip; null for an undriven search. */
    val avgSpeedKmh: Double? = null,
) {
    val hasActual: Boolean get() = tripId != null && actualCost != null

    /**
     * Actual average consumption, or null when it is not meaningful (no drive, or a drive too
     * short for fuel/distance to mean anything).
     */
    val actualLitersPer100Km: Double?
        get() {
            val liters = actualLiters?.takeIf { hasActual } ?: return null
            val distance = distanceKm?.takeIf { it >= LearnedDataPlausibility.MIN_TRIP_DISTANCE_KM } ?: return null
            return liters / distance * 100.0
        }

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
        val trips = tripDao.recentClosedForVehicle(vehicleId, limit)
        val tripBySearchId = trips
            .filter { it.routeSearchId != null }
            .associateBy { it.routeSearchId!!.toLong() }
        val searches = visibleSearches(routeSearchDao.recent(limit), tripBySearchId)

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
        val tripBySearchId = tripDao.recentClosedForVehicle(vehicleId, LINKED_SCAN_LIMIT)
            .filter { it.routeSearchId != null }
            .associateBy { it.routeSearchId!!.toLong() }
        return visibleSearches(routeSearchDao.recent(limit), tripBySearchId)
            .filter { it.id !in tripBySearchId }
            .map {
                LinkableSearch(
                    id = it.id,
                    originLabel = it.originLabel,
                    destinationLabel = it.destinationLabel,
                    timestampMs = it.timestampMs,
                )
            }
    }

    /**
     * [searches] minus the duplicates of another search for the same ride (see
     * [SearchRecordPolicy.hiddenDuplicates]): refreshes recorded before searches were deduplicated
     * on write would otherwise linger as "waiting for OBD" twins of a ride that was already driven.
     */
    private fun visibleSearches(
        searches: List<RouteSearchEntity>,
        tripBySearchId: Map<Long, TripEntity>,
    ): List<RouteSearchEntity> {
        val hidden = SearchRecordPolicy.hiddenDuplicates(
            searches.map {
                val trip = tripBySearchId[it.id]
                SearchKey(
                    id = it.id,
                    timestampMs = it.timestampMs,
                    originLabel = it.originLabel,
                    destinationLabel = it.destinationLabel,
                    destinationPlaceId = it.destinationPlaceId,
                    distanceKm = it.distanceKm,
                    linked = trip != null,
                    tripEndedAtMs = trip?.endedAtMs,
                )
            },
        )
        return searches.filter { it.id !in hidden }
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
     *
     * - **Measured columns** (distance/fuel/idle) are the sums of the originals' OBD columns; a
     *   manual-only part contributes its window but no "measured" numbers, so refuel calibration
     *   and data repair never see a typed-in figure as an OBD measurement. `actualCost` is the sum
     *   of the parts' own OBD costs, so parts driven at different prices stay exact.
     * - **Manual entries survive**: when any part carries one (or is a manual-only ride), the
     *   merged drive's manual cost is the sum of every part's displayed cost (manual where the
     *   user entered one, OBD otherwise), so the total History showed before the merge is kept.
     * - **The route-search link survives**: the merged drive keeps the link of its earliest linked
     *   part (the ride the user searched for before setting off; a stop mid-way is what splits one
     *   drive into several). Links of later parts revert to undriven searches.
     *
     * Returns the new trip id, or null when the ids are not a valid same-vehicle set.
     */
    suspend fun mergeTrips(ids: List<Long>): Long? {
        val uniqueIds = ids.distinct()
        if (uniqueIds.size < 2) return null
        val trips = uniqueIds.mapNotNull { tripDao.findById(it) }.sortedBy { it.startedAtMs }
        if (trips.size < 2) return null
        val vehicleId = trips.first().vehicleId
        if (trips.any { it.vehicleId != vehicleId }) return null
        // Never mix simulated ("הדגמה") rides with real drives: all inputs must agree.
        val demoCount = trips.count { it.source == TripSource.DEMO }
        if (demoCount != 0 && demoCount != trips.size) return null

        val window = TripSplitCalculator.merge(trips.map { it.toRawTotals() }) ?: return null
        val measuredParts = trips.filter { it.source != TripSource.MANUAL }.ifEmpty { trips }
        val measured = TripSplitCalculator.merge(measuredParts.map { it.toRawTotals() }) ?: return null

        val price = trips.last().pricePerLiterAtTrip.takeIf { it > 0.0 }
            ?: trips.mapNotNull { it.pricePerLiterAtTrip.takeIf { p -> p > 0.0 } }.maxOrNull()
            ?: 0.0
        val linkedPart = trips.firstOrNull { it.routeSearchId != null }
        val hasManual = trips.any { it.manualCost != null || it.source == TripSource.MANUAL }
        val hasManualDistance = trips.any { it.manualDistanceKm != null || it.source == TripSource.MANUAL }

        val merged = TripEntity(
            vehicleId = vehicleId,
            startedAtMs = window.startedAtMs,
            endedAtMs = window.endedAtMs,
            distanceKm = measured.distanceKm,
            fuelL = measured.fuelL,
            avgSpeedKmh = TripSplitCalculator.avgSpeedKmh(measured.distanceKm, window.startedAtMs, window.endedAtMs),
            maxSpeedKmh = measured.maxSpeedKmh,
            idleSeconds = measured.idleSeconds,
            isOpen = 0,
            routeSearchId = linkedPart?.routeSearchId,
            linkedAtMs = linkedPart?.linkedAtMs,
            coldStartFuelL = trips.sumOf { it.coldStartFuelL },
            actualCost = measuredParts.sumOf { it.obdCost() },
            pricePerLiterAtTrip = price,
            source = when {
                trips.all { it.source == TripSource.DEMO } -> TripSource.DEMO
                trips.all { it.source == TripSource.MANUAL } -> TripSource.MANUAL
                else -> TripSource.REAL
            },
            manualCost = if (hasManual) trips.sumOf { it.displayActualCost() ?: 0.0 } else null,
            manualDistanceKm = if (hasManualDistance) trips.sumOf { it.effectiveDistanceKm() } else null,
            // Parts had different (or no) consumption entries; the merged liters come from cost/price.
            manualLitersPer100Km = null,
            manualEnteredAtMs = if (hasManual) {
                trips.mapNotNull { it.manualEnteredAtMs }.maxOrNull() ?: System.currentTimeMillis()
            } else {
                null
            },
        )
        return tripDao.replaceTrips(uniqueIds, listOf(merged)).firstOrNull()
    }

    /**
     * Splits one trip at [splitAtMs] into two, apportioning distance/fuel/idle by elapsed time
     * (best-effort; see [TripSplitCalculator]). A manual entry is apportioned the same way (its
     * consumption, a rate, is kept on both halves), so a split never silently drops the cost the
     * user typed in. The first half keeps the original cold-start fuel and route-search link, the
     * second is unlinked. Returns the two new trip ids, or null when the split point is not
     * strictly inside the drive.
     */
    suspend fun splitTrip(id: Long, splitAtMs: Long): Pair<Long, Long>? {
        val trip = tripDao.findById(id) ?: return null
        val fraction = TripSplitCalculator.splitFraction(trip.startedAtMs, trip.endedAtMs, splitAtMs) ?: return null
        val (firstTotals, secondTotals) =
            TripSplitCalculator.split(trip.toRawTotals(), splitAtMs) ?: return null
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
            manualCost = trip.manualCost?.times(fraction),
            manualDistanceKm = trip.manualDistanceKm?.times(fraction),
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
            manualCost = trip.manualCost?.times(1.0 - fraction),
            manualDistanceKm = trip.manualDistanceKm?.times(1.0 - fraction),
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
            avgSpeedKmh = trip?.effectiveAvgSpeedKmh(),
        )
    }

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
        avgSpeedKmh = effectiveAvgSpeedKmh(),
    )

    /** The measured (raw OBD) columns, the input to the merge/split math. */
    private fun TripEntity.toRawTotals() = TripTotals(
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        distanceKm = distanceKm,
        fuelL = fuelL,
        idleSeconds = idleSeconds,
        maxSpeedKmh = maxSpeedKmh,
    )

    /** The part's own OBD cost: the stored one, else its fuel at its price. */
    private fun TripEntity.obdCost(): Double =
        actualCost.takeIf { it > 0.0 } ?: (fuelL * pricePerLiterAtTrip).coerceAtLeast(0.0)

    companion object {
        const val DEFAULT_LIMIT = 50
        const val ACCURACY_WINDOW = 20
        const val LINK_CANDIDATE_LIMIT = 30
        private const val LINKED_SCAN_LIMIT = 500

        /** Fallback trip duration for a manual entry whose search has no usable predicted minutes. */
        private const val DEFAULT_MANUAL_TRIP_MINUTES = 20.0
    }
}
