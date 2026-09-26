package com.fuelroute.ui.route

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star as StarOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.R
import com.fuelroute.data.places.FavoriteDestination
import com.fuelroute.data.places.FavoritesRepository
import com.fuelroute.data.places.PlaceSuggestion
import com.fuelroute.data.places.RecentPlace
import com.fuelroute.data.routes.RoutesError
import com.fuelroute.data.settings.NAV_GOOGLE
import com.fuelroute.data.settings.NAV_WAZE
import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteCost
import com.fuelroute.domain.nav.NavPlan
import com.fuelroute.nav.NavDestination
import com.fuelroute.nav.NavigationLauncher
import com.fuelroute.ui.components.Dimens
import com.fuelroute.ui.components.ErrorCard
import com.fuelroute.ui.components.FuelTopBar
import com.fuelroute.ui.components.ListRow
import com.fuelroute.ui.components.PillTone
import com.fuelroute.ui.components.PrimaryButton
import com.fuelroute.ui.components.SecondaryButton
import com.fuelroute.ui.components.SectionCard
import com.fuelroute.ui.components.SectionTitle
import com.fuelroute.ui.components.StatusPill
import com.fuelroute.ui.components.fmt
import com.fuelroute.ui.components.formatTime
import com.fuelroute.ui.components.money
import com.fuelroute.ui.favorites.FavoritesScreen
import com.fuelroute.ui.theme.FuelTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Route tab. One job: destination in → cheapest route out → navigate.
 *
 * Layout, top to bottom:
 *  - search card (origin, destination, departure chip, one primary "compute" button) — collapses
 *    to a one-line summary once results arrive;
 *  - quick destinations (favorites then recents, one horizontal row) while nothing is shown;
 *  - results: the map, one hero card for the selected (by default recommended) route with its
 *    savings vs. the fastest and a one-tap Navigate, then a compact list of all alternatives.
 * Cost breakdown, speed graph and segments live in [RouteDetailSheet].
 */
