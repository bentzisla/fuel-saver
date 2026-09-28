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
import io.mockk.slot
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
    fun `savings are measured for the selected route, not the top-ranked one`() = runTest {
        // Top-ranked 10.0, fastest 12.0; the user picked the fastest route.
        coEvery { routeSearchDao.recent(any()) } returns listOf(
            search(id = 1, ts = 1_000).copy(selectedRouteIndex = 1, selectedPredictedCost = 12.0),
        )
        coEvery { tripDao.recentClosedForVehicle("v1", any()) } returns emptyList()

        assertEquals(0.0, repository.recent("v1").entries.single().savedAmount, 1e-9)
    }

    @Test
    fun `savings for the top-ranked route are fastest minus cheapest`() = runTest {
        coEvery { routeSearchDao.recent(any()) } returns listOf(search(id = 1, ts = 1_000))
        coEvery { tripDao.recentClosedForVehicle("v1", any()) } returns emptyList()

        assertEquals(2.0, repository.recent("v1").entries.single().savedAmount, 1e-9)
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
            // Another ride (an earlier search for the same one would be a hidden duplicate).
            search(id = 200, ts = 1_000).copy(destinationLabel = "Gym"),
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

    @Test
    fun `recent lets a manual entry win over the OBD measurement`() = runTest {
        coEvery { routeSearchDao.recent(any()) } returns emptyList()
        coEvery { tripDao.recentClosedForVehicle("v1", any()) } returns listOf(
            trip(id = 10, vehicleId = "v1", routeSearchId = null, ts = 1_000, actualCost = 5.0, fuel = 0.7)
                .copy(
                    distanceKm = 10.0,
                    pricePerLiterAtTrip = 7.0,
                    manualCost = 42.0,
                    manualDistanceKm = 20.0,
                    manualLitersPer100Km = 6.0,
                ),
        )

        val entry = repository.recent("v1").entries.single()

        assertTrue(entry.hasManualEntry)
        assertEquals(42.0, entry.actualCost!!, 1e-9)
        assertEquals(20.0, entry.distanceKm!!, 1e-9)
        // 20 km * 6 L/100km = 1.2 L, not the OBD 0.7 L.
        assertEquals(1.2, entry.actualLiters!!, 1e-9)
    }

    @Test
    fun `setManualCost forwards the values to the DAO`() = runTest {
        coJustRun { tripDao.updateManualCost(any(), any(), any(), any(), any()) }

        repository.setManualCost(
            tripId = 10,
            cost = 42.0,
            distanceKm = 20.0,
            litersPer100Km = 6.0,
            enteredAtMs = 999,
        )

        coVerify(exactly = 1) { tripDao.updateManualCost(10, 42.0, 20.0, 6.0, 999) }
    }

    @Test
    fun `recordManualCost updates the existing trip when one already exists`() = runTest {
        coJustRun { tripDao.updateManualCost(any(), any(), any(), any(), any()) }

        val tripId = repository.recordManualCost(
            vehicleId = "v1",
            entry = historyEntry(searchId = 1, tripId = 10),
            cost = 42.0,
            enteredAtMs = 999,
        )

        assertEquals(10L, tripId)
        coVerify(exactly = 1) { tripDao.updateManualCost(10, 42.0, null, null, 999) }
        coVerify(exactly = 0) { tripDao.insert(any()) }
    }

    @Test
    fun `recordManualCost creates a manual trip linked to the search when the ride was never driven`() = runTest {
        coEvery { routeSearchDao.findById(1) } returns search(id = 1, ts = 1_000).copy(
            departureTimeMs = 100_000,
            durationMin = 30.0,
            distanceKm = 40.0,
            pricePerLiterAtSearch = 7.0,
        )
        val inserted = slot<TripEntity>()
        coEvery { tripDao.insert(capture(inserted)) } returns 55L

        val tripId = repository.recordManualCost(
            vehicleId = "v1",
            entry = historyEntry(searchId = 1, tripId = null),
            cost = 42.0,
            enteredAtMs = 999,
        )

        assertEquals(55L, tripId)
        val trip = inserted.captured
        assertEquals("v1", trip.vehicleId)
        assertEquals(1, trip.routeSearchId)
        assertEquals(100_000L, trip.startedAtMs)
        assertEquals(100_000L + 30 * 60_000L, trip.endedAtMs)
        assertEquals(40.0, trip.distanceKm, 1e-9)
        assertEquals(7.0, trip.pricePerLiterAtTrip, 1e-9)
        assertEquals(TripSource.MANUAL, trip.source)
        assertEquals(42.0, trip.manualCost!!, 1e-9)
        assertEquals(0, trip.isOpen)
    }

    @Test
    fun `recordManualCost falls back to the live price when the search never recorded one`() = runTest {
        coEvery { routeSearchDao.findById(1) } returns search(id = 1, ts = 1_000).copy(
            pricePerLiterAtSearch = 0.0,
        )
        val inserted = slot<TripEntity>()
        coEvery { tripDao.insert(capture(inserted)) } returns 55L

        repository.recordManualCost(
            vehicleId = "v1",
            entry = historyEntry(searchId = 1, tripId = null),
            cost = 42.0,
            fallbackPricePerLiter = 6.5,
        )

        assertEquals(6.5, inserted.captured.pricePerLiterAtTrip, 1e-9)
    }

    @Test
    fun `a manual trip already owns its search so linkCandidates never offers it again`() = runTest {
        coEvery { tripDao.recentClosedForVehicle("v1", any()) } returns listOf(
            trip(id = 55, vehicleId = "v1", routeSearchId = 1, ts = 100_000, source = TripSource.MANUAL),
        )
        coEvery { routeSearchDao.recent(any()) } returns listOf(search(id = 1, ts = 100_000))

        assertTrue(repository.linkCandidates("v1").isEmpty())
    }

    @Test
    fun `deleteMany removes trips and unlinks undriven searches`() = runTest {
        coJustRun { tripDao.deleteByIds(any()) }
        coJustRun { tripDao.unlinkTripsForSearch(any()) }
        coJustRun { routeSearchDao.deleteById(any()) }

        repository.deleteMany(
            listOf(
                historyEntry(searchId = 1, tripId = 10),
                historyEntry(searchId = null, tripId = 7),
                historyEntry(searchId = 5, tripId = null),
            ),
        )

        coVerify(exactly = 1) { tripDao.deleteByIds(listOf(10L, 7L)) }
        coVerify(exactly = 1) { tripDao.unlinkTripsForSearch(5) }
        coVerify(exactly = 1) { routeSearchDao.deleteById(5) }
    }

    @Test
    fun `mergeTrips sums the totals and replaces the originals`() = runTest {
        coEvery { tripDao.findById(1) } returns trip(
            id = 1,
            vehicleId = "v1",
            routeSearchId = null,
            ts = 1_000,
            actualCost = 7.0,
            fuel = 1.0,
        ).copy(distanceKm = 10.0, idleSeconds = 10.0, maxSpeedKmh = 60.0, pricePerLiterAtTrip = 7.0)
        coEvery { tripDao.findById(2) } returns trip(
            id = 2,
            vehicleId = "v1",
            routeSearchId = null,
            ts = 3_000,
            actualCost = 14.0,
            fuel = 2.0,
        ).copy(distanceKm = 20.0, idleSeconds = 20.0, maxSpeedKmh = 90.0, pricePerLiterAtTrip = 7.0)
        val inserted = slot<List<TripEntity>>()
        coEvery { tripDao.replaceTrips(any(), capture(inserted)) } returns listOf(99L)

        val newId = repository.mergeTrips(listOf(1, 2))

        assertEquals(99L, newId)
        val merged = inserted.captured.single()
        assertEquals(1_000L, merged.startedAtMs)
        assertEquals(603_000L, merged.endedAtMs)
        assertEquals(30.0, merged.distanceKm, 1e-9)
        assertEquals(3.0, merged.fuelL, 1e-9)
        assertEquals(30.0, merged.idleSeconds, 1e-9)
        // 3 L * 7 ₪/L.
        assertEquals(21.0, merged.actualCost, 1e-9)
        assertEquals(0, merged.isOpen)
        assertEquals(90.0, merged.maxSpeedKmh, 1e-9)
        coVerify(exactly = 1) { tripDao.replaceTrips(listOf(1L, 2L), any()) }
    }

    @Test
    fun `mergeTrips rejects trips of different vehicles`() = runTest {
        coEvery { tripDao.findById(1) } returns trip(1, "v1", null, 1_000)
        coEvery { tripDao.findById(2) } returns trip(2, "v2", null, 2_000)

        assertNull(repository.mergeTrips(listOf(1, 2)))
        coVerify(exactly = 0) { tripDao.replaceTrips(any(), any()) }
    }

    @Test
    fun `recent exposes the trip window separately from the display timestamp`() = runTest {
        // A linked ride: the search happened earlier than the drive itself.
        coEvery { routeSearchDao.recent(any()) } returns listOf(search(id = 1, ts = 1_000))
        coEvery { tripDao.recentClosedForVehicle("v1", any()) } returns listOf(
            trip(id = 10, vehicleId = "v1", routeSearchId = 1, ts = 50_000)
                .copy(endedAtMs = 650_000),
        )

        val entry = repository.recent("v1").entries.single()

        // The display/sort key stays the search time...
        assertEquals(1_000L, entry.timestampMs)
        // ...while the merge/split math gets the real drive window.
        assertEquals(50_000L, entry.tripStartedAtMs)
        assertEquals(650_000L, entry.tripEndedAtMs)
    }

    @Test
    fun `recent gives an undriven search no trip window`() = runTest {
        coEvery { routeSearchDao.recent(any()) } returns listOf(search(id = 1, ts = 1_000))
        coEvery { tripDao.recentClosedForVehicle("v1", any()) } returns emptyList()

        val entry = repository.recent("v1").entries.single()

        assertNull(entry.tripStartedAtMs)
        assertNull(entry.tripEndedAtMs)
    }

    @Test
    fun `mergeTrips merges two demo trips and keeps the demo source`() = runTest {
        coEvery { tripDao.findById(1) } returns trip(
            id = 1,
            vehicleId = "v1",
            routeSearchId = null,
            ts = 1_000,
            fuel = 1.0,
            source = TripSource.DEMO,
        ).copy(distanceKm = 10.0)
        coEvery { tripDao.findById(2) } returns trip(
            id = 2,
            vehicleId = "v1",
            routeSearchId = null,
            ts = 3_000,
            fuel = 2.0,
            source = TripSource.DEMO,
        ).copy(distanceKm = 20.0)
        val inserted = slot<List<TripEntity>>()
        coEvery { tripDao.replaceTrips(any(), capture(inserted)) } returns listOf(99L)

        assertEquals(99L, repository.mergeTrips(listOf(1, 2)))
        assertEquals(TripSource.DEMO, inserted.captured.single().source)
    }

    @Test
    fun `mergeTrips rejects a mixed real and demo set`() = runTest {
        coEvery { tripDao.findById(1) } returns trip(1, "v1", null, 1_000, source = TripSource.REAL)
        coEvery { tripDao.findById(2) } returns trip(2, "v1", null, 2_000, source = TripSource.DEMO)

        assertNull(repository.mergeTrips(listOf(1, 2)))
        coVerify(exactly = 0) { tripDao.replaceTrips(any(), any()) }
    }

    @Test
    fun `splitTrip apportions the original and replaces it with two parts`() = runTest {
        coEvery { tripDao.findById(5) } returns trip(
            id = 5,
            vehicleId = "v1",
            routeSearchId = 3,
            ts = 0,
            actualCost = 14.0,
            fuel = 2.0,
        ).copy(
            endedAtMs = 10_000,
            distanceKm = 20.0,
            idleSeconds = 100.0,
            pricePerLiterAtTrip = 7.0,
        )
        val inserted = slot<List<TripEntity>>()
        coEvery { tripDao.replaceTrips(listOf(5L), capture(inserted)) } returns listOf(101L, 102L)

        val (firstId, secondId) = repository.splitTrip(5, 3_000)!!

        assertEquals(101L, firstId)
        assertEquals(102L, secondId)
        val parts = inserted.captured
        assertEquals(0L, parts[0].startedAtMs)
        assertEquals(3_000L, parts[0].endedAtMs)
        assertEquals(6.0, parts[0].distanceKm, 1e-9)
        assertEquals(0.6 * 7.0, parts[0].actualCost, 1e-9)
        assertEquals(3, parts[0].routeSearchId)
        assertEquals(3_000L, parts[1].startedAtMs)
        assertEquals(10_000L, parts[1].endedAtMs)
        assertEquals(14.0, parts[1].distanceKm, 1e-9)
        assertNull(parts[1].routeSearchId)
        coVerify(exactly = 1) { tripDao.replaceTrips(listOf(5L), any()) }
    }

    @Test
    fun `splitTrip rejects a split point outside the drive`() = runTest {
        coEvery { tripDao.findById(5) } returns trip(5, "v1", null, 0)

        assertNull(repository.splitTrip(5, 0))
        coVerify(exactly = 0) { tripDao.replaceTrips(any(), any()) }
    }

    @Test
    fun `splitTrip rejects a split anchored on a linked ride's search time`() = runTest {
        // The search was an hour before the drive; the drive itself ran 30 minutes.
        coEvery { tripDao.findById(10) } returns trip(10, "v1", routeSearchId = 1, ts = 3_600_000)
            .copy(endedAtMs = 5_400_000)

        // Old UI split = search time 0 + 0.95 * 30min, which lands before the drive started.
        val buggySplitAtMs = (1_800_000L * 0.95f).toLong()
        assertNull(repository.splitTrip(10, buggySplitAtMs))
        coVerify(exactly = 0) { tripDao.replaceTrips(any(), any()) }
    }

    @Test
    fun `mergeTrips keeps the search link of the earliest linked part`() = runTest {
        // One drive split in two by a stop: the first leg was auto-linked to the search.
        coEvery { tripDao.findById(1) } returns trip(1, "v1", routeSearchId = 7, ts = 1_000, fuel = 1.0)
            .copy(linkedAtMs = 5_000L)
        coEvery { tripDao.findById(2) } returns trip(2, "v1", routeSearchId = null, ts = 700_000, fuel = 1.0)
        val inserted = slot<List<TripEntity>>()
        coEvery { tripDao.replaceTrips(any(), capture(inserted)) } returns listOf(99L)

        repository.mergeTrips(listOf(2, 1))

        val merged = inserted.captured.single()
        assertEquals(7, merged.routeSearchId)
        assertEquals(5_000L, merged.linkedAtMs)
    }

    @Test
    fun `mergeTrips keeps a manual cost entered on one of the parts`() = runTest {
        coEvery { tripDao.findById(1) } returns trip(1, "v1", null, ts = 1_000, actualCost = 7.0, fuel = 1.0)
            .copy(pricePerLiterAtTrip = 7.0)
        // The second leg's OBD cost was corrected by hand to 20.
        coEvery { tripDao.findById(2) } returns trip(2, "v1", null, ts = 700_000, actualCost = 14.0, fuel = 2.0)
            .copy(pricePerLiterAtTrip = 7.0, manualCost = 20.0, manualEnteredAtMs = 9_000L)
        val inserted = slot<List<TripEntity>>()
        coEvery { tripDao.replaceTrips(any(), capture(inserted)) } returns listOf(99L)

        repository.mergeTrips(listOf(1, 2))

        val merged = inserted.captured.single()
        // What History showed before the merge: 7 (OBD) + 20 (manual).
        assertEquals(27.0, merged.manualCost!!, 1e-9)
        assertEquals(9_000L, merged.manualEnteredAtMs)
        // The measured columns stay OBD-only.
        assertEquals(3.0, merged.fuelL, 1e-9)
        assertEquals(21.0, merged.actualCost, 1e-9)
    }

    @Test
    fun `mergeTrips never turns a typed-in manual ride into measured OBD numbers`() = runTest {
        coEvery { tripDao.findById(1) } returns trip(1, "v1", null, ts = 1_000, actualCost = 7.0, fuel = 1.0)
            .copy(distanceKm = 10.0, pricePerLiterAtTrip = 7.0)
        // A manual-only ride: its distance is the search's prediction, its fuel was never measured.
        coEvery { tripDao.findById(2) } returns trip(2, "v1", routeSearchId = 4, ts = 700_000, source = TripSource.MANUAL)
            .copy(distanceKm = 30.0, pricePerLiterAtTrip = 7.0, manualCost = 21.0)
        val inserted = slot<List<TripEntity>>()
        coEvery { tripDao.replaceTrips(any(), capture(inserted)) } returns listOf(99L)

        repository.mergeTrips(listOf(1, 2))

        val merged = inserted.captured.single()
        assertEquals(TripSource.REAL, merged.source)
        assertEquals(10.0, merged.distanceKm, 1e-9)
        assertEquals(1.0, merged.fuelL, 1e-9)
        assertEquals(40.0, merged.manualDistanceKm!!, 1e-9)
        assertEquals(28.0, merged.manualCost!!, 1e-9)
        assertEquals(4, merged.routeSearchId)
    }

    @Test
    fun `splitTrip apportions a manual entry instead of dropping it`() = runTest {
        coEvery { tripDao.findById(5) } returns trip(5, "v1", routeSearchId = 3, ts = 0, actualCost = 14.0, fuel = 2.0)
            .copy(
                endedAtMs = 10_000,
                distanceKm = 20.0,
                pricePerLiterAtTrip = 7.0,
                manualCost = 30.0,
                manualDistanceKm = 25.0,
                manualLitersPer100Km = 8.0,
            )
        val inserted = slot<List<TripEntity>>()
        coEvery { tripDao.replaceTrips(listOf(5L), capture(inserted)) } returns listOf(101L, 102L)

        repository.splitTrip(5, 3_000)

        val (first, second) = inserted.captured
        assertEquals(9.0, first.manualCost!!, 1e-9)
        assertEquals(21.0, second.manualCost!!, 1e-9)
        assertEquals(7.5, first.manualDistanceKm!!, 1e-9)
        assertEquals(17.5, second.manualDistanceKm!!, 1e-9)
        assertEquals(8.0, first.manualLitersPer100Km!!, 1e-9)
        assertEquals(8.0, second.manualLitersPer100Km!!, 1e-9)
        // The measured columns are split from the OBD values, not the manual ones.
        assertEquals(6.0, first.distanceKm, 1e-9)
        assertEquals(0.6, first.fuelL, 1e-9)
    }

    @Test
    fun `recent hides an earlier refresh of a ride that was driven`() = runTest {
        // Field case: searched at 11:45, refreshed at 11:50, drove from 12:17 (linked to 11:50).
        val minute = 60_000L
        coEvery { routeSearchDao.recent(any()) } returns listOf(
            search(id = 6, ts = 50 * minute),
            search(id = 5, ts = 45 * minute),
        )
        coEvery { tripDao.recentClosedForVehicle("v1", any()) } returns listOf(
            trip(id = 12, vehicleId = "v1", routeSearchId = 6, ts = 77 * minute, actualCost = 5.0, fuel = 0.7),
        )

        val entries = repository.recent("v1").entries

        assertEquals(listOf(6L), entries.map { it.searchId })
    }

    @Test
    fun `linkCandidates skips hidden duplicate searches`() = runTest {
        val minute = 60_000L
        coEvery { tripDao.recentClosedForVehicle("v1", any()) } returns emptyList()
        coEvery { routeSearchDao.recent(any()) } returns listOf(
            search(id = 2, ts = 10 * minute),
            search(id = 1, ts = 0),
        )

        assertEquals(listOf(2L), repository.linkCandidates("v1").map { it.id })
    }

    @Test
    fun `a direct-cost manual entry resolves to liters for accuracy and consumption`() = runTest {
        coEvery { routeSearchDao.recent(any()) } returns emptyList()
        coEvery { tripDao.recentClosedForVehicle("v1", any()) } returns listOf(
            trip(id = 10, vehicleId = "v1", routeSearchId = null, ts = 0, source = TripSource.MANUAL)
                .copy(distanceKm = 20.0, pricePerLiterAtTrip = 7.0, manualCost = 14.0),
        )

        val entry = repository.recent("v1").entries.single()

        assertEquals(2.0, entry.actualLiters!!, 1e-9)
        assertEquals(10.0, entry.actualLitersPer100Km!!, 1e-9)
    }

    @Test
    fun `canEnterManualCost is true for an undriven search with no trip yet`() {
        assertTrue(historyEntry(searchId = 1, tripId = null).canEnterManualCost)
    }

    @Test
    fun `canEnterManualCost is true for a driven ride even when an OBD cost already exists`() {
        // historyEntry() always sets a positive actualCost; overriding it must still be offered.
        assertTrue(historyEntry(searchId = 1, tripId = 10).canEnterManualCost)
        assertTrue(historyEntry(searchId = null, tripId = 10).canEnterManualCost)
    }

    @Test
    fun `canEnterManualCost is false only when the entry has neither id`() {
        assertTrue(!historyEntry(searchId = null, tripId = null).canEnterManualCost)
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
