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
)