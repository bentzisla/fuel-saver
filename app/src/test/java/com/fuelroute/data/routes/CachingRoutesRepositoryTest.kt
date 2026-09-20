package com.fuelroute.data.routes

import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteSegment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CachingRoutesRepositoryTest {

    private val delegate = mockk<GoogleRoutesRepository>()
    private val repository = CachingRoutesRepository(delegate)

    private val origin = RouteWaypoint(placeId = "origin")
    private val destination = RouteWaypoint(placeId = "destination")

    @Test
    fun `second call within the TTL does not hit the service`() = runTest {
        coEvery { delegate.getAlternatives(any(), any(), any(), any()) } returns listOf(route("route-0"))

        val first = repository.getAlternatives(origin, destination)
        val second = repository.getAlternatives(origin, destination)

        assertEquals(listOf(route("route-0")), first)
        assertEquals(first, second)
        coVerify(exactly = 1) { delegate.getAlternatives(any(), any(), any(), any()) }
    }

    @Test
    fun `forceRefresh bypasses the cache`() = runTest {
        coEvery { delegate.getAlternatives(any(), any(), any(), any()) } returns listOf(route("route-0"))

        repository.getAlternatives(origin, destination)
        repository.getAlternatives(origin, destination, forceRefresh = true)

        coVerify(exactly = 2) { delegate.getAlternatives(any(), any(), any(), any()) }
    }

    @Test
    fun `different destinations are cached separately`() = runTest {
        coEvery { delegate.getAlternatives(any(), any(), any(), any()) } returns listOf(route("route-0"))

        repository.getAlternatives(origin, destination)
        repository.getAlternatives(origin, RouteWaypoint(placeId = "other"))

        coVerify(exactly = 2) { delegate.getAlternatives(any(), any(), any(), any()) }
    }

    private fun route(id: String) = Route(
        id = id,
        distanceMeters = 1_000.0,
        staticDurationSeconds = 100.0,
        durationSeconds = 100.0,
        segments = listOf(RouteSegment(distanceMeters = 1_000.0, staticDurationSeconds = 100.0)),
    )
}
