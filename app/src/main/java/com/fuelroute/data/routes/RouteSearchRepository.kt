package com.fuelroute.data.routes

import com.fuelroute.data.db.RouteSearchDao
import com.fuelroute.data.db.RouteSearchEntity
import com.fuelroute.data.db.TripDao
import com.fuelroute.domain.history.SearchKey
import com.fuelroute.domain.history.SearchRecordPolicy
import com.fuelroute.domain.history.TripMatcher
import javax.inject.Inject
import javax.inject.Singleton

data class RouteSearch(
    val id: Long = 0,
    val originLabel: String,
    val destinationLabel: String,
    val timestampMs: Long,
    val cheapestCost: Double,
    val savedAmount: Double,
    val predictedLiters: Double,
    val distanceKm: Double,
    val durationMin: Double,
    val selectedRouteIndex: Int = 0,
    val departureTimeMs: Long? = null,
    val tollUnknown: Boolean = false,
    val selectedPredictedCost: Double = 0.0,
    val selectedPredictedLiters: Double = 0.0,
    val selectedPredictedMinutes: Double = 0.0,
    val pricePerLiterAtSearch: Double = 0.0,
    val destinationPlaceId: String? = null,
    val destinationLat: Double? = null,
    val destinationLng: Double? = null,
    /** `FuelModelOverrides.effectiveFuelCorrection` the predictions above include. */
    val fuelCorrectionAtSearch: Double? = null,
)

interface RouteSearchRepository {
    suspend fun recent(limit: Int): List<RouteSearch>

    /** Inserts the search and returns its generated id so it can be updated later. */
    suspend fun add(search: RouteSearch): Long

    /**
     * Records a search as History's one row per ride (see [SearchRecordPolicy]): a new ride is
     * inserted, a pre-departure refresh of the same ride overwrites the previous row, and a
     * refresh made while that ride is already being driven records nothing. Returns the id of the
     * row that now describes this search, or null when nothing was recorded (so later selection
     * changes must not touch the ride's stored prediction).
     */
    suspend fun record(search: RouteSearch): Long? = add(search)

    /** Stores which ranked route the user actually opened/navigated. */
    suspend fun updateSelection(
        id: Long,
        selectedRouteIndex: Int,
        selectedPredictedCost: Double,
        selectedPredictedLiters: Double,
        selectedPredictedMinutes: Double,
        pricePerLiterAtSearch: Double,
        destinationPlaceId: String?,
        destinationLat: Double?,
        destinationLng: Double?,
    )
}

@Singleton
class DefaultRouteSearchRepository @Inject constructor(
    private val dao: RouteSearchDao,
    private val tripDao: TripDao,
) : RouteSearchRepository {

    override suspend fun recent(limit: Int): List<RouteSearch> =
        dao.recent(limit).map { it.toDomain() }

    override suspend fun add(search: RouteSearch): Long = dao.insert(search.toEntity())

    override suspend fun record(search: RouteSearch): Long? {
        val previous = dao.latest()
        val previousKey = previous?.let { it.toKey(linked = dao.isLinked(it.id)) }
        // A drive counts as underway even when it started shortly BEFORE the previous search (the
        // user pulled away, searched from the road, then refreshed): that is the same window in
        // which TripLinker would have linked the trip to that search.
        val driveUnderway = previous != null &&
            tripDao.recentOpenTrips().any {
                it.startedAtMs >= previous.timestampMs - TripMatcher.LINK_WINDOW_MS
            }
        val entity = search.toEntity()
        return when (SearchRecordPolicy.decide(previousKey, entity.toKey(linked = false), driveUnderway)) {
            SearchRecordPolicy.Action.INSERT -> dao.insert(entity)
            SearchRecordPolicy.Action.REPLACE -> {
                val id = requireNotNull(previous).id
                dao.update(entity.copy(id = id))
                id
            }
            SearchRecordPolicy.Action.SKIP -> null
        }
    }

    private fun RouteSearch.toEntity() = RouteSearchEntity(
        originLabel = originLabel,
        destinationLabel = destinationLabel,
        timestampMs = timestampMs,
        cheapestCost = cheapestCost,
        fastestCost = cheapestCost + savedAmount,
        savedAmount = savedAmount,
        predictedLiters = predictedLiters,
        distanceKm = distanceKm,
        durationMin = durationMin,
        selectedRouteIndex = selectedRouteIndex,
        departureTimeMs = departureTimeMs,
        tollUnknown = if (tollUnknown) 1 else 0,
        selectedPredictedCost = selectedPredictedCost,
        selectedPredictedLiters = selectedPredictedLiters,
        selectedPredictedMinutes = selectedPredictedMinutes,
        pricePerLiterAtSearch = pricePerLiterAtSearch,
        destinationPlaceId = destinationPlaceId,
        destinationLat = destinationLat,
        destinationLng = destinationLng,
        fuelCorrectionAtSearch = fuelCorrectionAtSearch,
    )

    private fun RouteSearchEntity.toKey(linked: Boolean) = SearchKey(
        id = id,
        timestampMs = timestampMs,
        originLabel = originLabel,
        destinationLabel = destinationLabel,
        destinationPlaceId = destinationPlaceId,
        distanceKm = distanceKm,
        linked = linked,
    )

    override suspend fun updateSelection(
        id: Long,
        selectedRouteIndex: Int,
        selectedPredictedCost: Double,
        selectedPredictedLiters: Double,
        selectedPredictedMinutes: Double,
        pricePerLiterAtSearch: Double,
        destinationPlaceId: String?,
        destinationLat: Double?,
        destinationLng: Double?,
    ) {
        dao.updateSelection(
            id = id,
            index = selectedRouteIndex,
            cost = selectedPredictedCost,
            liters = selectedPredictedLiters,
            minutes = selectedPredictedMinutes,
            pricePerLiter = pricePerLiterAtSearch,
            placeId = destinationPlaceId,
            lat = destinationLat,
            lng = destinationLng,
        )
    }

    private fun RouteSearchEntity.toDomain() = RouteSearch(
        id = id,
        originLabel = originLabel,
        destinationLabel = destinationLabel,
        timestampMs = timestampMs,
        cheapestCost = cheapestCost,
        savedAmount = savedAmount,
        predictedLiters = predictedLiters,
        distanceKm = distanceKm,
        durationMin = durationMin,
        selectedRouteIndex = selectedRouteIndex,
        departureTimeMs = departureTimeMs,
        tollUnknown = tollUnknown != 0,
        selectedPredictedCost = selectedPredictedCost,
        selectedPredictedLiters = selectedPredictedLiters,
        selectedPredictedMinutes = selectedPredictedMinutes,
        pricePerLiterAtSearch = pricePerLiterAtSearch,
        destinationPlaceId = destinationPlaceId,
        destinationLat = destinationLat,
        destinationLng = destinationLng,
        fuelCorrectionAtSearch = fuelCorrectionAtSearch,
    )
}