package com.fuelroute.data.history

import com.fuelroute.data.db.RouteSearchDao
import com.fuelroute.data.db.RouteSearchEntity
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.TripEntity
import com.fuelroute.data.db.TripSource
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card 47: history is scoped to the active vehicle. Trips (linked and orphan) come from the
 * vehicle-scoped DAO query, while route searches stay global.
 */
class DriveHistoryRepositoryTest {

    private val routeSearchDao = mockk<RouteSearchDao>()
    private val tripDao = mockk<TripDao>()
    private val tripLinker = mockk<TripLinker>(relaxed = true)
    private val repository = DriveHistoryRepository(routeSearchDao, tripDao, tripLinker)

    @Test
    fun `recent joins the vehicle's trips to global searches`() = runTest {
        coEvery { routeSearchDao.recent(any()) } returns listOf(search(id = 1, ts = 1_000))
        coEvery { tripDao.recentClosedForVehicle("v1", any()) } returns listOf(
            trip(id = 10, vehicleId = "v1", routeSearchId = 1, ts = 1_100, actualCost = 12.0, fuel = 1.5),
        )

        val history = repository.recent("v1")

        val entry = history.entries.single()
        assertEquals(1L, entry.searchId)
        assertEquals(10L, entry.tripId)
        assertEquals(12.0, entry.actualCost!!, 1e-9)
        assertEquals(1.5, entry.actualLiters!!, 1e-9)
        // The per-vehicle trip query is used; the global one is not.
        coVerify(exactly = 1) { tripDao.recentClosedForVehicle("v1", any()) }
        coVerify(exactly = 0) { tripDao.recentClosed(any()) }
    }

    @Test
    fun `recent surfaces orphan trips for the vehicle with no search side`() = runTest {
        coEvery { routeSearchDao.recent(any()) } returns emptyList()
        coEvery { tripDao.recentClosedForVehicle("v2", any()) } returns listOf(
            trip(id = 7, vehicleId = "v2", routeSearchId = null, ts = 2_000, actualCost = 3.0, fuel = 0.4),
        )

        val entry = repository.recent("v2").entries.single()

        assertNull(entry.searchId)
        assertEquals(7L, entry.tripId)
        assertTrue(entry.canLinkManually)
    }

    @Test
    fun `demo trips keep the demo marker after the vehicle scoping change`() = runTest {
        coEvery { routeSearchDao.recent(any()) } returns emptyList()
        coEvery { tripDao.recentClosedForVehicle("v1", any()) } returns listOf(
            trip(id = 3, vehicleId = "v1", routeSearchId = null, ts = 5, source = TripSource.DEMO),
        )

        assertTrue(repository.recent("v1").entries.single().isDemo)
    }

    @Test
    fun `linkCandidates excludes searches already linked to this vehicle's trips`() = runTest {
        coEvery { tripDao.recentClosedForVehicle("v1", any()) } returns listOf(
            trip(id = 1, vehicleId = "v1", routeSearchId = 100, ts = 1),
        )
        coEvery { routeSearchDao.recent(any()) } returns listOf(
            search(id = 100, ts = 2_000),
            search(id = 200, ts = 1_000),
        )

        val candidates = repository.linkCandidates("v1")

        assertEquals(listOf(200L), candidates.map { it.id })
        // The linked-set scan is scoped to the active vehicle.
        coVerify(exactly = 1) { tripDao.recentClosedForVehicle("v1", any()) }
        coVerify(exactly = 0) { tripDao.recentClosed(any()) }
    }

    @Test
    fun `delete of a linked ride removes the trip and leaves the search as undriven`() = runTest {
        val entry = historyEntry(searchId = 1, tripId = 10)
        coJustRun { tripDao.deleteById(any()) }

        repository.delete(entry)

        coVerify(exactly = 1) { tripDao.deleteById(10) }
        coVerify(exactly = 0) { tripDao.unlinkTripsForSearch(any()) }
        coVerify(exactly = 0) { routeSearchDao.deleteById(any()) }
    }

    @Test
    fun `delete of an orphan drive removes only the trip`() = runTest {
        val entry = historyEntry(searchId = null, tripId = 7)
        coJustRun { tripDao.deleteById(any()) }

        repository.delete(entry)

        coVerify(exactly = 1) { tripDao.deleteById(7) }
        coVerify(exactly = 0) { routeSearchDao.deleteById(any()) }
    }

    @Test
    fun `delete of an undriven search unlinks its trip before deleting the search`() = runTest {
        val entry = historyEntry(searchId = 5, tripId = null)
        coJustRun { tripDao.unlinkTripsForSearch(any()) }
        coJustRun { routeSearchDao.deleteById(any()) }

        repository.delete(entry)

        coVerify(exactly = 1) { tripDao.unlinkTripsForSearch(5) }
        coVerify(exactly = 1) { routeSearchDao.deleteById(5) }
        coVerify(exactly = 0) { tripDao.deleteById(any()) }
    }

    @Test
    fun `delete of an entry with neither id is a no-op`() = runTest {
        repository.delete(historyEntry(searchId = null, tripId = null))

        coVerify(exactly = 0) { tripDao.deleteById(any()) }
        coVerify(exactly = 0) { tripDao.unlinkTripsForSearch(any()) }
        coVerify(exactly = 0) { routeSearchDao.deleteById(any()) }
    }

    private fun historyEntry(searchId: Long?, tripId: Long?) = DriveHistoryEntry(
        searchId = searchId,
        tripId = tripId,
        originLabel = "Home",
        destinationLabel = "Work",
        timestampMs = 1_000,
        predictedCost = 10.0,
        predictedLiters = 1.5,
        predictedMinutes = 20.0,
        distanceKm = 12.0,
        actualCost = 12.0,
        actualLiters = 1.6,
        actualMinutes = 22.0,
        pricePerLiterAtSearch = 7.0,
        pricePerLiterAtTrip = 7.1,
        savedAmount = 1.0,
    )

    private fun search(id: Long, ts: Long) = RouteSearchEntity(
        id = id,
        originLabel = "Home",
        destinationLabel = "Work",
        timestampMs = ts,
        cheapestCost = 10.0,
        fastestCost = 12.0,
        savedAmount = 2.0,
        predictedLiters = 1.5,
        distanceKm = 15.0,
        durationMin = 22.0,
    )

    private fun trip(
        id: Long,
        vehicleId: String,
        routeSearchId: Int?,
        ts: Long,
        actualCost: Double = 0.0,
        fuel: Double = 0.0,
        source: String = TripSource.REAL,
    ) = TripEntity(
        id = id,
        vehicleId = vehicleId,
        startedAtMs = ts,
        endedAtMs = ts + 600_000,
        distanceKm = 12.0,
        fuelL = fuel,
        avgSpeedKmh = 45.0,
        maxSpeedKmh = 90.0,
        idleSeconds = 5.0,
        routeSearchId = routeSearchId,
        actualCost = actualCost,
        source = source,
    )
}
