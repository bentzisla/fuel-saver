package com.fuelroute.data.routes

import com.fuelroute.data.db.RouteSearchDao
import com.fuelroute.data.db.RouteSearchEntity
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.TripEntity
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One History row per ride: [DefaultRouteSearchRepository.record] applies the dedupe policy. */
class RouteSearchRepositoryTest {

    private val dao = mockk<RouteSearchDao>()
    private val tripDao = mockk<TripDao>()
    private val repository = DefaultRouteSearchRepository(dao, tripDao)
    private val minute = 60_000L

    private fun search(atMin: Long, km: Double = 40.0) = RouteSearch(
        originLabel = "Home",
        destinationLabel = "Work",
        timestampMs = atMin * minute,
        cheapestCost = 30.0,
        savedAmount = 2.0,
        predictedLiters = 4.0,
        distanceKm = km,
        durationMin = 45.0,
    )

    private fun stored(id: Long, atMin: Long, km: Double = 40.0) = RouteSearchEntity(
        id = id,
        originLabel = "Home",
        destinationLabel = "Work",
        timestampMs = atMin * minute,
        cheapestCost = 31.0,
        fastestCost = 33.0,
        savedAmount = 2.0,
        predictedLiters = 4.1,
        distanceKm = km,
        durationMin = 44.0,
    )

    @Test
    fun `a refresh of the same ride overwrites the previous row and keeps its id`() = runTest {
        coEvery { dao.latest() } returns stored(id = 5, atMin = 0)
        coEvery { dao.isLinked(5) } returns false
        coEvery { tripDao.recentOpenTrips() } returns emptyList()
        val updated = slot<RouteSearchEntity>()
        coJustRun { dao.update(capture(updated)) }

        assertEquals(5L, repository.record(search(atMin = 5)))
        assertEquals(5L, updated.captured.id)
        assertEquals(5 * minute, updated.captured.timestampMs)
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `a refresh while the ride is being driven records nothing`() = runTest {
        coEvery { dao.latest() } returns stored(id = 5, atMin = 0)
        coEvery { dao.isLinked(5) } returns false
        coEvery { tripDao.recentOpenTrips() } returns listOf(openTrip(startMin = 10))

        assertNull(repository.record(search(atMin = 20)))
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun `a refresh after pulling away before the previous search records nothing`() = runTest {
        // Searched at minute 60 from the road; the drive had started at minute 45 (inside the
        // link window before that search), so the refresh at minute 70 must not replace it.
        coEvery { dao.latest() } returns stored(id = 5, atMin = 60)
        coEvery { dao.isLinked(5) } returns false
        coEvery { tripDao.recentOpenTrips() } returns listOf(openTrip(startMin = 45))

        assertNull(repository.record(search(atMin = 70)))
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun `an open trip that started long before the previous search does not block a refresh`() = runTest {
        coEvery { dao.latest() } returns stored(id = 5, atMin = 120)
        coEvery { dao.isLinked(5) } returns false
        coEvery { tripDao.recentOpenTrips() } returns listOf(openTrip(startMin = 45))
        coJustRun { dao.update(any()) }

        assertEquals(5L, repository.record(search(atMin = 125)))
        coVerify(exactly = 1) { dao.update(any()) }
    }

    @Test
    fun `a search after the previous ride was driven is inserted`() = runTest {
        coEvery { dao.latest() } returns stored(id = 5, atMin = 0)
        coEvery { dao.isLinked(5) } returns true
        coEvery { tripDao.recentOpenTrips() } returns emptyList()
        coEvery { dao.insert(any()) } returns 6L

        assertEquals(6L, repository.record(search(atMin = 30)))
    }

    private fun openTrip(startMin: Long) = TripEntity(
        vehicleId = "v1",
        startedAtMs = startMin * minute,
        endedAtMs = startMin * minute,
        distanceKm = 0.0,
        fuelL = 0.0,
        avgSpeedKmh = 0.0,
        maxSpeedKmh = 0.0,
        idleSeconds = 0.0,
        isOpen = 1,
    )
}
