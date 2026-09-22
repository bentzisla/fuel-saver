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
}
