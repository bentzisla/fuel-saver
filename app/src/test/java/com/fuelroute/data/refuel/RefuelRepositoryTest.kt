package com.fuelroute.data.refuel

import com.fuelroute.data.db.RefuelDao
import com.fuelroute.data.db.RefuelEntity
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.data.db.TripDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
}
