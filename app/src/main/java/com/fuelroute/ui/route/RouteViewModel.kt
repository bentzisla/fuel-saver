package com.fuelroute.ui.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.fuelroute.R
import com.fuelroute.data.history.TripLinker
import com.fuelroute.data.learning.ColdStartRepository
import com.fuelroute.data.location.Coordinates
import com.fuelroute.data.location.LocationRepository
import com.fuelroute.data.location.ReverseGeocoder
import com.fuelroute.data.obd.LearnedCurveRepository
import com.fuelroute.data.places.FavoriteDestination
import com.fuelroute.data.places.FavoritesRepository
import com.fuelroute.data.places.PlaceSuggestion
import com.fuelroute.data.places.PlacesHistoryRepository
import com.fuelroute.data.places.PlacesRepository
import com.fuelroute.data.places.RecentPlace
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.routes.RouteRequestOptions
import com.fuelroute.data.routes.RouteSearch
import com.fuelroute.data.routes.RouteSearchRepository
import com.fuelroute.data.routes.RouteWaypoint
import com.fuelroute.data.routes.RoutesError
import com.fuelroute.data.routes.RoutesRepository
import com.fuelroute.data.routes.toEmissionType
import com.fuelroute.data.settings.NAV_GOOGLE
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.fuel.ConsumptionCurve
import com.fuelroute.domain.fuel.CurveBlender
import com.fuelroute.domain.fuel.DefaultCurve
import com.fuelroute.domain.fuel.FuelModel
import com.fuelroute.domain.fuel.ModelConstants
import com.fuelroute.domain.model.RouteCost
import com.fuelroute.domain.model.SpeedPoint
import com.fuelroute.domain.ranking.RouteRanker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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

