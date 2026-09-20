package com.fuelroute.data.routes

import com.fuelroute.data.db.RouteSearchDao
import com.fuelroute.data.db.RouteSearchEntity
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
)

interface RouteSearchRepository {
    suspend fun recent(limit: Int): List<RouteSearch>

    /** Inserts the search and returns its generated id so it can be updated later. */
    suspend fun add(search: RouteSearch): Long

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
) : RouteSearchRepository {

    override suspend fun recent(limit: Int): List<RouteSearch> =
        dao.recent(limit).map { it.toDomain() }

    override suspend fun add(search: RouteSearch): Long =
        dao.insert(
            RouteSearchEntity(
                originLabel = search.originLabel,
                destinationLabel = search.destinationLabel,
                timestampMs = search.timestampMs,
                cheapestCost = search.cheapestCost,
                fastestCost = search.cheapestCost + search.savedAmount,
                savedAmount = search.savedAmount,
                predictedLiters = search.predictedLiters,
                distanceKm = search.distanceKm,
                durationMin = search.durationMin,
                selectedRouteIndex = search.selectedRouteIndex,
                departureTimeMs = search.departureTimeMs,
                tollUnknown = if (search.tollUnknown) 1 else 0,
                selectedPredictedCost = search.selectedPredictedCost,
                selectedPredictedLiters = search.selectedPredictedLiters,
                selectedPredictedMinutes = search.selectedPredictedMinutes,
                pricePerLiterAtSearch = search.pricePerLiterAtSearch,
                destinationPlaceId = search.destinationPlaceId,
                destinationLat = search.destinationLat,
                destinationLng = search.destinationLng,
            ),
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
    )
}