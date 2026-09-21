package com.fuelroute.data.routes

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputeRoutesRequestTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `default request encodes alternatives and traffic but omits unsupported fields`() {
        val request = ComputeRoutesRequest(
            origin = WaypointDto(placeId = "origin"),
            destination = WaypointDto(placeId = "destination"),
        )

        val encoded = json.encodeToString(ComputeRoutesRequest.serializer(), request)

        assertTrue(encoded.contains("\"computeAlternativeRoutes\":true"))
        assertTrue(encoded.contains("\"extraComputations\":[\"TRAFFIC_ON_POLYLINE\",\"TOLLS\"]"))
        // These must NOT be sent: FUEL_EFFICIENT reference route is unsupported in IL (400),
        // and a past departureTime is rejected for DRIVE.
        assertFalse(encoded.contains("requestedReferenceRoutes"))
        assertFalse(encoded.contains("departureTime"))
    }

    @Test
    fun `field mask requests full toll info at route and leg level`() {
        // The API only returns tollInfo when the parent message is masked (a leaf such as
        // `...tollInfo.estimatedPrice` alone is not enough), so guard the parent paths.
        assertTrue(ROUTES_FIELD_MASK.contains("routes.travelAdvisory.tollInfo"))
        assertTrue(ROUTES_FIELD_MASK.contains("routes.legs.travelAdvisory.tollInfo"))
    }

    @Test
    fun `serializes departure time when provided`() {
        val request = ComputeRoutesRequest(
            origin = WaypointDto(placeId = "origin"),
            destination = WaypointDto(placeId = "destination"),
            departureTime = "2026-09-19T12:00:00Z",
        )

        val encoded = json.encodeToString(ComputeRoutesRequest.serializer(), request)
        assertTrue(encoded.contains("\"departureTime\":\"2026-09-19T12:00:00Z\""))
    }

    @Test
    fun `round trips departure time`() {
        val request = ComputeRoutesRequest(
            origin = WaypointDto(placeId = "origin"),
            destination = WaypointDto(placeId = "destination"),
            departureTime = "2026-09-19T12:00:00Z",
        )

        val decoded = json.decodeFromString(
            ComputeRoutesRequest.serializer(),
            json.encodeToString(ComputeRoutesRequest.serializer(), request),
        )

        assertEquals("2026-09-19T12:00:00Z", decoded.departureTime)
    }
}