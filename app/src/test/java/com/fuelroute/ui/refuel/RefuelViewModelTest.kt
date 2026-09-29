package com.fuelroute.ui.refuel

import com.fuelroute.data.price.FuelPrice
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.refuel.FullRefuelInterval
import com.fuelroute.data.refuel.RefuelRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.learning.RefuelCalibrator
import com.fuelroute.domain.model.VehicleProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Tank-to-tank calibration as the refuel screen applies it. */
@OptIn(ExperimentalCoroutinesApi::class)
class RefuelViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val refuels = mockk<RefuelRepository>(relaxed = true)
    private val prices = mockk<FuelPriceRepository>()
    private val vehicles = mockk<VehicleRepository>()
    private var vehicle = VehicleProfile(id = "v1", name = "Car", fuelRateCorrection = 1.25)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { vehicles.active() } answers { vehicle }
        coEvery { vehicles.updateFuelRateCorrection(any()) } answers {
            vehicle = vehicle.copy(fuelRateCorrection = firstArg())
        }
        coEvery { prices.onFullRefuel(any(), any()) } returns FuelPrice(7.0, "95", manuallyPinned = false)
        coEvery { refuels.recent(any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun interval(pumped: Double, obd: Double, active: Double? = null) =
        FullRefuelInterval(pumped, obd, fromTimestampMs = 0L, toTimestampMs = 1L, activeCorrection = active)

    private fun saveFull(viewModel: RefuelViewModel) {
        viewModel.onLitersChange("40")
        viewModel.onPriceChange("280")
        viewModel.save(confirmed = true)
    }

    @Test
    fun `a matching tank keeps a correct correction instead of resetting it`() = runTest {
        // Trips were logged with 1.25 and match the pump: the correction must stay 1.25.
        coEvery { refuels.lastFullInterval("v1") } returns interval(pumped = 40.0, obd = 40.0, active = 1.25)
        val viewModel = RefuelViewModel(refuels, vehicles, prices)
        advanceUntilIdle()

        saveFull(viewModel)
        advanceUntilIdle()

        coVerify { vehicles.updateFuelRateCorrection(1.25) }
        assertEquals(1.25, viewModel.state.value.correction, 1e-9)
        assertNull(viewModel.state.value.pendingClamped)
    }

    @Test
    fun `the fill records the correction active during the interval`() = runTest {
        coEvery { refuels.lastFullInterval("v1") } returns null
        val viewModel = RefuelViewModel(refuels, vehicles, prices)
        advanceUntilIdle()

        saveFull(viewModel)
        advanceUntilIdle()

        coVerify { refuels.add(40.0, 280.0, true, "v1", "95", 1.25) }
    }

    @Test
    fun `a clamped result waits for confirmation`() = runTest {
        // 1.25 x 60/40 = 1.875: clamped to the maximum, probably drives without the dongle.
        coEvery { refuels.lastFullInterval("v1") } returns interval(pumped = 60.0, obd = 40.0, active = 1.25)
        val viewModel = RefuelViewModel(refuels, vehicles, prices)
        advanceUntilIdle()

        saveFull(viewModel)
        advanceUntilIdle()

        coVerify(exactly = 0) { vehicles.updateFuelRateCorrection(any()) }
        val pending = viewModel.state.value.pendingClamped
        assertNotNull(pending)
        assertEquals(RefuelCalibrator.MAX_FACTOR, pending!!.factor, 1e-9)

        viewModel.applyClampedCalibration()
        advanceUntilIdle()

        coVerify { vehicles.updateFuelRateCorrection(RefuelCalibrator.MAX_FACTOR) }
        assertNull(viewModel.state.value.pendingClamped)
        assertEquals(RefuelCalibrator.MAX_FACTOR, viewModel.state.value.correction, 1e-9)
    }

    @Test
    fun `a discarded clamped result changes nothing`() = runTest {
        coEvery { refuels.lastFullInterval("v1") } returns interval(pumped = 60.0, obd = 40.0, active = 1.25)
        val viewModel = RefuelViewModel(refuels, vehicles, prices)
        advanceUntilIdle()
        saveFull(viewModel)
        advanceUntilIdle()

        viewModel.discardClampedCalibration()
        advanceUntilIdle()

        coVerify(exactly = 0) { vehicles.updateFuelRateCorrection(any()) }
        assertNull(viewModel.state.value.pendingClamped)
        assertEquals(1.25, viewModel.state.value.correction, 1e-9)
    }

    @Test
    fun `low obd coverage is explained and never applied`() = runTest {
        coEvery { refuels.lastFullInterval("v1") } returns interval(pumped = 45.0, obd = 12.0, active = 1.25)
        val viewModel = RefuelViewModel(refuels, vehicles, prices)
        advanceUntilIdle()

        saveFull(viewModel)
        advanceUntilIdle()

        coVerify(exactly = 0) { vehicles.updateFuelRateCorrection(any()) }
        assertEquals(LowCoverageNotice(45.0, 12.0), viewModel.state.value.lowCoverage)
        assertNull(viewModel.state.value.pendingClamped)
    }

    @Test
    fun `an interval from before the correction was recorded uses the current one`() = runTest {
        coEvery { refuels.lastFullInterval("v1") } returns interval(pumped = 44.0, obd = 40.0, active = null)
        val viewModel = RefuelViewModel(refuels, vehicles, prices)
        advanceUntilIdle()

        saveFull(viewModel)
        advanceUntilIdle()

        coVerify { vehicles.updateFuelRateCorrection(1.25 * 44.0 / 40.0) }
    }
}
