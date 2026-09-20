package com.fuelroute.data.routes

import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteSegment
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RoutesErrorTest {

    @Test
    fun `maps 429 to quota`() {
        assertSame(RoutesError.Quota, RoutesError.from(httpException(429)))
    }

    @Test
    fun `maps 403 to forbidden`() {
        assertSame(RoutesError.Forbidden, RoutesError.from(httpException(403)))
    }

    @Test
    fun `maps io exceptions to no network`() {
        assertSame(RoutesError.NoNetwork, RoutesError.from(IOException("offline")))
    }

    @Test
    fun `maps parse exceptions to parse`() {
        val error = RoutesError.from(RoutesParseException("bad"))
        assertTrue(error is RoutesError.Parse)
    }

    @Test
    fun `already mapped errors pass through`() {
        assertSame(RoutesError.NoRoute, RoutesError.from(RoutesError.NoRoute))
    }

    @Test
    fun `empty routes map to no route`() {
        assertSame(RoutesError.NoRoute, RoutesError.fromRoutes(emptyList()))
    }

    @Test
    fun `single route maps to single route only`() {
        assertSame(RoutesError.SingleRouteOnly, RoutesError.fromRoutes(listOf(route("a"))))
    }

    @Test
    fun `multiple routes map to no error`() {
        assertNull(RoutesError.fromRoutes(listOf(route("a"), route("b"))))
    }

    private fun httpException(code: Int): HttpException {
        val body = "{}".toResponseBody("application/json".toMediaType())
        return HttpException(Response.error<Any>(code, body))
    }

    private fun route(id: String) = Route(
        id = id,
        distanceMeters = 1_000.0,
        staticDurationSeconds = 100.0,
        durationSeconds = 100.0,
        segments = listOf(RouteSegment(distanceMeters = 1_000.0, staticDurationSeconds = 100.0)),
    )
}
