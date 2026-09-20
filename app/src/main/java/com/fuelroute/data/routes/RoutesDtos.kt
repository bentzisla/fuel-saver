@file:OptIn(ExperimentalSerializationApi::class)

package com.fuelroute.data.routes

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
    @EncodeDefault
    val travelMode: String = "DRIVE",
    @EncodeDefault
    val routingPreference: String = "TRAFFIC_AWARE_OPTIMAL",
    @EncodeDefault
    val computeAlternativeRoutes: Boolean = true,
    // FUEL_EFFICIENT is unsupported in the origin country (IL) and returns HTTP 400.
    // Default empty = omitted; [RoutesRequestFactory] only sets it when explicitly requested
    // via RouteRequestOptions.requestFuelEfficient (default OFF). No region gate is applied,
    // so production never turns this on.
    val requestedReferenceRoutes: List<String> = emptyList(),
    val departureTime: String? = null, // NB: no @EncodeDefault — null must be omitted (past times → 400)
    // Null = omitted. The API applies the vehicle modifiers (emissionType) to the returned
    // routes and toll estimates; tollPasses/avoidTolls stay at their API defaults unless set.
    val routeModifiers: RouteModifiersDto? = null,
    @EncodeDefault
    val languageCode: String = "he",
    @EncodeDefault
    val units: String = "METRIC",
    @EncodeDefault
    val extraComputations: List<String> = listOf("TRAFFIC_ON_POLYLINE", "TOLLS"),
)

@Serializable
data class RouteModifiersDto(
    val vehicleInfo: VehicleInfoDto? = null,
    val tollPasses: List<String> = emptyList(),
    val avoidTolls: Boolean = false,
)

@Serializable
data class VehicleInfoDto(
    val emissionType: String,
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
    val distanceMeters: Int = 0,
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
    val distanceMeters: Int = 0,
    val duration: String = "0s",
    val staticDuration: String? = null,
    val steps: List<StepDto> = emptyList(),
    val polyline: PolylineDto? = null,
    val travelAdvisory: TravelAdvisoryDto? = null,
)

@Serializable
data class StepDto(
    val distanceMeters: Int = 0,
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