package com.fuelroute.ui.debug

import com.fuelroute.data.history.DriveHistory
import com.fuelroute.data.history.DriveHistoryEntry
import com.fuelroute.data.history.DriveHistoryRepository
import com.fuelroute.data.refuel.FullRefuelInterval
import com.fuelroute.data.refuel.RefuelRepository
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.fuel.FuelModelOverrides
import com.fuelroute.domain.learning.Calibration
import com.fuelroute.domain.model.VehicleProfile
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val history = mockk<DriveHistoryRepository>()
    private val vehicles = mockk<VehicleRepository>()
    private val refuels = mockk<RefuelRepository>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { vehicles.active() } returns VehicleProfile(id = "v1", name = "Car", fuelRateCorrection = 1.2)
        coEvery { refuels.lastFullInterval("v1") } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun entry(
        id: Long,
        predicted: Double,
        actual: Double,
        correction: Double?,
        searchedKm: Double = 40.0,
        drivenKm: Double = 40.0,
    ) = DriveHistoryEntry(
        searchId = id,
        tripId = id,
        originLabel = "a",
        destinationLabel = "b",
        timestampMs = id,
        predictedCost = predicted * 7.0,
        predictedLiters = predicted,
        predictedMinutes = 30.0,
        distanceKm = drivenKm,
        actualCost = actual * 7.0,
        actualLiters = actual,
        actualMinutes = 30.0,
        pricePerLiterAtSearch = 7.0,
        pricePerLiterAtTrip = 7.0,
        savedAmount = 0.0,
        predictedDistanceKm = searchedKm,
        fuelCorrectionAtSearch = correction,
    )

    private fun history(vararg entries: DriveHistoryEntry) {
        coEvery { history.recent("v1", any()) } returns DriveHistory(entries.toList(), accuracyPct = null)
    }

    @Test
    fun `a corrupt stored correction does not poison the fit`() = runTest {
        // Legacy 0.0035 is ignored by the fuel model (effective 1.0), so the predictions below
        // include 1.0, not 0.0035. Dividing by the raw text made every pair absurd.
        every { settings.modelOverrides } returns flowOf(FuelModelOverrides(fuelCorrection = 0.0035))
        history(
            entry(1, predicted = 3.0, actual = 3.6, correction = null),
            entry(2, predicted = 4.0, actual = 4.8, correction = null),
            entry(3, predicted = 5.0, actual = 6.0, correction = null),
        )
        val viewModel = CalibrationViewModel(settings, history, vehicles, refuels)
        advanceUntilIdle()

        viewModel.fit()
        advanceUntilIdle()

        assertEquals(1.2, viewModel.state.value.suggestedFactor!!, 1e-9)
    }

    @Test
    fun `each search is un-corrected with its own correction and mismatched distances are dropped`() = runTest {
        every { settings.modelOverrides } returns flowOf(FuelModelOverrides(fuelCorrection = 1.1))
        history(
            entry(1, predicted = 3.0 * 0.9, actual = 3.6, correction = 0.9),
            entry(2, predicted = 4.0 * 1.1, actual = 4.8, correction = 1.1),
            entry(3, predicted = 5.0 * 1.1, actual = 6.0, correction = null),
            // Drove only half the searched route: excluded.
            entry(4, predicted = 5.0, actual = 2.0, correction = 1.0, searchedKm = 40.0, drivenKm = 20.0),
        )
        val viewModel = CalibrationViewModel(settings, history, vehicles, refuels)
        advanceUntilIdle()

        viewModel.fit()
        advanceUntilIdle()

        assertEquals(1.2, viewModel.state.value.suggestedFactor!!, 1e-9)
        assertEquals(3, viewModel.state.value.pairCount)
    }

    @Test
    fun `the refuel result is computed against the correction the interval was logged with`() = runTest {
        every { settings.modelOverrides } returns flowOf(FuelModelOverrides.DEFAULT)
        history()
        // Already applied: the vehicle now carries 1.2 but the interval was logged with 1.0.
        coEvery { refuels.lastFullInterval("v1") } returns
            FullRefuelInterval(48.0, 40.0, fromTimestampMs = 0L, toTimestampMs = 1L, activeCorrection = 1.0)
        val viewModel = CalibrationViewModel(settings, history, vehicles, refuels)
        advanceUntilIdle()

        assertEquals(Calibration.Exact(1.2), viewModel.state.value.refuelCalibration)
    }
}
