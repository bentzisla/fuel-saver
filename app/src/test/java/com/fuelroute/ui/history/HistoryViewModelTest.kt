package com.fuelroute.ui.history

import com.fuelroute.data.history.DriveHistory
import com.fuelroute.data.history.DriveHistoryEntry
import com.fuelroute.data.history.DriveHistoryRepository
import com.fuelroute.data.price.FuelPrice
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.history.ManualCostInput
import com.fuelroute.domain.model.VehicleProfile
import io.mockk.coEvery
import io.mockk.coJustRun
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
import org.junit.Assert.assertTrue
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
        val fuelPriceRepository = mockk<FuelPriceRepository>()
        val vehicles = MutableStateFlow(listOf(vehicle("v1"), vehicle("v2")))

        every { vehicleRepository.vehicles() } returns vehicles
        coEvery { vehicleRepository.active() } returns vehicle("v1")
        coEvery { fuelPriceRepository.current(any()) } returns FuelPrice(7.0, "95", false)
        coEvery { repository.recent("v1", any()) } returns DriveHistory(listOf(entry(1)), null)
        coEvery { repository.recent("v2", any()) } returns DriveHistory(listOf(entry(2)), null)

        val viewModel = HistoryViewModel(repository, vehicleRepository, fuelPriceRepository)
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
        val fuelPriceRepository = mockk<FuelPriceRepository>()
        every { vehicleRepository.vehicles() } returns MutableStateFlow(listOf(vehicle("v1")))
        coEvery { vehicleRepository.active() } returns vehicle("v1")
        coEvery { fuelPriceRepository.current(any()) } returns FuelPrice(7.0, "95", false)
        coEvery { repository.recent("v1", any()) } returns DriveHistory(emptyList(), null)

        val viewModel = HistoryViewModel(repository, vehicleRepository, fuelPriceRepository)
        advanceUntilIdle()

        val entry = entry(7)
        viewModel.openDetail(entry)
        assertEquals(entry, viewModel.state.value.selectedEntry)

        viewModel.dismissDetail()
        assertEquals(null, viewModel.state.value.selectedEntry)
    }

    @Test
    fun `toggleSelectionMode and toggleSelection track checked rows`() = runTest(dispatcher) {
        val repository = mockk<DriveHistoryRepository>()
        val vehicleRepository = mockk<VehicleRepository>()
        val fuelPriceRepository = mockk<FuelPriceRepository>()
        every { vehicleRepository.vehicles() } returns MutableStateFlow(listOf(vehicle("v1")))
        coEvery { vehicleRepository.active() } returns vehicle("v1")
        coEvery { fuelPriceRepository.current(any()) } returns FuelPrice(7.0, "95", false)
        coEvery { repository.recent("v1", any()) } returns DriveHistory(listOf(entry(1)), null)

        val viewModel = HistoryViewModel(repository, vehicleRepository, fuelPriceRepository)
        advanceUntilIdle()

        viewModel.toggleSelectionMode()
        assertTrue(viewModel.state.value.selectionMode)

        viewModel.toggleSelection(entry(1))
        assertEquals(setOf(1L), viewModel.state.value.selectedIds)

        viewModel.toggleSelection(entry(1))
        assertTrue(viewModel.state.value.selectedIds.isEmpty())

        viewModel.toggleSelectionMode()
        assertEquals(false, viewModel.state.value.selectionMode)
    }

    @Test
    fun `confirmBulkDelete removes the selected entries and leaves selection mode`() = runTest(dispatcher) {
        val repository = mockk<DriveHistoryRepository>()
        val vehicleRepository = mockk<VehicleRepository>()
        val fuelPriceRepository = mockk<FuelPriceRepository>()
        every { vehicleRepository.vehicles() } returns MutableStateFlow(listOf(vehicle("v1")))
        coEvery { vehicleRepository.active() } returns vehicle("v1")
        coEvery { fuelPriceRepository.current(any()) } returns FuelPrice(7.0, "95", false)
        coEvery { repository.recent("v1", any()) } returns DriveHistory(listOf(entry(1), entry(2)), null)
        coJustRun { repository.deleteMany(any()) }

        val viewModel = HistoryViewModel(repository, vehicleRepository, fuelPriceRepository)
        advanceUntilIdle()

        viewModel.toggleSelectionMode()
        viewModel.toggleSelection(entry(1))
        viewModel.requestBulkDelete()
        viewModel.confirmBulkDelete()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.deleteMany(listOf(entry(1))) }
        assertEquals(false, viewModel.state.value.selectionMode)
        assertTrue(viewModel.state.value.selectedIds.isEmpty())
    }

    @Test
    fun `confirmMerge reports success feedback when the merge succeeds`() = runTest(dispatcher) {
        val repository = mockk<DriveHistoryRepository>()
        val vehicleRepository = mockk<VehicleRepository>()
        val fuelPriceRepository = mockk<FuelPriceRepository>()
        every { vehicleRepository.vehicles() } returns MutableStateFlow(listOf(vehicle("v1")))
        coEvery { vehicleRepository.active() } returns vehicle("v1")
        coEvery { fuelPriceRepository.current(any()) } returns FuelPrice(7.0, "95", false)
        coEvery { repository.recent("v1", any()) } returns DriveHistory(emptyList(), null)
        coEvery { repository.mergeTrips(listOf(1L, 2L)) } returns 99L

        val viewModel = HistoryViewModel(repository, vehicleRepository, fuelPriceRepository)
        advanceUntilIdle()

        viewModel.requestMerge(listOf(1L, 2L))
        viewModel.confirmMerge()
        advanceUntilIdle()

        assertEquals(MergeSplitFeedback.MERGED, viewModel.state.value.mergeSplitFeedback)
        assertEquals(null, viewModel.state.value.pendingMerge)
    }

    @Test
    fun `confirmMerge reports failure feedback when the merge is rejected`() = runTest(dispatcher) {
        val repository = mockk<DriveHistoryRepository>()
        val vehicleRepository = mockk<VehicleRepository>()
        val fuelPriceRepository = mockk<FuelPriceRepository>()
        every { vehicleRepository.vehicles() } returns MutableStateFlow(listOf(vehicle("v1")))
        coEvery { vehicleRepository.active() } returns vehicle("v1")
        coEvery { fuelPriceRepository.current(any()) } returns FuelPrice(7.0, "95", false)
        coEvery { repository.recent("v1", any()) } returns DriveHistory(emptyList(), null)
        coEvery { repository.mergeTrips(listOf(1L, 2L)) } returns null

        val viewModel = HistoryViewModel(repository, vehicleRepository, fuelPriceRepository)
        advanceUntilIdle()

        viewModel.requestMerge(listOf(1L, 2L))
        viewModel.confirmMerge()
        advanceUntilIdle()

        assertEquals(MergeSplitFeedback.FAILED, viewModel.state.value.mergeSplitFeedback)
    }

    @Test
    fun `confirmSplit reports success and failure feedback`() = runTest(dispatcher) {
        val repository = mockk<DriveHistoryRepository>()
        val vehicleRepository = mockk<VehicleRepository>()
        val fuelPriceRepository = mockk<FuelPriceRepository>()
        every { vehicleRepository.vehicles() } returns MutableStateFlow(listOf(vehicle("v1")))
        coEvery { vehicleRepository.active() } returns vehicle("v1")
        coEvery { fuelPriceRepository.current(any()) } returns FuelPrice(7.0, "95", false)
        coEvery { repository.recent("v1", any()) } returns DriveHistory(emptyList(), null)

        val viewModel = HistoryViewModel(repository, vehicleRepository, fuelPriceRepository)
        advanceUntilIdle()

        coEvery { repository.splitTrip(7, 500L) } returns (10L to 11L)
        viewModel.requestSplit(7, 500L)
        viewModel.confirmSplit()
        advanceUntilIdle()
        assertEquals(MergeSplitFeedback.SPLIT, viewModel.state.value.mergeSplitFeedback)

        coEvery { repository.splitTrip(7, 900L) } returns null
        viewModel.requestSplit(7, 900L)
        viewModel.confirmSplit()
        advanceUntilIdle()
        assertEquals(MergeSplitFeedback.FAILED, viewModel.state.value.mergeSplitFeedback)
    }

    @Test
    fun `saveManualCost persists the entry and closes the dialog`() = runTest(dispatcher) {
        val repository = mockk<DriveHistoryRepository>()
        val vehicleRepository = mockk<VehicleRepository>()
        val fuelPriceRepository = mockk<FuelPriceRepository>()
        every { vehicleRepository.vehicles() } returns MutableStateFlow(listOf(vehicle("v1")))
        coEvery { vehicleRepository.active() } returns vehicle("v1")
        coEvery { fuelPriceRepository.current(any()) } returns FuelPrice(7.0, "95", false)
        coEvery { repository.recent("v1", any()) } returns DriveHistory(emptyList(), null)
        coEvery { repository.recordManualCost(any(), any(), any(), any(), any(), any(), any()) } returns 7L

        val viewModel = HistoryViewModel(repository, vehicleRepository, fuelPriceRepository)
        advanceUntilIdle()

        val driveEntry = entry(7)
        viewModel.openManualCost(driveEntry)
        viewModel.saveManualCost(ManualCostInput(cost = 42.0))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.recordManualCost(
                vehicleId = "v1",
                entry = driveEntry,
                cost = 42.0,
                distanceKm = null,
                litersPer100Km = null,
                fallbackPricePerLiter = 7.0,
                enteredAtMs = any(),
            )
        }
        assertEquals(null, viewModel.state.value.manualEntryEntry)
    }

    @Test
    fun `openManualCost opens the dialog for an undriven search with no trip yet`() = runTest(dispatcher) {
        val repository = mockk<DriveHistoryRepository>()
        val vehicleRepository = mockk<VehicleRepository>()
        val fuelPriceRepository = mockk<FuelPriceRepository>()
        every { vehicleRepository.vehicles() } returns MutableStateFlow(listOf(vehicle("v1")))
        coEvery { vehicleRepository.active() } returns vehicle("v1")
        coEvery { fuelPriceRepository.current(any()) } returns FuelPrice(7.0, "95", false)
        coEvery { repository.recent("v1", any()) } returns DriveHistory(emptyList(), null)

        val viewModel = HistoryViewModel(repository, vehicleRepository, fuelPriceRepository)
        advanceUntilIdle()

        val undrivenSearch = entry(7).copy(tripId = null, searchId = 3)
        viewModel.openManualCost(undrivenSearch)

        assertEquals(undrivenSearch, viewModel.state.value.manualEntryEntry)
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
