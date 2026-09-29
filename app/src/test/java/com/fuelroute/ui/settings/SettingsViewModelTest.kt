package com.fuelroute.ui.settings

import com.fuelroute.data.price.FuelPrice
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.settings.AppSettings
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.model.VehicleProfile
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

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
    fun `parseValuePerMinute accepts finite values from 0 to 10 only`() {
        assertEquals(0.0, SettingsViewModel.parseValuePerMinute("0")!!, 0.0)
        assertEquals(0.5, SettingsViewModel.parseValuePerMinute(" 0.5 ")!!, 0.0)
        assertEquals(10.0, SettingsViewModel.parseValuePerMinute("10")!!, 0.0)
        assertNull(SettingsViewModel.parseValuePerMinute("-1"))
        assertNull(SettingsViewModel.parseValuePerMinute("10.01"))
        assertNull(SettingsViewModel.parseValuePerMinute("NaN"))
        assertNull(SettingsViewModel.parseValuePerMinute("Infinity"))
        assertNull(SettingsViewModel.parseValuePerMinute("-Infinity"))
        assertNull(SettingsViewModel.parseValuePerMinute(""))
        assertNull(SettingsViewModel.parseValuePerMinute("abc"))
    }

    @Test
    fun `invalid value per minute is flagged and not saved`() = runTest(dispatcher) {
        val settingsRepository = mockk<SettingsRepository>()
        val viewModel = viewModel(settingsRepository)
        advanceUntilIdle()

        viewModel.onValuePerMinuteChange("-3")
        viewModel.onValuePerMinuteChange("NaN")
        viewModel.onValuePerMinuteChange("Infinity")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.valuePerMinuteInvalid)
        assertEquals("Infinity", viewModel.uiState.value.valuePerMinute)
        coVerify(exactly = 0) { settingsRepository.saveValuePerMinute(any()) }
    }

    @Test
    fun `valid value per minute clears the error and is saved`() = runTest(dispatcher) {
        val settingsRepository = mockk<SettingsRepository>()
        val viewModel = viewModel(settingsRepository)
        advanceUntilIdle()

        viewModel.onValuePerMinuteChange("50")
        assertTrue(viewModel.uiState.value.valuePerMinuteInvalid)

        viewModel.onValuePerMinuteChange("0.8")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.valuePerMinuteInvalid)
        coVerify(exactly = 1) { settingsRepository.saveValuePerMinute(0.8) }
    }

    private fun viewModel(settingsRepository: SettingsRepository): SettingsViewModel {
        val fuelPriceRepository = mockk<FuelPriceRepository>()
        val vehicleRepository = mockk<VehicleRepository>()
        every { settingsRepository.settings } returns flowOf(AppSettings())
        coJustRun { settingsRepository.saveValuePerMinute(any()) }
        coEvery { vehicleRepository.active() } returns VehicleProfile(id = "v1", name = "v1")
        coEvery { fuelPriceRepository.current(any()) } returns FuelPrice(7.0, "95", false)
        return SettingsViewModel(
            settingsRepository = settingsRepository,
            fuelPriceRepository = fuelPriceRepository,
            vehicleRepository = vehicleRepository,
            backupRepository = mockk(relaxed = true),
            csvExportRepository = mockk(relaxed = true),
            obdProbeScheduler = mockk(relaxed = true),
        )
    }
}
