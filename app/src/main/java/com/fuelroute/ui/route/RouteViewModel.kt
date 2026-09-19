package com.fuelroute.ui.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fuelroute.R
import com.fuelroute.data.location.Coordinates
import com.fuelroute.data.location.LocationRepository
import com.fuelroute.data.location.ReverseGeocoder
import com.fuelroute.data.obd.LearnedCurveRepository
import com.fuelroute.data.places.PlaceSuggestion
import com.fuelroute.data.places.PlacesHistoryRepository
import com.fuelroute.data.places.PlacesRepository
import com.fuelroute.data.places.RecentPlace
import com.fuelroute.data.routes.RouteSearch
import com.fuelroute.data.routes.RouteSearchRepository
import com.fuelroute.data.routes.RouteWaypoint
import com.fuelroute.data.routes.RoutesRepository
import com.fuelroute.data.settings.NAV_GOOGLE
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.fuel.ConsumptionCurve
import com.fuelroute.domain.fuel.CurveBlender
import com.fuelroute.domain.fuel.DefaultCurve
import com.fuelroute.domain.fuel.FuelModel
import com.fuelroute.domain.model.RouteCost
import com.fuelroute.domain.model.SpeedPoint
import com.fuelroute.domain.ranking.RouteRanker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val DEFAULT_FUEL_PRICE = 7.0
private const val DEFAULT_IDLE_LPH = 0.8
private const val AUTOCOMPLETE_DEBOUNCE_MS = 300L
private const val DEFAULT_VEHICLE_ID = "default"

data class RouteUiState(
    val origin: String = "",
    val destination: String = "",
    val originPlaceId: String? = null,
    val destinationPlaceId: String? = null,
    val originLocation: Coordinates? = null,
    val destinationLocation: Coordinates? = null,
    val originIsCurrentLocation: Boolean = false,
    val originAddress: String? = null,
    val originSuggestions: List<PlaceSuggestion> = emptyList(),
    val destinationSuggestions: List<PlaceSuggestion> = emptyList(),
    val history: List<RecentPlace> = emptyList(),
    val locationError: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val results: List<RouteCost> = emptyList(),
    val selectedIndex: Int = 0,
    val fuelPricePerLiter: Double = DEFAULT_FUEL_PRICE,
    val learnedKm: Double = 0.0,
    val navigationApp: String = NAV_GOOGLE,
    val routeCountMessage: Int? = null,
)

object RouteCountMessages {
    fun message(count: Int): Int? = when {
        count <= 0 -> R.string.route_no_routes
        count == 1 -> R.string.route_single
        else -> null
    }
}

