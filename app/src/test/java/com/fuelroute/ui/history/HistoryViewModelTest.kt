package com.fuelroute.ui.history

import com.fuelroute.data.history.DriveHistory
import com.fuelroute.data.history.DriveHistoryEntry
import com.fuelroute.data.history.DriveHistoryRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.model.VehicleProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Card 47: History loads the active vehicle and reloads when it switches. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads the active vehicle and reloads on vehicle switch`() = runTest(dispatcher) {
        val repository = mockk<DriveHistoryRepository>()
        val vehicleRepository = mockk<VehicleRepository>()
        val vehicles = MutableStateFlow(listOf(vehicle("v1"), vehicle("v2")))

        every { vehicleRepository.vehicles() } returns vehicles
        coEvery { vehicleRepository.active() } returns vehicle("v1")
        coEvery { repository.recent("v1", any()) } returns DriveHistory(listOf(entry(1)), null)
        coEvery { repository.recent("v2", any()) } returns DriveHistory(listOf(entry(2)), null)

        val viewModel = HistoryViewModel(repository, vehicleRepository)
        advanceUntilIdle()

        assertEquals(listOf(1L), viewModel.state.value.entries.map { it.tripId })
        coVerify { repository.recent("v1", any()) }

        // The user switches to the second vehicle.
        coEvery { vehicleRepository.active() } returns vehicle("v2")
        coEvery { vehicleRepository.setActive("v2") } returns Unit
        viewModel.selectVehicle("v2")
        advanceUntilIdle()

        assertEquals(listOf(2L), viewModel.state.value.entries.map { it.tripId })
        coVerify { repository.recent("v2", any()) }
    }

    @Test
    fun `openDetail and dismissDetail toggle the selected entry`() = runTest(dispatcher) {
        val repository = mockk<DriveHistoryRepository>()
        val vehicleRepository = mockk<VehicleRepository>()
        every { vehicleRepository.vehicles() } returns MutableStateFlow(listOf(vehicle("v1")))
        coEvery { vehicleRepository.active() } returns vehicle("v1")
        coEvery { repository.recent("v1", any()) } returns DriveHistory(emptyList(), null)

        val viewModel = HistoryViewModel(repository, vehicleRepository)
        advanceUntilIdle()

        val entry = entry(7)
        viewModel.openDetail(entry)
        assertEquals(entry, viewModel.state.value.selectedEntry)

        viewModel.dismissDetail()
        assertEquals(null, viewModel.state.value.selectedEntry)
    }

    private fun vehicle(id: String) = VehicleProfile(id = id, name = id)

    private fun entry(tripId: Long) = DriveHistoryEntry(
        searchId = null,
        tripId = tripId,
        originLabel = null,
        destinationLabel = null,
        timestampMs = tripId,
        predictedCost = null,
        predictedLiters = null,
        predictedMinutes = null,
        distanceKm = 1.0,
        actualCost = 1.0,
        actualLiters = 0.1,
        actualMinutes = 1.0,
        pricePerLiterAtSearch = null,
        pricePerLiterAtTrip = null,
        savedAmount = 0.0,
    )
}
