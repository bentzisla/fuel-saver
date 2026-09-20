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
)

interface RouteSearchRepository {
    suspend fun recent(limit: Int): List<RouteSearch>
    suspend fun add(search: RouteSearch)
}

@Singleton
class DefaultRouteSearchRepository @Inject constructor(
    private val dao: RouteSearchDao,
) : RouteSearchRepository {

    override suspend fun recent(limit: Int): List<RouteSearch> =
        dao.recent(limit).map { it.toDomain() }

    override suspend fun add(search: RouteSearch) {
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
            ),
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
    )
}