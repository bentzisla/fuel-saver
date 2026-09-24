package com.fuelroute.ui.route

import com.fuelroute.data.history.TripLinker
import com.fuelroute.data.learning.ColdStartRepository
import com.fuelroute.data.location.LocationRepository
import com.fuelroute.data.location.ReverseGeocoder
import com.fuelroute.data.obd.LearnedCurveRepository
import com.fuelroute.data.places.FavoritesRepository
import com.fuelroute.data.places.PlacesHistoryRepository
import com.fuelroute.data.places.PlacesRepository
import com.fuelroute.data.price.FuelPrice
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.routes.RouteSearchRepository
import com.fuelroute.data.routes.RoutesRepository
import com.fuelroute.data.settings.AppSettings
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.fuel.FuelModelOverrides
import com.fuelroute.domain.learning.ColdStartStats
import com.fuelroute.domain.learning.LearnedCurve
import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteSegment
import com.fuelroute.domain.model.VehicleProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Before
import org.junit.Test

/** A newer route search must cancel the in-flight one so a stale result never wins the race. */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteViewModelSearchTest {

    private val dispatcher = StandardTestDispatcher()
    private val routesRepository = mockk<RoutesRepository>()
    private val routeSearchRepository = mockk<RouteSearchRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): RouteViewModel {
        val placesHistory = mockk<PlacesHistoryRepository>(relaxed = true)
        every { placesHistory.history } returns flowOf(emptyList())
        val favorites = mockk<FavoritesRepository>(relaxed = true)
        every { favorites.favorites } returns flowOf(emptyList())
        val vehicles = mockk<VehicleRepository>()
        coEvery { vehicles.active() } returns VehicleProfile(id = "v1", name = "Car")
        val learned = mockk<LearnedCurveRepository>()
        coEvery { learned.learnedCurve(any()) } returns LearnedCurve(emptyList())
        val settings = mockk<SettingsRepository>()
        every { settings.settings } returns flowOf(AppSettings())
        every { settings.modelOverrides } returns flowOf(FuelModelOverrides.DEFAULT)
        val prices = mockk<FuelPriceRepository>()
        coEvery { prices.current(any()) } returns FuelPrice(7.0, "95", manuallyPinned = false)
        val coldStart = mockk<ColdStartRepository>()
        coEvery { coldStart.stats(any()) } returns ColdStartStats(meanExtraL = 0.0, count = 0)
        return RouteViewModel(
            routesRepository = routesRepository,
            placesRepository = mockk<PlacesRepository>(relaxed = true),
            placesHistoryRepository = placesHistory,
            favoritesRepository = favorites,
            locationRepository = mockk<LocationRepository>(relaxed = true),
            reverseGeocoder = mockk<ReverseGeocoder>(relaxed = true),
            vehicleRepository = vehicles,
            learnedCurveRepository = learned,
            settingsRepository = settings,
            fuelPriceRepository = prices,
            routeSearchRepository = routeSearchRepository,
            tripLinker = mockk<TripLinker>(relaxed = true),
            coldStartRepository = coldStart,
            navigationPlanner = mockk(relaxed = true),
        )
    }

    private fun route(id: String, km: Double) = Route(
        id = id,
        distanceMeters = km * 1000,
        staticDurationSeconds = km * 60,
        durationSeconds = km * 60,
        segments = listOf(RouteSegment(distanceMeters = km * 1000, staticDurationSeconds = km * 60)),
    )

    @Test
    fun `a newer search cancels the older one`() = runTest(dispatcher) {
        val slow = CompletableDeferred<List<Route>>()
        coEvery { routesRepository.getAlternatives(any(), any(), any(), false) } coAnswers { slow.await() }
        coEvery { routesRepository.getAlternatives(any(), any(), any(), true) } returns listOf(route("fresh", 10.0))

        val vm = viewModel()
        vm.onOriginChange("A")
        vm.onDestinationChange("B")
        vm.compute()
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        // The stale search completing late must not overwrite the fresh result.
        slow.complete(listOf(route("stale", 50.0)))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf("fresh"), state.results.map { it.route.id })
        assertFalse(state.isLoading)
        coVerify(exactly = 1) { routeSearchRepository.add(any()) }
    }
}
