package com.fuelroute.data.routes

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

const val ROUTES_FIELD_MASK = "routes.routeLabels,routes.description,routes.distanceMeters," +
    "routes.duration,routes.staticDuration,routes.polyline.encodedPolyline," +
    "routes.legs.distanceMeters,routes.legs.duration,routes.legs.staticDuration," +
    "routes.legs.polyline.encodedPolyline,routes.legs.steps.distanceMeters," +
    "routes.legs.steps.staticDuration,routes.legs.steps.polyline.encodedPolyline," +
    "routes.legs.travelAdvisory.speedReadingIntervals," +
    "routes.travelAdvisory.speedReadingIntervals,routes.travelAdvisory.tollInfo.estimatedPrice"

@Serializable
data class ComputeRoutesRequest(
    val origin: WaypointDto,
    val destination: WaypointDto,
    val travelMode: String = "DRIVE",
    val routingPreference: String = "TRAFFIC_AWARE_OPTIMAL",
    val computeAlternativeRoutes: Boolean = true,
    @EncodeDefault
    val requestedReferenceRoutes: List<String> = listOf("FUEL_EFFICIENT"),
    val departureTime: String? = null,
    val languageCode: String = "he",
    val units: String = "METRIC",
    val extraComputations: List<String> = listOf("TRAFFIC_ON_POLYLINE", "TOLLS"),
)

@Serializable
data class WaypointDto(
    val address: String? = null,
    val location: LocationDto? = null,
    val placeId: String? = null,
)

@Serializable
data class LocationDto(
    val latLng: LatLngDto,
)

@Serializable
data class LatLngDto(
    val latitude: Double,
    val longitude: Double,
)

@Serializable
data class ComputeRoutesResponse(
    val routes: List<RouteDto> = emptyList(),
)

@Serializable
data class RouteDto(
    val routeLabels: List<String> = emptyList(),
    val description: String? = null,
    val distanceMeters: Double = 0.0,
    val duration: String = "0s",
    val staticDuration: String? = null,
    val polyline: PolylineDto? = null,
    val legs: List<LegDto> = emptyList(),
    val travelAdvisory: TravelAdvisoryDto? = null,
)

@Serializable
data class PolylineDto(
    val encodedPolyline: String? = null,
)

@Serializable
data class LegDto(
    val distanceMeters: Double = 0.0,
    val duration: String = "0s",
    val staticDuration: String? = null,
    val steps: List<StepDto> = emptyList(),
    val polyline: PolylineDto? = null,
    val travelAdvisory: TravelAdvisoryDto? = null,
)

@Serializable
data class StepDto(
    val distanceMeters: Double = 0.0,
    val staticDuration: String? = null,
    val polyline: PolylineDto? = null,
)

@Serializable
data class TravelAdvisoryDto(
    val speedReadingIntervals: List<SpeedReadingIntervalDto> = emptyList(),
    val tollInfo: TollInfoDto? = null,
)

@Serializable
data class SpeedReadingIntervalDto(
    val startPolylinePointIndex: Int = 0,
    val endPolylinePointIndex: Int = 0,
    val speed: String = "NORMAL",
)

@Serializable
data class TollInfoDto(
    val estimatedPrice: List<MoneyDto> = emptyList(),
)

@Serializable
data class MoneyDto(
    val currencyCode: String = "ILS",
    val units: Long = 0,
    val nanos: Int = 0,
)