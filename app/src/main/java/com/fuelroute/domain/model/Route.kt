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
    /**
     * Net elevation change over this segment in meters (end minus start; positive = climb),
     * from the Google Elevation API sampled along the route polyline. Null when elevation data
     * was never fetched, or the fetch failed — [FuelModel] then charges no grade term for the
     * segment and [Route.gradeDataMissing] tells the UI to show a hint that the climb was not
     * included, rather than silently underpricing an uphill route.
     */
    val elevationDeltaM: Double? = null,
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
    /**
     * True when this route's cost was computed without elevation data (no polyline, the
     * Elevation API call failed/was denied, or there was no network). Defaults to true so a
     * route nobody has enriched is treated as "grade unknown" rather than silently "flat".
     */
    val gradeDataMissing: Boolean = true,
)