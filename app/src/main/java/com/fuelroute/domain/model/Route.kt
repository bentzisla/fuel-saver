package com.fuelroute.domain.model

import com.fuelroute.domain.fuel.ModelConstants

enum class CongestionLevel(val speedFactor: Double) {
    NORMAL(ModelConstants.NORMAL_FACTOR),
    SLOW(ModelConstants.SLOW_FACTOR),
    TRAFFIC_JAM(ModelConstants.JAM_FACTOR),
}

enum class TrafficResolution {
    PER_SEGMENT,
    ROUTE_AVERAGE,
    NONE,
}

data class RouteSegment(
    val distanceMeters: Double,
    val staticDurationSeconds: Double,
    val congestionFactor: Double = 1.0,
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
    val tollUnknown: Boolean = false,
    val trafficResolution: TrafficResolution = TrafficResolution.PER_SEGMENT,
    val encodedPolyline: String? = null,
)