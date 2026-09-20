package com.fuelroute.data.routes

import com.fuelroute.domain.model.CongestionLevel
import com.fuelroute.domain.model.TrafficResolution
import com.fuelroute.testutil.Fixtures
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses the recorded `computeRoutes` responses under `fixtures/routes/` with the
 * same tolerant JSON config the app uses, then maps them to the domain model. This
 * guards the DTO shape against future API drift.
 */
class RoutesFixtureTest {

    private fun load(name: String): ComputeRoutesResponse =
        Fixtures.json.decodeFromString(Fixtures.read("fixtures/routes/$name"))

    @Test
    fun `three alternatives map with the expected traffic resolution`() {
        val routes = RoutesMapper.toDomain(load("3-alternatives.json"))

        assertEquals(3, routes.size)
        assertEquals(listOf("route-0", "route-1", "route-2"), routes.map { it.id })

        val default = routes[0]
        assertEquals(TrafficResolution.PER_SEGMENT, default.trafficResolution)
        assertEquals(51955.0, default.distanceMeters, 1e-6)
        assertEquals(4200.0, default.durationSeconds, 1e-6)
        assertEquals(
            listOf(CongestionLevel.SLOW, CongestionLevel.TRAFFIC_JAM, CongestionLevel.NORMAL),
            default.segments.map { it.congestion },
        )
        assertTrue(default.tollUnknown)
        assertNull(default.tollCost)

        val tollRoad = routes[1]
        assertEquals(TrafficResolution.ROUTE_AVERAGE, tollRoad.trafficResolution)
        assertEquals(
            listOf(
                CongestionLevel.NORMAL,
                CongestionLevel.SLOW,
                CongestionLevel.TRAFFIC_JAM,
                CongestionLevel.NORMAL,
            ),
            tollRoad.segments.map { it.congestion },
        )

        val untracked = routes[2]
        assertEquals(TrafficResolution.NONE, untracked.trafficResolution)
        assertEquals(2, untracked.segments.size)
        assertTrue(untracked.segments.all { it.congestionFactor == 1.0 })
    }

    @Test
    fun `toll fixture exposes the estimated price`() {
        val route = RoutesMapper.toDomain(load("with-tolls.json")).single()

        assertFalse(route.tollUnknown)
        assertEquals(12.5, route.tollCost!!, 1e-9)
        assertEquals(TrafficResolution.NONE, route.trafficResolution)
    }

    @Test
    fun `single route fixture still maps`() {
        val route = RoutesMapper.toDomain(load("single-route.json")).single()

        assertEquals(10000.0, route.distanceMeters, 1e-6)
        assertEquals(2, route.segments.size)
        assertTrue(route.tollUnknown)
        assertNull(route.tollCost)
    }

    @Test
    fun `route level only fixture becomes a route average`() {
        val route = RoutesMapper.toDomain(load("route-level-only.json")).single()

        assertEquals(TrafficResolution.ROUTE_AVERAGE, route.trafficResolution)
        assertEquals(
            listOf(CongestionLevel.SLOW, CongestionLevel.TRAFFIC_JAM, CongestionLevel.NORMAL),
            route.segments.map { it.congestion },
        )
    }

    @Test
    fun `fixture survives a map encode decode round trip`() {
        val original = load("3-alternatives.json")
        val reencoded = Fixtures.json.encodeToString(ComputeRoutesResponse.serializer(), original)
        val reparsed = Fixtures.json.decodeFromString<ComputeRoutesResponse>(reencoded)

        assertEquals(original, reparsed)
    }
}