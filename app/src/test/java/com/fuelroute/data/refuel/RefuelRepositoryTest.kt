package com.fuelroute.data.refuel

import com.fuelroute.data.db.RefuelDao
import com.fuelroute.data.db.RefuelEntity
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.data.db.TripDao
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Card 47: the refuel list is scoped to the active vehicle. */
class RefuelRepositoryTest {

    private val refuelDao = mockk<RefuelDao>()
    private val speedBinDao = mockk<SpeedBinDao>(relaxed = true)
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val repository = DefaultRefuelRepository(refuelDao, speedBinDao, tripDao)

    @Test
    fun `recent queries only the active vehicle's refuels`() = runTest {
        coEvery { refuelDao.recentForVehicle("v1", 20) } returns listOf(
            RefuelEntity(
                id = 5,
                vehicleId = "v1",
                timestampMs = 1_000,
                liters = 30.0,
                totalPrice = 210.0,
                isFull = true,
            ),
        )

        val refuels = repository.recent("v1", 20)

        assertEquals(1, refuels.size)
        assertEquals("v1", refuels.single().vehicleId)
        assertEquals(30.0, refuels.single().liters, 1e-9)
        // The global query is no longer used by the list path.
        coVerify(exactly = 0) { refuelDao.recent(any()) }
    }

    @Test
    fun `add persists computed price per liter and the vehicle grade`() = runTest {
        val inserted = slot<RefuelEntity>()
        coEvery { refuelDao.insert(capture(inserted)) } just Runs

        repository.add(liters = 30.0, totalPrice = 210.0, isFull = true, vehicleId = "v1", grade = "98")

        val entity = inserted.captured
        assertEquals("v1", entity.vehicleId)
        assertEquals(30.0, entity.liters, 1e-9)
        assertEquals(210.0, entity.totalPrice, 1e-9)
        assertEquals(7.0, entity.pricePerLiter, 1e-9)
        assertEquals("98", entity.grade)
    }

    @Test
    fun `add never divides by zero liters`() = runTest {
        val inserted = slot<RefuelEntity>()
        coEvery { refuelDao.insert(capture(inserted)) } just Runs

        repository.add(liters = 0.0, totalPrice = 10.0, isFull = false, vehicleId = "v1", grade = "95")

        assertEquals(0.0, inserted.captured.pricePerLiter, 1e-9)
    }

    @Test
    fun `add records the obd correction active at the fill`() = runTest {
        val inserted = slot<RefuelEntity>()
        coEvery { refuelDao.insert(capture(inserted)) } just Runs

        repository.add(liters = 40.0, totalPrice = 280.0, isFull = true, vehicleId = "v1", grade = "95", obdCorrection = 1.2)

        assertEquals(1.2, inserted.captured.obdCorrectionAtFill!!, 1e-9)
    }

    @Test
    fun `the interval pumps every fill since the previous full one`() = runTest {
        val previous = RefuelEntity(id = 1, vehicleId = "v1", timestampMs = 1_000, liters = 45.0, totalPrice = 300.0, isFull = true)
        val latest = RefuelEntity(
            id = 3,
            vehicleId = "v1",
            timestampMs = 3_000,
            liters = 30.0,
            totalPrice = 210.0,
            isFull = true,
            obdCorrectionAtFill = 1.1,
        )
        coEvery { refuelDao.fullRefuelsSince("v1", 0L) } returns listOf(previous, latest)
        // A 15 L partial top-up at t=2000 plus the 30 L closing fill.
        coEvery { refuelDao.litersBetween("v1", 1_000, 3_000) } returns 45.0
        coEvery { tripDao.fuelBetween("v1", 1_000, 3_000) } returns 41.0

        val interval = repository.lastFullInterval("v1")!!

        assertEquals(45.0, interval.pumpedLitres, 1e-9)
        assertEquals(41.0, interval.obdLitres, 1e-9)
        assertEquals(1.1, interval.activeCorrection!!, 1e-9)
        assertEquals(1_000L, interval.fromTimestampMs)
        assertEquals(3_000L, interval.toTimestampMs)
    }

    @Test
    fun `no interval until two full fills exist`() = runTest {
        coEvery { refuelDao.fullRefuelsSince("v1", 0L) } returns listOf(
            RefuelEntity(id = 1, vehicleId = "v1", timestampMs = 1_000, liters = 45.0, totalPrice = 300.0, isFull = true),
        )
        assertEquals(null, repository.lastFullInterval("v1"))
    }
}
