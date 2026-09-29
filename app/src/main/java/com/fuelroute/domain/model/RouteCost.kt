package com.fuelroute.domain.model

data class SegmentCost(
    val distanceKm: Double,
    val effectiveSpeedKmh: Double,
    val congestion: CongestionLevel,
    val litersPer100Km: Double,
    val liters: Double,
)

data class RouteCost(
    val route: Route,
    val fuelLiters: Double,
    val fuelCost: Double,
    val tollCost: Double,
    val totalCost: Double,
    val durationMinutes: Double,
    val distanceKm: Double,
    val avgSpeedKmh: Double,
    val segments: List<SegmentCost> = emptyList(),
    /**
     * Share (0..1) of this route's estimate backed by the car's learned OBD curve along its speed
     * mix ([com.fuelroute.domain.ranking.RouteConfidence.learnedShare]); null when not computed.
     */
    val learnedShare: Double? = null,
)