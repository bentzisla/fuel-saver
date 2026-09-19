package com.fuelroute.domain.model

enum class CongestionLevel(val speedFactor: Double) {
    NORMAL(1.0),
    SLOW(0.55),
    TRAFFIC_JAM(0.25),
}

data class RouteSegment(
    val distanceMeters: Double,
    val staticDurationSeconds: Double,
    val trafficDurationSeconds: Double? = null,
    val congestion: CongestionLevel = CongestionLevel.NORMAL,
)

data class Route(
    val id: String,
    val label: String? = null,
    val routeLabels: List<String> = emptyList(),
    val distanceMeters: Double,
    val staticDurationSeconds: Double,
    val durationSeconds: Double,
    val segments: List<RouteSegment>,
    val tollCost: Double? = null,
    val encodedPolyline: String? = null,
)