private const val AUTOCOMPLETE_DEBOUNCE_MS = 300L

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
    val favorites: List<FavoriteDestination> = emptyList(),
    val locationError: Boolean = false,
    val isLoading: Boolean = false,
    val error: RoutesError? = null,
    val results: List<RouteCost> = emptyList(),
    val selectedIndex: Int = 0,
    val departureTimeMs: Long? = null,
    val fuelPricePerLiter: Double = ModelConstants.DEFAULT_FUEL_PRICE,
    val learnedKm: Double = 0.0,
    val navigationApp: String = NAV_GOOGLE,
    val departLinkFeedback: Int? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class RouteViewModel @Inject constructor(
    private val routesRepository: RoutesRepository,
    private val placesRepository: PlacesRepository,
    private val placesHistoryRepository: PlacesHistoryRepository,
    private val favoritesRepository: FavoritesRepository,
    private val locationRepository: LocationRepository,
    private val reverseGeocoder: ReverseGeocoder,
    private val vehicleRepository: VehicleRepository,
    private val learnedCurveRepository: LearnedCurveRepository,
    private val settingsRepository: SettingsRepository,
    private val fuelPriceRepository: FuelPriceRepository,
    private val routeSearchRepository: RouteSearchRepository,
    private val tripLinker: TripLinker,
    private val coldStartRepository: ColdStartRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RouteUiState())
    val uiState: StateFlow<RouteUiState> = _uiState.asStateFlow()

    /** Id of the route_search row written by the most recent compute(), if any. */
    private var lastSearchId: Long? = null

    /** The in-flight search; a new search cancels it so a stale result can never win the race. */
    private var searchJob: Job? = null

    private val originQuery = MutableStateFlow("")
    private val destinationQuery = MutableStateFlow("")

    init {
        viewModelScope.launch {
            settingsRepository.settings.first().let { settings ->
                _uiState.update { it.copy(navigationApp = settings.navigationApp) }
            }
        }

        viewModelScope.launch {
            val vehicle = vehicleRepository.active()
            val price = fuelPriceRepository.current(vehicle.grade)
            _uiState.update { it.copy(fuelPricePerLiter = price.pricePerLiter) }
        }

        viewModelScope.launch {
            placesHistoryRepository.history.collect { history ->
                _uiState.update { it.copy(history = history) }
            }
        }

        viewModelScope.launch {
            favoritesRepository.favorites.collect { favorites ->
                _uiState.update { it.copy(favorites = favorites) }
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
                origin = fullLabel(suggestion.mainText, suggestion.secondaryText),
                originPlaceId = suggestion.placeId,
                originLocation = null,
                originIsCurrentLocation = false,
                originSuggestions = emptyList(),
            )
        }
        originQuery.value = ""
    }

    fun onDestinationSelect(suggestion: PlaceSuggestion) {
        val label = fullLabel(suggestion.mainText, suggestion.secondaryText)
        _uiState.update {
            it.copy(
                destination = label,
                destinationPlaceId = suggestion.placeId,
                destinationLocation = null,
                destinationSuggestions = emptyList(),
            )
        }
        destinationQuery.value = ""
        resolveDestinationDetails(suggestion.placeId, label)
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
        if (place.placeId != null && (place.latitude == null || place.longitude == null)) {
            resolveDestinationDetails(place.placeId, place.label)
        }
    }

    /** Star/unstar the currently resolved destination (label + exact placeId/coords). */
    fun onToggleDestinationFavorite() {
        val state = _uiState.value
        val label = state.destination.trim()
        if (label.isBlank()) return
        viewModelScope.launch {
            val existing = favoritesRepository.find(state.destinationPlaceId, label)
            if (existing != null) {
                favoritesRepository.remove(existing.id)
            } else {
                favoritesRepository.add(
                    label = label,
                    placeId = state.destinationPlaceId,
                    lat = state.destinationLocation?.latitude,
                    lng = state.destinationLocation?.longitude,
                )
            }
        }
    }

    /** Star/unstar a recents chip. */
    fun onToggleRecentFavorite(place: RecentPlace) {
        viewModelScope.launch {
            val existing = favoritesRepository.find(place.placeId, place.label)
            if (existing != null) {
                favoritesRepository.remove(existing.id)
            } else {
                favoritesRepository.add(place.label, place.placeId, place.latitude, place.longitude)
            }
        }
    }

    /**
     * One tap on a favorite chip: fills the destination with its exact placeId/coordinates and
     * immediately re-runs the search.
     */
    fun onFavoriteSelected(favorite: FavoriteDestination) {
        _uiState.update {
            it.copy(
                destination = favorite.label,
                destinationPlaceId = favorite.placeId,
                destinationLocation = if (favorite.latitude != null && favorite.longitude != null) {
                    Coordinates(favorite.latitude, favorite.longitude)
                } else {
                    null
                },
                destinationSuggestions = emptyList(),
            )
        }
        destinationQuery.value = ""
        if (favorite.placeId != null && (favorite.latitude == null || favorite.longitude == null)) {
            resolveDestinationDetails(favorite.placeId, favorite.label)
        }
        compute()
    }

    fun renameFavorite(id: Long, label: String) {
        viewModelScope.launch { favoritesRepository.rename(id, label) }
    }

    fun removeFavorite(id: Long) {
        viewModelScope.launch { favoritesRepository.remove(id) }
    }

    fun moveFavorite(id: Long, newSortOrder: Int) {
        viewModelScope.launch { favoritesRepository.move(id, newSortOrder) }
    }

    /**
     * Resolves coordinates for the selected place off the keystroke path. Any failure is
     * swallowed: the full label remains a usable fallback and navigation must never be blocked.
     */
    private fun resolveDestinationDetails(placeId: String, fallbackLabel: String) {
        viewModelScope.launch {
            val details = runCatching { placesRepository.details(placeId) }.getOrNull() ?: return@launch
            if (_uiState.value.destinationPlaceId != placeId) return@launch
            val coords = if (details.latitude != null && details.longitude != null) {
                Coordinates(details.latitude, details.longitude)
            } else {
                null
            }
            _uiState.update { state ->
                state.copy(
                    destinationLocation = coords ?: state.destinationLocation,
                    destination = state.destination.ifBlank {
                        details.formattedAddress?.takeIf { it.isNotBlank() } ?: fallbackLabel
                    },
                )
            }
        }
    }

    /** Full "street, city" display label; strips a trailing country and keeps the city. */
    private fun fullLabel(mainText: String, secondaryText: String?): String {
        val main = mainText.trim()
        val secondary = secondaryText
            ?.trim()
            ?.removeSuffix(", ישראל")
            ?.removeSuffix(", Israel")
            ?.trim()
            .orEmpty()
        return listOf(main, secondary)
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifBlank { mainText }
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

    /** One-tap clear for the origin text field: drops the typed text, placeId, coords and suggestions. */
    fun clearOrigin() {
        _uiState.update {
            it.copy(
                origin = "",
                originPlaceId = null,
                originLocation = null,
                originAddress = null,
                originIsCurrentLocation = false,
                originSuggestions = emptyList(),
                locationError = false,
                error = null,
            )
        }
        originQuery.value = ""
    }

    /** One-tap clear for the destination text field: drops the typed text, placeId, coords and suggestions. */
    fun clearDestination() {
        _uiState.update {
            it.copy(
                destination = "",
                destinationPlaceId = null,
                destinationLocation = null,
                destinationSuggestions = emptyList(),
                error = null,
            )
        }
        destinationQuery.value = ""
    }

    fun selectResult(index: Int) {
        _uiState.update { it.copy(selectedIndex = index) }
        persistSelection(index)
    }

    /**
     * "יצאתי במסלול הזה": manually links the most recent unlinked trip to the search
     * the user is looking at, so it can be compared even if auto-linking missed it.
     */
    fun markDeparted() {
        val searchId = lastSearchId ?: return
        viewModelScope.launch {
            val linked = tripLinker.linkLatestUnlinkedTrip(searchId)
            _uiState.update {
                it.copy(
                    departLinkFeedback = if (linked != null) {
                        R.string.route_departed_linked
                    } else {
                        R.string.route_departed_none
                    },
                )
            }
        }
    }

    fun clearDepartLinkFeedback() {
        _uiState.update { it.copy(departLinkFeedback = null) }
    }

    private fun persistSelection(index: Int) {
        val searchId = lastSearchId ?: return
        val state = _uiState.value
        val cost = state.results.getOrNull(index) ?: return
        viewModelScope.launch {
            routeSearchRepository.updateSelection(
                id = searchId,
                selectedRouteIndex = index,
                selectedPredictedCost = cost.totalCost,
                selectedPredictedLiters = cost.fuelLiters,
                selectedPredictedMinutes = cost.durationMinutes,
                pricePerLiterAtSearch = state.fuelPricePerLiter,
                destinationPlaceId = state.destinationPlaceId,
                destinationLat = state.destinationLocation?.latitude,
                destinationLng = state.destinationLocation?.longitude,
            )
        }
    }

    /** null = "now" (the departureTime field is omitted). Past values are clamped to now. */
    fun onDepartureTimeChange(epochMs: Long?) {
        val clamped = epochMs?.takeIf { it > System.currentTimeMillis() }
        _uiState.update { it.copy(departureTimeMs = clamped, error = null) }
    }

    fun compute() = runSearch(forceRefresh = false)

    /** "רענן" — bypasses the routes cache. */
    fun refresh() = runSearch(forceRefresh = true)

    private fun runSearch(forceRefresh: Boolean) {
        val state = _uiState.value
        val origin = state.origin.trim()
        val destination = state.destination.trim()
        val originSet = state.originIsCurrentLocation || origin.isNotBlank()
        if (!originSet || destination.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), error = null) }
            return
        }

        searchJob?.cancel()
        // Results of the previous search are gone; its id must not receive this search's
        // selection or departure link.
        lastSearchId = null
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, results = emptyList()) }
            try {
                val vehicle = vehicleRepository.active()
                val settings = settingsRepository.settings.first()
                val overrides = settingsRepository.modelOverrides.first()
                val learned = learnedCurveRepository.learnedCurve(vehicle.id)
                val default = DefaultCurve.forVehicle(vehicle.ratedCombinedL100, vehicle.fuelType)
                val fallback = effectiveFallback(vehicle.manualCurve, default)
                val curve = CurveBlender.blend(learned, fallback)
                val fuelModel = FuelModel(
                    curve = curve,
                    idleLitersPerHour = learned.idleLitersPerHour ?: overrides.effectiveIdleLphDefault,
                    overrides = overrides,
                )

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

                val options = RouteRequestOptions(
                    departureTimeMs = state.departureTimeMs,
                    emissionType = vehicle.fuelType.toEmissionType(),
                )
                val routes = routesRepository.getAlternatives(
                    origin = originWaypoint,
                    destination = destinationWaypoint,
                    options = options,
                    forceRefresh = forceRefresh,
                )
                val notice = RoutesError.fromRoutes(routes)
                val fuelPrice = fuelPriceRepository.current(vehicle.grade).pricePerLiter
                val coldStartStats = coldStartRepository.stats(vehicle.id)
                val ranked = RouteRanker.rank(
                    routes.map {
                        fuelModel.cost(it, fuelPrice, coldStartLiters = coldStartStats.effectiveExtraL)
                    },
                    valuePerMinute = settings.valuePerMinute,
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        results = ranked,
                        selectedIndex = 0,
                        departureTimeMs = state.departureTimeMs,
                        learnedKm = learned.totalDistanceKm,
                        fuelPricePerLiter = fuelPrice,
                        error = notice,
                    )
                }

                if (state.destinationPlaceId != null || destination.isNotBlank()) {
                    placesHistoryRepository.add(
                        RecentPlace(
                            label = destination,
                            placeId = state.destinationPlaceId,
                            latitude = state.destinationLocation?.latitude,
                            longitude = state.destinationLocation?.longitude,
                        ),
                    )
                }

                ranked.firstOrNull()?.let { cheapest ->
                    val fastest = ranked.minByOrNull { it.durationMinutes }
                    if (fastest != null) {
                        lastSearchId = routeSearchRepository.add(
                            RouteSearch(
                                originLabel = origin,
                                destinationLabel = destination,
                                timestampMs = System.currentTimeMillis(),
                                cheapestCost = cheapest.totalCost,
                                savedAmount = (fastest.totalCost - cheapest.totalCost).coerceAtLeast(0.0),
                                predictedLiters = cheapest.fuelLiters,
                                distanceKm = cheapest.distanceKm,
                                durationMin = cheapest.durationMinutes,
                                selectedRouteIndex = 0,
                                departureTimeMs = state.departureTimeMs,
                                tollUnknown = cheapest.route.tollUnknown,
                                selectedPredictedCost = cheapest.totalCost,
                                selectedPredictedLiters = cheapest.fuelLiters,
                                selectedPredictedMinutes = cheapest.durationMinutes,
                                pricePerLiterAtSearch = fuelPrice,
                                destinationPlaceId = state.destinationPlaceId,
                                destinationLat = state.destinationLocation?.latitude,
                                destinationLng = state.destinationLocation?.longitude,
                            ),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val mapped = RoutesError.from(e)
                Log.w("FuelRoute", "route search failed: ${mapped.javaClass.simpleName}", e)
                _uiState.update { it.copy(isLoading = false, error = mapped) }
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