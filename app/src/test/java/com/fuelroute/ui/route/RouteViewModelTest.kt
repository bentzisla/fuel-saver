package com.fuelroute.ui.route

import androidx.lifecycle.SavedStateHandle
import com.fuelroute.data.learning.ColdStartRepository
import com.fuelroute.data.location.Coordinates
import com.fuelroute.data.obd.LearnedCurveRepository
import com.fuelroute.data.places.FavoritesRepository
import com.fuelroute.data.places.PlacesHistoryRepository
import com.fuelroute.data.price.FuelPrice
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.routes.RouteSearchRepository
import com.fuelroute.data.routes.RouteWaypoint
import com.fuelroute.data.routes.RoutesRepository
import com.fuelroute.data.settings.AppSettings
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.fuel.FuelModelOverrides
import com.fuelroute.domain.learning.ColdStartStats
import com.fuelroute.domain.learning.LearnedCurve
import com.fuelroute.domain.model.VehicleProfile
import io.mockk.coEvery
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
import org.junit.Before
import org.junit.Test

/** Process death: the search inputs come back and the routes are recomputed, not stored. */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Fixture(val handle: SavedStateHandle) {
        val routes = mockk<RoutesRepository>()
        val vehicleRepository = mockk<VehicleRepository>()
        val settingsRepository = mockk<SettingsRepository>()
        val fuelPriceRepository = mockk<FuelPriceRepository>()
        val places = mockk<PlacesHistoryRepository>()
        val favorites = mockk<FavoritesRepository>()
        val learned = mockk<LearnedCurveRepository>()
        val coldStart = mockk<ColdStartRepository>()
        val searches = mockk<RouteSearchRepository>()

        init {
            every { settingsRepository.settings } returns flowOf(AppSettings())
            every { settingsRepository.modelOverrides } returns flowOf(FuelModelOverrides())
            coEvery { vehicleRepository.active() } returns VehicleProfile(id = "v1", name = "v1")
            coEvery { fuelPriceRepository.current(any()) } returns FuelPrice(7.0, "95", false)
            every { places.history } returns flowOf(emptyList())
            coEvery { places.add(any()) } returns Unit
            every { favorites.favorites } returns flowOf(emptyList())
            coEvery { learned.learnedCurve(any()) } returns LearnedCurve(emptyList())
            coEvery { coldStart.stats(any()) } returns ColdStartStats.initial()
            coEvery { routes.getAlternatives(any(), any(), any(), any()) } returns emptyList()
            coEvery { searches.record(any()) } returns 1L
        }

        fun viewModel() = RouteViewModel(
            routesRepository = routes,
            placesRepository = mockk(relaxed = true),
            placesHistoryRepository = places,
            favoritesRepository = favorites,
            locationRepository = mockk(relaxed = true),
            reverseGeocoder = mockk(relaxed = true),
            vehicleRepository = vehicleRepository,
            learnedCurveRepository = learned,
            settingsRepository = settingsRepository,
            fuelPriceRepository = fuelPriceRepository,
            routeSearchRepository = searches,
            tripLinker = mockk(relaxed = true),
            coldStartRepository = coldStart,
            navigationPlanner = mockk(relaxed = true),
            savedStateHandle = handle,
        )
    }

    @Test
    fun `search inputs are written to the saved state`() = runTest(dispatcher) {
        val fixture = Fixture(SavedStateHandle())
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        viewModel.onOriginChange("Tel Aviv")
        viewModel.onDestinationChange("Haifa")
        advanceUntilIdle()

        val restored = restoreInputs(fixture.handle)
        assertEquals("Tel Aviv", restored.origin)
        assertEquals("Haifa", restored.destination)
    }

    @Test
    fun `restored destination re-runs the search with the stored inputs`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        saveInputs(
            handle,
            RouteUiState(
                destination = "Haifa",
                destinationPlaceId = "place-haifa",
                destinationLocation = Coordinates(32.8, 35.0),
                originIsCurrentLocation = true,
                originLocation = Coordinates(32.0, 34.8),
                originAddress = "Dizengoff 1",
                selectedIndex = 2,
            ),
        )
        val fixture = Fixture(handle)

        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Haifa", state.destination)
        assertEquals(Coordinates(32.0, 34.8), state.originLocation)
        assertEquals("Dizengoff 1", state.originAddress)
        assertFalse(state.isLoading)
        coVerify(exactly = 1) {
            fixture.routes.getAlternatives(
                origin = RouteWaypoint(latitude = 32.0, longitude = 34.8),
                destination = RouteWaypoint(placeId = "place-haifa"),
                options = any(),
                forceRefresh = false,
            )
        }
    }

    @Test
    fun `fresh start without a destination does not search`() = runTest(dispatcher) {
        val fixture = Fixture(SavedStateHandle())

        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.destination)
        assertNull(viewModel.uiState.value.originPlaceId)
        coVerify(exactly = 0) { fixture.routes.getAlternatives(any(), any(), any(), any()) }
    }
}
