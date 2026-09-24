package com.fuelroute.domain.nav

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaypointPlannerTest {

    private val planner = WaypointPlanner(stepM = 50.0)

    private fun p(lat: Double, lng: Double) = GeoPoint(lat, lng)

    /** Polyline through [vertices], densified so it looks like a real (dense) route polyline. */
    private fun line(vararg vertices: GeoPoint): List<GeoPoint> =
        RouteGeometry.resample(vertices.toList(), 25.0)

    private val start = p(32.00, 34.80)
    private val end = p(32.00, 34.90)
    private val straight = line(start, end)

    /** What Google would return when forced through [waypoints]: start -> waypoints -> end. */
    private val viaFollowsWaypoints: suspend (List<GeoPoint>) -> List<GeoPoint>? = { wps ->
        line(start, *wps.toTypedArray(), end)
    }

    @Test
    fun `route equal to the default needs no waypoints`() = runTest {
        val plan = planner.plan(chosen = line(start, end), default = straight) { error("must not be asked") }
        assertTrue(plan.waypoints.isEmpty())
        assertTrue(plan.exact)
    }

    @Test
    fun `one detour needs exactly one waypoint`() = runTest {
        val chosen = line(start, p(32.012, 34.85), end)
        val plan = planner.plan(chosen, straight, viaFollowsWaypoints)
        assertEquals(1, plan.waypoints.size)
        assertTrue(plan.exact)
        // The waypoint sits on the chosen route, near the apex of the detour.
        assertTrue(RouteGeometry.distanceToPolylineM(plan.waypoints[0], chosen) < 5.0)
        assertTrue(plan.waypoints[0].lat > 32.010)
    }

    @Test
    fun `two separate detours need two waypoints in route order`() = runTest {
        val chosen = line(start, p(32.012, 34.83), p(31.992, 34.87), end)
        val plan = planner.plan(chosen, straight, viaFollowsWaypoints)
        assertEquals(2, plan.waypoints.size)
        assertTrue(plan.exact)
        assertTrue(plan.waypoints[0].lng < plan.waypoints[1].lng)
    }

    @Test
    fun `a routing service that ignores waypoints yields an inexact plan capped at the maximum`() = runTest {
        val chosen = line(start, p(32.012, 34.85), end)
        val plan = planner.plan(chosen, straight) { straight }
        assertFalse(plan.exact)
        assertTrue(plan.waypoints.size in 1..3)
    }

    @Test
    fun `failure to fetch the verification route degrades to an inexact plan`() = runTest {
        val chosen = line(start, p(32.012, 34.85), end)
        val plan = planner.plan(chosen, straight) { null }
        assertFalse(plan.exact)
        assertEquals(1, plan.waypoints.size)
    }

    @Test
    fun `tiny deviations do not create waypoints`() = runTest {
        // 40 m off the default: within tolerance, so it counts as the same route.
        val chosen = line(p(32.0004, 34.80), p(32.0004, 34.90))
        val plan = planner.plan(chosen, straight) { error("must not be asked") }
        assertTrue(plan.waypoints.isEmpty())
    }

    @Test
    fun `geometry helpers`() {
        assertEquals(0.0, RouteGeometry.distanceToPolylineM(p(32.0, 34.85), straight), 1.0)
        // 0.001 deg of latitude is about 111 m
        assertEquals(111.0, RouteGeometry.distanceToPolylineM(p(32.001, 34.85), straight), 3.0)
        assertEquals(1.0, RouteGeometry.coverage(straight, straight, 10.0), 1e-9)
        assertEquals(0.0, RouteGeometry.coverage(straight, line(p(32.1, 34.8), p(32.1, 34.9)), 10.0), 1e-9)
    }
}