@OptIn(FlowPreview::class)
@HiltViewModel
class RouteViewModel @Inject constructor(
    private val routesRepository: RoutesRepository,
    private val placesRepository: PlacesRepository,
    private val placesHistoryRepository: PlacesHistoryRepository,
    private val locationRepository: LocationRepository,
    private val reverseGeocoder: ReverseGeocoder,
    private val vehicleRepository: VehicleRepository,
    private val learnedCurveRepository: LearnedCurveRepository,
    private val settingsRepository: SettingsRepository,
    private val routeSearchRepository: RouteSearchRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RouteUiState())
    val uiState: StateFlow<RouteUiState> = _uiState.asStateFlow()

    private val originQuery = MutableStateFlow("")
    private val destinationQuery = MutableStateFlow("")

    init {
        viewModelScope.launch {
            settingsRepository.settings.first().let { settings ->
                _uiState.update {
                    it.copy(
                        navigationApp = settings.navigationApp,
                        fuelPricePerLiter = settings.fuelPricePerLiter,
                    )
                }
            }
        }

        viewModelScope.launch {
            placesHistoryRepository.history.collect { history ->
                _uiState.update { it.copy(history = history) }
            }
        }

        viewModelScope.launch {
            originQuery
                .debounce(AUTOCOMPLETE_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { query ->
                    val state = _uiState.value
                    if (query.isBlank() || state.originPlaceId != null || state.originIsCurrentLocation) {
                        _uiState.update { it.copy(originSuggestions = emptyList()) }
                    } else {
                        val suggestions = runCatching { placesRepository.autocomplete(query) }
                            .getOrDefault(emptyList())
                        _uiState.update { it.copy(originSuggestions = suggestions) }
                    }
                }
        }

        viewModelScope.launch {
            destinationQuery
                .debounce(AUTOCOMPLETE_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { query ->
                    val state = _uiState.value
                    if (query.isBlank() || state.destinationPlaceId != null) {
                        _uiState.update { it.copy(destinationSuggestions = emptyList()) }
                    } else {
                        val suggestions = runCatching { placesRepository.autocomplete(query) }
                            .getOrDefault(emptyList())
                        _uiState.update { it.copy(destinationSuggestions = suggestions) }
                    }
                }
        }
    }

    fun onOriginChange(value: String) {
        _uiState.update {
            it.copy(
                origin = value,
                originPlaceId = null,
                originLocation = null,
                originAddress = null,
                originIsCurrentLocation = false,
                originSuggestions = emptyList(),
                locationError = false,
                error = null,
            )
        }
        originQuery.value = value
    }

    fun onDestinationChange(value: String) {
        _uiState.update {
            it.copy(
                destination = value,
                destinationPlaceId = null,
                destinationLocation = null,
                destinationSuggestions = emptyList(),
                error = null,
            )
        }
        destinationQuery.value = value
    }

    fun onOriginSelect(suggestion: PlaceSuggestion) {
        _uiState.update {
            it.copy(
                origin = suggestion.mainText,
                originPlaceId = suggestion.placeId,
                originLocation = null,
                originIsCurrentLocation = false,
                originSuggestions = emptyList(),
            )
        }
        originQuery.value = ""
    }

    fun onDestinationSelect(suggestion: PlaceSuggestion) {
        _uiState.update {
            it.copy(
                destination = suggestion.mainText,
                destinationPlaceId = suggestion.placeId,
                destinationSuggestions = emptyList(),
            )
        }
        destinationQuery.value = ""
    }

    fun onRecentSelected(place: RecentPlace) {
        _uiState.update {
            it.copy(
                destination = place.label,
                destinationPlaceId = place.placeId,
                destinationLocation = if (place.latitude != null && place.longitude != null) {
                    Coordinates(place.latitude, place.longitude)
                } else {
                    null
                },
                destinationSuggestions = emptyList(),
            )
        }
        destinationQuery.value = ""
    }

    fun useCurrentLocation() {
        viewModelScope.launch {
            val coords = locationRepository.currentLocation()
            if (coords != null) {
                val address = reverseGeocoder.reverseGeocode(coords.latitude, coords.longitude)
                _uiState.update {
                    it.copy(
                        origin = "",
                        originPlaceId = null,
                        originLocation = coords,
                        originAddress = address,
                        originIsCurrentLocation = true,
                        originSuggestions = emptyList(),
                        locationError = false,
                    )
                }
                originQuery.value = ""
            } else {
                _uiState.update { it.copy(locationError = true) }
            }
        }
    }

    fun onClearOriginLocation() {
        _uiState.update {
            it.copy(
                originIsCurrentLocation = false,
                originLocation = null,
                originAddress = null,
                origin = "",
                locationError = false,
            )
        }
    }

    fun selectResult(index: Int) = _uiState.update { it.copy(selectedIndex = index) }

    fun compute() {
        val state = _uiState.value
        val origin = state.origin.trim()
        val destination = state.destination.trim()
        val originSet = state.originIsCurrentLocation || origin.isNotBlank()
        if (!originSet || destination.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), routeCountMessage = null) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, results = emptyList(), routeCountMessage = null) }
            try {
                val vehicle = vehicleRepository.profile.first()
                val settings = settingsRepository.settings.first()
                val learned = learnedCurveRepository.learnedCurve(vehicle.id.ifBlank { DEFAULT_VEHICLE_ID })
                val default = DefaultCurve.forVehicle(vehicle.ratedCombinedL100, vehicle.fuelType)
                val fallback = effectiveFallback(vehicle.manualCurve, default)
                val curve = CurveBlender.blend(learned, fallback)
                val fuelModel = FuelModel(curve, learned.idleLitersPerHour ?: DEFAULT_IDLE_LPH)

                val originWaypoint = when {
                    state.originIsCurrentLocation && state.originLocation != null ->
                        RouteWaypoint(latitude = state.originLocation.latitude, longitude = state.originLocation.longitude)
                    state.originPlaceId != null -> RouteWaypoint(placeId = state.originPlaceId)
                    else -> RouteWaypoint(address = origin)
                }
                val destinationWaypoint = when {
                    state.destinationPlaceId != null -> RouteWaypoint(placeId = state.destinationPlaceId)
                    state.destinationLocation != null ->
                        RouteWaypoint(latitude = state.destinationLocation.latitude, longitude = state.destinationLocation.longitude)
                    else -> RouteWaypoint(address = destination)
                }

                val routes = routesRepository.getAlternatives(originWaypoint, destinationWaypoint)
                val fuelPrice = settings.fuelPricePerLiter
                val ranked = RouteRanker.rank(
                    routes.map { fuelModel.cost(it, fuelPrice) },
                    valuePerMinute = settings.valuePerMinute,
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        results = ranked,
                        selectedIndex = 0,
                        learnedKm = learned.totalDistanceKm,
                        fuelPricePerLiter = fuelPrice,
                        routeCountMessage = RouteCountMessages.message(ranked.size),
                    )
                }

                if (state.destinationPlaceId != null || destination.isNotBlank()) {
                    placesHistoryRepository.add(
                        RecentPlace(label = destination, placeId = state.destinationPlaceId),
                    )
                }

                ranked.firstOrNull()?.let { cheapest ->
                    val fastest = ranked.minByOrNull { it.durationMinutes }
                    if (fastest != null) {
                        routeSearchRepository.add(
                            RouteSearch(
                                originLabel = origin,
                                destinationLabel = destination,
                                timestampMs = System.currentTimeMillis(),
                                cheapestCost = cheapest.totalCost,
                                savedAmount = (fastest.totalCost - cheapest.totalCost).coerceAtLeast(0.0),
                                predictedLiters = cheapest.fuelLiters,
                                distanceKm = cheapest.distanceKm,
                                durationMin = cheapest.durationMinutes,
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun effectiveFallback(
        manualCurve: List<SpeedPoint>?,
        default: ConsumptionCurve,
    ): ConsumptionCurve = manualCurve
        ?.takeIf { it.size >= 2 }
        ?.let { runCatching { ConsumptionCurve(it) }.getOrNull() }
        ?: default
}