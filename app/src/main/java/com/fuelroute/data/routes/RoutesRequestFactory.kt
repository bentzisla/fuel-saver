package com.fuelroute.data.routes

import com.fuelroute.domain.model.FuelType
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Builds the `computeRoutes` request. Kept pure so tests can pin [nowMs] and assert the
 * exact body without a live clock.
 *
 * CRITICAL: the Routes API rejects a past `departureTime` for `DRIVE` with HTTP 400
 * (past times are TRANSIT-only). We therefore only ever send an explicit timestamp for a
 * strictly-future departure; "now" and any past value are encoded as null (field omitted).
 */
object RoutesRequestFactory {

    fun create(
        origin: RouteWaypoint,
        destination: RouteWaypoint,
        options: RouteRequestOptions,
        nowMs: Long,
    ): ComputeRoutesRequest {
        val departureTime = options.departureTimeMs
            ?.takeIf { it > nowMs }
            ?.let { isoUtc(it) }

        val routeModifiers = options.emissionType
            ?.takeIf { it.isNotBlank() }
            ?.let { RouteModifiersDto(vehicleInfo = VehicleInfoDto(emissionType = it)) }

        return ComputeRoutesRequest(
            origin = origin.toDto(),
            destination = destination.toDto(),
            departureTime = departureTime,
            routeModifiers = routeModifiers,
            // FUEL_EFFICIENT is unsupported in the origin country (IL) and returns HTTP 400.
            // Kept behind an explicit, default-OFF flag; production never enables it.
            requestedReferenceRoutes = if (options.requestFuelEfficient) {
                listOf("FUEL_EFFICIENT")
            } else {
                emptyList()
            },
        )
    }

    fun isoUtc(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun RouteWaypoint.toDto() = WaypointDto(
        address = address,
        placeId = placeId,
        location = if (latitude != null && longitude != null) {
            LocationDto(LatLngDto(latitude, longitude))
        } else {
            null
        },
    )
}

/** Routes API `EmissionType` values line up with our [FuelType] names. */
fun FuelType.toEmissionType(): String = name