@Composable
fun RouteScreen(
    modifier: Modifier = Modifier,
    onOpenHistory: () -> Unit = {},
    viewModel: RouteViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDetail by rememberSaveable { mutableStateOf(false) }
    var showManageDestinations by rememberSaveable { mutableStateOf(false) }
    var editingSearch by rememberSaveable { mutableStateOf(false) }
    var autoLocated by rememberSaveable { mutableStateOf(false) }

    val destinationIsFavorite = state.favorites.any { favorite ->
        FavoritesRepository.matches(favorite, state.destinationPlaceId, state.destination)
    }
    val visibleRecents = state.history.filterNot { place ->
        state.favorites.any { favorite -> FavoritesRepository.matches(favorite, place.placeId, place.label) }
    }
    val departureMs = state.departureTimeMs ?: System.currentTimeMillis()
    val selectedCost = state.results.getOrNull(state.selectedIndex)
    val hasResults = state.results.isNotEmpty()
    val canCompute = !state.isLoading &&
        (state.originIsCurrentLocation || state.origin.isNotBlank()) &&
        state.destination.isNotBlank()

    val departLinkFeedback = state.departLinkFeedback
    LaunchedEffect(departLinkFeedback) {
        departLinkFeedback?.let { resId ->
            Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
            viewModel.clearDepartLinkFeedback()
        }
    }
    // A fresh result set always lands on the results view, not on the edit form.
    LaunchedEffect(state.results) {
        if (state.results.isNotEmpty()) editingSearch = false
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.useCurrentLocation()
    }
    val onCurrentLocationClick = {
        if (context.hasFineLocation()) {
            viewModel.useCurrentLocation()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    // Most trips start "here": pre-fill the origin once, but only when location access was
    // already granted (never pop a permission prompt on screen open).
    LaunchedEffect(Unit) {
        if (!autoLocated && !state.originIsCurrentLocation && state.origin.isBlank() &&
            context.hasFineLocation()
        ) {
            autoLocated = true
            viewModel.useCurrentLocation()
        }
    }

    val navScope = rememberCoroutineScope()
    var navPreparing by remember { mutableStateOf(false) }
    var wazePrompt by remember { mutableStateOf<PendingWazeHandOff?>(null) }

    val navigate: (Int) -> Unit = navigate@{ index ->
        if (navPreparing) return@navigate
        val cost = state.results.getOrNull(index) ?: return@navigate
        if (index != state.selectedIndex) viewModel.selectResult(index)
        // The API's default route needs no planning; an alternative asks the Routes API which few
        // waypoints make Maps follow it, which takes a moment.
        val needsPlanning = cost.route.id != DEFAULT_ROUTE_ID
        if (needsPlanning) {
            navPreparing = true
            Toast.makeText(context, R.string.route_nav_preparing, Toast.LENGTH_SHORT).show()
        }
        navScope.launch {
            val plan = if (needsPlanning) viewModel.planNavigation(cost) else NavPlan(emptyList(), exact = true)
            navPreparing = false
            if (state.navigationApp == NAV_WAZE && plan.waypoints.isNotEmpty()) {
                // Waze cannot follow a chosen route: let the driver decide instead of silently
                // navigating a different route than the one that was recommended.
                wazePrompt = PendingWazeHandOff(state, plan)
            } else {
                launchNavigation(context, state, plan)
            }
        }
    }

    wazePrompt?.let { pending ->
        AlertDialog(
            onDismissRequest = { wazePrompt = null },
            title = { Text(stringResource(R.string.route_waze_prompt_title)) },
            text = { Text(stringResource(R.string.route_waze_prompt_body)) },
            confirmButton = {
                TextButton(onClick = {
                    wazePrompt = null
                    launchNavigation(context, pending.state.copy(navigationApp = NAV_GOOGLE), pending.plan)
                }) { Text(stringResource(R.string.route_waze_prompt_google)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    wazePrompt = null
                    launchNavigation(context, pending.state, NavPlan(emptyList(), exact = true))
                }) { Text(stringResource(R.string.route_waze_prompt_waze)) }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // The Scaffold already pads the NavHost for the system bars; consume them here so
            // imePadding() below doesn't add the navigation-bar height a second time.
            .consumeWindowInsets(WindowInsets.systemBars),
    ) {
        FuelTopBar(
            title = stringResource(R.string.route_title),
            actions = {
                IconButton(onClick = onOpenHistory) {
                    Icon(
                        painter = painterResource(R.drawable.ic_history),
                        contentDescription = stringResource(R.string.route_open_history),
                    )
                }
            },
        )

        if (hasResults) {
            RouteMapHeader(state = state)
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding(),
            contentPadding = PaddingValues(start = Dimens.l, end = Dimens.l, bottom = Dimens.xl, top = Dimens.s),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            if (!hasResults || editingSearch) {
                item(key = "search") {
                    SearchCard(
                        state = state,
                        destinationIsFavorite = destinationIsFavorite,
                        canCompute = canCompute,
                        onCurrentLocationClick = onCurrentLocationClick,
                        viewModel = viewModel,
                    )
                }
            } else {
                item(key = "summary") {
                    SearchSummaryCard(state = state, onEdit = { editingSearch = true })
                }
            }

            if (state.isLoading) {
                item(key = "loading") { LoadingRow() }
            }

            state.error?.takeIf { it != RoutesError.SingleRouteOnly }?.let { error ->
                item(key = "error") {
                    ErrorCard(
                        message = stringResource(error.messageRes()),
                        hint = error.actionRes()?.takeUnless { error.isRetryable() }?.let { stringResource(it) },
                        onRetry = if (error.isRetryable()) viewModel::refresh else null,
                    )
                }
            }

            if (hasResults && selectedCost != null) {
                item(key = "hero") {
                    SelectedRouteCard(
                        cost = selectedCost,
                        index = state.selectedIndex,
                        comparison = RouteHighlights.compare(state.results, state.selectedIndex),
                        departureMs = departureMs,
                        onNavigate = { navigate(state.selectedIndex) },
                        onDetails = { showDetail = true },
                    )
                }
                if (state.results.size > 1) {
                    item(key = "all") {
                        AllRoutesCard(
                            results = state.results,
                            selectedIndex = state.selectedIndex,
                            onSelect = viewModel::selectResult,
                        )
                    }
                }
                item(key = "footer") {
                    ResultsFooter(
                        pricePerLiter = state.fuelPricePerLiter,
                        learnedKm = state.learnedKm,
                        singleRouteOnly = state.error == RoutesError.SingleRouteOnly,
                        refreshEnabled = canCompute,
                        onRefresh = viewModel::refresh,
                    )
                }
            }

            if (!hasResults && !state.isLoading && state.destination.isBlank()) {
                item(key = "quick") {
                    QuickDestinations(
                        favorites = state.favorites,
                        recents = visibleRecents,
                        onFavorite = viewModel::onFavoriteSelected,
                        onRecent = { place ->
                            viewModel.onRecentSelected(place)
                            viewModel.compute()
                        },
                        onManage = { showManageDestinations = true },
                    )
                }
            }
        }
    }

    if (showDetail && selectedCost != null) {
        RouteDetailSheet(
            cost = selectedCost,
            index = state.selectedIndex,
            comparison = RouteHighlights.compare(state.results, state.selectedIndex),
            departureMs = departureMs,
            isWaze = state.navigationApp == NAV_WAZE,
            learnedKm = state.learnedKm,
            hasManualCurve = state.hasManualCurve,
            fuelCorrectionFactor = state.fuelCorrectionFactor,
            onNavigate = { navigate(state.selectedIndex) },
            onDeparted = viewModel::markDeparted,
            onDismiss = { showDetail = false },
        )
    }

    if (showManageDestinations) {
        FavoritesScreen(
            favorites = state.favorites,
            recents = visibleRecents,
            onRename = { favorite, label -> viewModel.renameFavorite(favorite.id, label) },
            onMoveUp = { favorite -> viewModel.moveFavorite(favorite.id, favorite.sortOrder - 1) },
            onMoveDown = { favorite -> viewModel.moveFavorite(favorite.id, favorite.sortOrder + 1) },
            onDelete = { favorite -> viewModel.removeFavorite(favorite.id) },
            onAddRecent = viewModel::onToggleRecentFavorite,
            onDismiss = { showManageDestinations = false },
        )
    }
}

private fun Context.hasFineLocation(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** State captured when the driver has to choose between Waze and an exact Google Maps route. */
private data class PendingWazeHandOff(val state: RouteUiState, val plan: NavPlan)

/**
 * Hands the chosen route to Google Maps (the planned waypoints, usually none or one) or Waze
 * (destination only, Waze picks its own route).
 */
private fun launchNavigation(context: Context, state: RouteUiState, plan: NavPlan) {
    val originLocation = state.originLocation
    val origin = if (state.originIsCurrentLocation && originLocation != null) {
        NavDestination(
            label = state.originAddress.orEmpty(),
            latitude = originLocation.latitude,
            longitude = originLocation.longitude,
        )
    } else {
        NavDestination(
            label = state.origin,
            placeId = state.originPlaceId,
            latitude = originLocation?.latitude,
            longitude = originLocation?.longitude,
        )
    }
    val destination = NavDestination(
        label = state.destination,
        placeId = state.destinationPlaceId,
        latitude = state.destinationLocation?.latitude,
        longitude = state.destinationLocation?.longitude,
    )
    val fromHere = state.originIsCurrentLocation && originLocation != null
    if (state.navigationApp == NAV_WAZE) {
        NavigationLauncher.openWaze(context, destination, origin, fromHere)
    } else {
        NavigationLauncher.openGoogleMaps(
            context = context,
            destination = destination,
            origin = origin,
            startsFromCurrentLocation = fromHere,
            waypoints = plan.waypoints.map { it.lat to it.lng },
        )
        when {
            !plan.exact -> Toast.makeText(context, R.string.route_nav_approx, Toast.LENGTH_LONG).show()
            plan.waypoints.isNotEmpty() -> Toast.makeText(
                context,
                context.getString(R.string.route_nav_via_points, plan.waypoints.size),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------------------------

@Composable
private fun SearchCard(
    state: RouteUiState,
    destinationIsFavorite: Boolean,
    canCompute: Boolean,
    onCurrentLocationClick: () -> Unit,
    viewModel: RouteViewModel,
) {
    SectionCard {
        if (state.originIsCurrentLocation) {
            ListRow(
                title = stringResource(R.string.route_current_location_set),
                subtitle = state.originAddress?.takeIf { it.isNotBlank() },
                leading = { Icon(painterResource(R.drawable.ic_my_location), contentDescription = null) },
                trailing = { ClearFieldButton(onClick = viewModel::onClearOriginLocation) },
            )
        } else {
            PlaceField(
                value = state.origin,
                onValueChange = viewModel::onOriginChange,
                onSelect = viewModel::onOriginSelect,
                suggestions = state.originSuggestions,
                label = stringResource(R.string.route_origin_label),
                trailingIcon = {
                    if (state.origin.isNotBlank()) {
                        ClearFieldButton(onClick = viewModel::clearOrigin)
                    } else {
                        IconButton(onClick = onCurrentLocationClick) {
                            Icon(
                                painter = painterResource(R.drawable.ic_my_location),
                                contentDescription = stringResource(R.string.route_use_current_location),
                            )
                        }
                    }
                },
            )
        }
        if (state.locationError) {
            Text(
                text = stringResource(R.string.route_location_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        PlaceField(
            value = state.destination,
            onValueChange = viewModel::onDestinationChange,
            onSelect = viewModel::onDestinationSelect,
            suggestions = state.destinationSuggestions,
            label = stringResource(R.string.route_destination_label),
            imeAction = ImeAction.Search,
            onImeAction = { if (canCompute) viewModel.compute() },
            trailingIcon = if (state.destination.isNotBlank()) {
                {
                    Row {
                        IconButton(onClick = viewModel::onToggleDestinationFavorite) {
                            Icon(
                                imageVector = if (destinationIsFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                contentDescription = stringResource(
                                    if (destinationIsFavorite) R.string.route_favorite_remove else R.string.route_favorite_add,
                                ),
                                tint = if (destinationIsFavorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        ClearFieldButton(onClick = viewModel::clearDestination)
                    }
                }
            } else {
                null
            },
        )

        DepartureChip(
            departureTimeMs = state.departureTimeMs,
            onChange = viewModel::onDepartureTimeChange,
        )

        PrimaryButton(
            text = stringResource(R.string.route_compute),
            onClick = viewModel::compute,
            enabled = canCompute,
        )
    }
}

/** Collapsed search once results are showing: "to X · from Y · leave now" + change. */
@Composable
private fun SearchSummaryCard(state: RouteUiState, onEdit: () -> Unit) {
    val from = if (state.originIsCurrentLocation) {
        stringResource(R.string.route_current_location_set)
    } else {
        state.origin
    }
    val departure = state.departureTimeMs?.let { formatDateTime(it) }
        ?: stringResource(R.string.route_departure_now)
    SectionCard(contentPadding = Dimens.s) {
        ListRow(
            title = state.destination,
            subtitle = stringResource(R.string.route_search_summary, from, departure),
            leading = { Icon(painterResource(R.drawable.ic_navigation), contentDescription = null) },
            trailing = {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.route_search_change)) }
            },
            onClick = onEdit,
            modifier = Modifier.padding(horizontal = Dimens.s),
        )
    }
}

@Composable
private fun DepartureChip(
    departureTimeMs: Long?,
    onChange: (Long?) -> Unit,
) {
    val context = LocalContext.current
    val label = departureTimeMs?.let { formatDateTime(it) } ?: stringResource(R.string.route_departure_now)
    val openPicker = {
        val initial = Calendar.getInstance().apply {
            timeInMillis = departureTimeMs ?: System.currentTimeMillis()
        }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        val picked = Calendar.getInstance().apply {
                            set(year, month, day, hour, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        onChange(picked.timeInMillis)
                    },
                    initial.get(Calendar.HOUR_OF_DAY),
                    initial.get(Calendar.MINUTE),
                    true,
                ).show()
            },
            initial.get(Calendar.YEAR),
            initial.get(Calendar.MONTH),
            initial.get(Calendar.DAY_OF_MONTH),
        ).show()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AssistChip(
            onClick = openPicker,
            label = { Text(stringResource(R.string.route_departure_label, label)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_schedule),
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
            modifier = Modifier.heightIn(min = 40.dp),
        )
        if (departureTimeMs != null) {
            IconButton(onClick = { onChange(null) }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.route_departure_reset),
                )
            }
        }
    }
}

@Composable
private fun ClearFieldButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.route_clear),
        )
    }
}

@Composable
private fun PlaceField(
    value: String,
    onValueChange: (String) -> Unit,
    onSelect: (PlaceSuggestion) -> Unit,
    suggestions: List<PlaceSuggestion>,
    label: String,
    trailingIcon: (@Composable () -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = trailingIcon,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onSearch = { onImeAction() }),
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoViewRequester)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        scope.launch {
                            // Let the IME start animating, then scroll the field back into view
                            // so it stays visible above the keyboard.
                            delay(IME_BRING_INTO_VIEW_DELAY_MS)
                            bringIntoViewRequester.bringIntoView()
                        }
                    }
                },
        )

        if (suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.xs),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                suggestions.forEachIndexed { index, suggestion ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ListRow(
                        title = suggestion.mainText,
                        subtitle = suggestion.secondaryText?.takeIf { it.isNotBlank() },
                        onClick = { onSelect(suggestion) },
                        modifier = Modifier.padding(horizontal = Dimens.l),
                    )
                }
            }
        }
    }
}

/**
 * Favorites first (one tap = search), then recents (one tap = search), in one horizontal row.
 * Rename/reorder/delete and "star a recent" live in the manage sheet, so the chips stay single
 * targets with no tiny nested icons.
 */
@Composable
private fun QuickDestinations(
    favorites: List<FavoriteDestination>,
    recents: List<RecentPlace>,
    onFavorite: (FavoriteDestination) -> Unit,
    onRecent: (RecentPlace) -> Unit,
    onManage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        SectionTitle(
            text = stringResource(R.string.route_quick_title),
            trailing = if (favorites.isNotEmpty() || recents.isNotEmpty()) {
                { TextButton(onClick = onManage) { Text(stringResource(R.string.route_favorites_manage)) } }
            } else {
                null
            },
        )
        if (favorites.isEmpty() && recents.isEmpty()) {
            Text(
                text = stringResource(R.string.route_favorites_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.xs),
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                items(favorites, key = { "f${it.id}" }) { favorite ->
                    QuickChip(
                        label = favorite.label,
                        icon = {
                            Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        onClick = { onFavorite(favorite) },
                    )
                }
                items(recents) { place ->
                    QuickChip(
                        label = place.label,
                        icon = { Icon(painterResource(R.drawable.ic_history), contentDescription = null) },
                        onClick = { onRecent(place) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickChip(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = label.substringBefore(","),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = Dimens.s),
            )
        },
        leadingIcon = { Box(Modifier.size(AssistChipDefaults.IconSize)) { icon() } },
        modifier = Modifier.heightIn(min = Dimens.touchTarget),
    )
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.l),
        horizontalArrangement = Arrangement.spacedBy(Dimens.m, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        Text(stringResource(R.string.route_loading), style = MaterialTheme.typography.bodyLarge)
    }
}

// ---------------------------------------------------------------------------------------------
// Results
// ---------------------------------------------------------------------------------------------

@Composable
private fun RouteMapHeader(state: RouteUiState) {
    // Responsive header: scales with the window and shrinks as more alternatives arrive.
    val windowHeightDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val fraction = when (state.results.size) {
        1 -> 0.30f
        2 -> 0.28f
        else -> 0.26f
    }
    val mapHeight = (windowHeightDp * fraction).coerceIn(MinMapHeight, MaxMapHeight)
    val mapDescription = stringResource(R.string.route_map_content_description)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.l)
            .height(mapHeight)
            .semantics { contentDescription = mapDescription },
        shape = MaterialTheme.shapes.large,
    ) {
        RouteMap(
            routes = state.results.map { it.route },
            selectedIndex = state.selectedIndex,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The hero: the route currently selected (the recommended one by default) with the one number
 * that matters, why it was picked, and Navigate.
 */
@Composable
private fun SelectedRouteCard(
    cost: RouteCost,
    index: Int,
    comparison: RouteComparison?,
    departureMs: Long,
    onNavigate: () -> Unit,
    onDetails: () -> Unit,
) {
    val etaMs = departureMs + (cost.durationMinutes * 60_000.0).toLong()
    SectionCard {
        RouteTitleRow(cost = cost, index = index, comparison = comparison, recommended = index == 0)
        RouteHeadline(cost = cost, comparison = comparison, etaMs = etaMs)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            PrimaryButton(
                text = stringResource(R.string.route_navigate),
                onClick = onNavigate,
                icon = painterResource(R.drawable.ic_navigation),
                modifier = Modifier.weight(1.4f),
            )
            SecondaryButton(
                text = stringResource(R.string.route_details),
                onClick = onDetails,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Big total, "distance · ETA", the trade-off sentence and any toll note. */
@Composable
internal fun RouteHeadline(cost: RouteCost, comparison: RouteComparison?, etaMs: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
            Text(
                text = money(cost.totalCost),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = stringResource(R.string.route_duration_minutes, fmt(cost.durationMinutes, 0)),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Text(
            text = stringResource(R.string.route_meta, fmt(cost.distanceKm, 1), formatTime(etaMs)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        comparison?.let { TradeOffLine(it) }
        when {
            cost.route.tollUnknown -> Text(
                text = stringResource(R.string.route_toll_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            cost.tollCost > RouteHighlights.MONEY_EPSILON -> Text(
                text = stringResource(R.string.route_total_includes_toll, fmt(cost.tollCost, 2)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TradeOffLine(comparison: RouteComparison) {
    val (text, color) = when {
        comparison.noTradeOff ->
            stringResource(R.string.route_reason_both) to FuelTheme.colors.positive
        comparison.isCheapest && comparison.savingsVsFastest > RouteHighlights.MONEY_EPSILON ->
            stringResource(
                R.string.route_reason_saves,
                fmt(comparison.savingsVsFastest, 2),
                fmt(comparison.extraMinutesVsFastest, 0),
            ) to FuelTheme.colors.positive
        comparison.isFastest ->
            stringResource(
                R.string.route_reason_fastest,
                fmt(comparison.premiumVsCheapest, 2),
                fmt(comparison.minutesSavedVsCheapest, 0),
            ) to MaterialTheme.colorScheme.onSurface
        else ->
            stringResource(
                R.string.route_reason_tradeoff,
                fmt(comparison.premiumVsCheapest, 2),
                fmt(comparison.extraMinutesVsFastest, 0),
            ) to MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoutePills(comparison: RouteComparison?, recommended: Boolean) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
        verticalArrangement = Arrangement.spacedBy(Dimens.xs),
    ) {
        if (recommended) {
            StatusPill(text = stringResource(R.string.route_recommended), tone = PillTone.Positive)
        }
        if (comparison?.isCheapest == true && !recommended) {
            StatusPill(text = stringResource(R.string.route_cheapest), tone = PillTone.Positive)
        }
        if (comparison?.isFastest == true) {
            StatusPill(text = stringResource(R.string.route_fastest), tone = PillTone.Accent)
        }
    }
}

/** Every alternative as one compact, selectable row, numbered/coloured like the map. */
@Composable
private fun AllRoutesCard(
    results: List<RouteCost>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    SectionCard(title = stringResource(R.string.route_all_routes), contentPadding = Dimens.s) {
        Column {
            results.forEachIndexed { index, cost ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val selected = index == selectedIndex
                val comparison = RouteHighlights.compare(results, index)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.rowHeight)
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            MaterialTheme.shapes.medium,
                        )
                        .clickable { onSelect(index) }
                        .padding(horizontal = Dimens.m, vertical = Dimens.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.m),
                ) {
                    RouteNumberDot(index = index)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(cost.route.routeLabelRes()),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = compactDelta(comparison),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = money(cost.totalCost), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(R.string.route_duration_minutes, fmt(cost.durationMinutes, 0)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** "הזול ביותר · +4 דק׳" / "+₪2.10 · המהיר ביותר" for the compact list row. */
@Composable
private fun compactDelta(comparison: RouteComparison?): String {
    if (comparison == null) return ""
    val cost = if (comparison.isCheapest) {
        stringResource(R.string.route_cheapest)
    } else {
        stringResource(R.string.route_delta_cost_short, fmt(comparison.premiumVsCheapest, 2))
    }
    val time = if (comparison.isFastest) {
        stringResource(R.string.route_fastest)
    } else {
        stringResource(R.string.route_delta_time_short, fmt(comparison.extraMinutesVsFastest, 0))
    }
    return "$cost · $time"
}

@Composable
private fun ResultsFooter(
    pricePerLiter: Double,
    learnedKm: Double,
    singleRouteOnly: Boolean,
    refreshEnabled: Boolean,
    onRefresh: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Dimens.xs),
        ) {
            if (singleRouteOnly) {
                Text(
                    text = stringResource(R.string.route_single),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.route_fuel_price, fmt(pricePerLiter, 2)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    if (learnedKm > 0.0) R.string.route_based_on else R.string.route_default_curve,
                    fmt(learnedKm, 0),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRefresh, enabled = refreshEnabled) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.route_refresh),
                modifier = Modifier.padding(start = Dimens.xs),
            )
        }
    }
}

/** Filled circle with the route number, in the same colour as its polyline on the map. */
@Composable
internal fun RouteNumberDot(index: Int) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(routeColor(index), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${index + 1}",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@StringRes
private fun RoutesError.messageRes(): Int = when (this) {
    RoutesError.NoNetwork -> R.string.route_error_no_network
    RoutesError.Timeout -> R.string.route_error_timeout
    RoutesError.Quota -> R.string.route_error_quota
    RoutesError.Forbidden -> R.string.route_error_forbidden
    RoutesError.NoRoute -> R.string.route_error_no_route
    RoutesError.SingleRouteOnly -> R.string.route_single
    is RoutesError.Invalid -> R.string.route_error_invalid
    is RoutesError.Parse -> R.string.route_error_parse
    is RoutesError.Unknown -> R.string.route_error_unknown
}

@StringRes
private fun RoutesError.actionRes(): Int? = when (this) {
    RoutesError.NoNetwork -> R.string.route_error_action_retry
    RoutesError.Timeout -> R.string.route_error_action_retry
    RoutesError.Quota -> R.string.route_error_action_wait
    RoutesError.Forbidden -> R.string.route_error_action_check_key
    RoutesError.NoRoute -> R.string.route_error_action_change_time
    RoutesError.SingleRouteOnly -> null
    is RoutesError.Invalid -> R.string.route_error_action_change_time
    is RoutesError.Parse -> R.string.route_error_action_retry
    is RoutesError.Unknown -> R.string.route_error_action_retry
}

/** Whether the error means "just try the same search again" (gets a real Retry button). */
private fun RoutesError.isRetryable(): Boolean = when (this) {
    RoutesError.NoNetwork,
    RoutesError.Timeout,
    is RoutesError.Parse,
    is RoutesError.Unknown,
    -> true

    else -> false
}

@StringRes
internal fun Route.routeLabelRes(): Int = when {
    "FUEL_EFFICIENT" in routeLabels -> R.string.route_label_fuel_efficient
    "DEFAULT_ROUTE" in routeLabels -> R.string.route_label_primary
    else -> R.string.route_label_alternate
}

private fun formatDateTime(epochMs: Long): String =
    SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(epochMs))

/** Delay before re-scrolling a focused input into view, letting the IME animation start. */
private const val IME_BRING_INTO_VIEW_DELAY_MS = 150L

/** Bounds for the responsive route-map header so it neither disappears nor dominates the page. */
private val MinMapHeight = 160.dp
private val MaxMapHeight = 280.dp
