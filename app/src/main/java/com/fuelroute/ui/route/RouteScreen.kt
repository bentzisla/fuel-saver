package com.fuelroute.ui.route

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star as StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.R
import com.fuelroute.data.places.FavoriteDestination
import com.fuelroute.data.places.FavoritesRepository
import com.fuelroute.data.places.PlaceSuggestion
import com.fuelroute.data.places.RecentPlace
import com.fuelroute.data.routes.RoutesError
import com.fuelroute.ui.favorites.FavoritesScreen
import com.fuelroute.data.settings.NAV_WAZE
import com.fuelroute.domain.model.CongestionLevel
import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteCost
import com.fuelroute.domain.model.SegmentCost
import com.fuelroute.domain.model.TrafficResolution
import com.fuelroute.domain.ranking.RouteInsights
import com.fuelroute.nav.NavDestination
import com.fuelroute.nav.NavigationLauncher
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RouteScreen(
    modifier: Modifier = Modifier,
    onOpenHistory: () -> Unit = {},
    viewModel: RouteViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var detailIndex by remember { mutableStateOf<Int?>(null) }
    var editingFavorite by remember { mutableStateOf<FavoriteDestination?>(null) }
    var showManageFavorites by remember { mutableStateOf(false) }
    var showSpeedGraph by remember { mutableStateOf(false) }
    val badges = RouteInsights.badges(state.results)
    val destinationIsFavorite = state.favorites.any { favorite ->
        FavoritesRepository.matches(favorite, state.destinationPlaceId, state.destination)
    }
    val visibleRecents = state.history.filterNot { place ->
        state.favorites.any { favorite -> FavoritesRepository.matches(favorite, place.placeId, place.label) }
    }
    val departureMs = state.departureTimeMs ?: System.currentTimeMillis()
    val selectedCost = state.results.getOrNull(state.selectedIndex)

    val context = LocalContext.current
    val departLinkFeedback = state.departLinkFeedback
    LaunchedEffect(departLinkFeedback) {
        departLinkFeedback?.let { resId ->
            Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
            viewModel.clearDepartLinkFeedback()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.useCurrentLocation()
    }
    val onCurrentLocationClick = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.useCurrentLocation() else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // The Scaffold already pads the NavHost for the system bars; consume them here so
            // imePadding() below doesn't add the navigation-bar height a second time (the
            // "black gap above the keyboard" double-inset bug).
            .consumeWindowInsets(WindowInsets.systemBars),
    ) {
        if (state.results.isNotEmpty()) {
            // Responsive map header: scales with the screen and shrinks as more result cards
            // arrive, so it frames the routes without crowding the list.
            val screenHeightDp = LocalConfiguration.current.screenHeightDp
            val mapHeight = (screenHeightDp * when (state.results.size) {
                1 -> 0.34f
                2 -> 0.32f
                else -> 0.28f
            }).dp.coerceIn(MinMapHeight, MaxMapHeight)
            val mapDescription = stringResource(R.string.route_map_content_description)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .height(mapHeight)
                    .semantics { contentDescription = mapDescription },
                shape = MaterialTheme.shapes.medium,
            ) {
                RouteMap(
                    routes = state.results.map { it.route },
                    selectedIndex = state.selectedIndex,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Lift the list (and the focused input) above the IME. The parent Column has
                // already consumed the system bars, so only the keyboard height is added.
                .imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.route_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            item {
                if (state.originIsCurrentLocation) {
                    CurrentLocationCard(
                        address = state.originAddress,
                        onClear = viewModel::onClearOriginLocation,
                    )
                } else {
                    Column {
                        PlaceField(
                            value = state.origin,
                            onValueChange = viewModel::onOriginChange,
                            onSelect = viewModel::onOriginSelect,
                            suggestions = state.originSuggestions,
                            label = stringResource(R.string.route_origin_label),
                            trailingIcon = if (state.origin.isNotBlank()) {
                                { ClearFieldButton(onClick = viewModel::clearOrigin) }
                            } else {
                                null
                            },
                        )
                        TextButton(onClick = onCurrentLocationClick) {
                            Text(stringResource(R.string.route_use_current_location))
                        }
                    }
                }
                if (state.locationError) {
                    Text(
                        text = stringResource(R.string.route_location_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item {
                PlaceField(
                    value = state.destination,
                    onValueChange = viewModel::onDestinationChange,
                    onSelect = viewModel::onDestinationSelect,
                    suggestions = state.destinationSuggestions,
                    label = stringResource(R.string.route_destination_label),
                    trailingIcon = if (state.destination.isNotBlank()) {
                        {
                            Row {
                                IconButton(onClick = viewModel::onToggleDestinationFavorite) {
                                    Icon(
                                        imageVector = if (destinationIsFavorite) {
                                            Icons.Filled.Star
                                        } else {
                                            Icons.Outlined.StarOutline
                                        },
                                        contentDescription = stringResource(
                                            if (destinationIsFavorite) {
                                                R.string.route_favorite_remove
                                            } else {
                                                R.string.route_favorite_add
                                            },
                                        ),
                                    )
                                }
                                ClearFieldButton(onClick = viewModel::clearDestination)
                            }
                        }
                    } else {
                        null
                    },
                )
            }

            if (state.destination.isBlank() || state.favorites.isNotEmpty()) {
                item {
                    FavoriteDestinations(
                        favorites = state.favorites,
                        onSelect = viewModel::onFavoriteSelected,
                        onEdit = { editingFavorite = it },
                        onManage = { showManageFavorites = true },
                    )
                }
            }

            if (state.destination.isBlank() && visibleRecents.isNotEmpty()) {
                item {
                    RecentDestinations(
                        history = visibleRecents,
                        onSelect = viewModel::onRecentSelected,
                        onToggleFavorite = viewModel::onToggleRecentFavorite,
                    )
                }
            }

            item {
                Column {
                    DeparturePicker(
                        departureTimeMs = state.departureTimeMs,
                        onChange = viewModel::onDepartureTimeChange,
                    )
                    Button(
                        onClick = viewModel::compute,
                        enabled = !state.isLoading &&
                            (state.originIsCurrentLocation || state.origin.isNotBlank()) &&
                            state.destination.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.route_compute))
                    }
                    TextButton(
                        onClick = viewModel::refresh,
                        enabled = !state.isLoading &&
                            (state.originIsCurrentLocation || state.origin.isNotBlank()) &&
                            state.destination.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.route_refresh))
                    }
                    Text(
                        text = stringResource(R.string.route_fuel_price, format(state.fuelPricePerLiter, 2)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    TextButton(
                        onClick = onOpenHistory,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.route_open_history))
                    }
                }
            }

            if (state.isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.route_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            state.error?.let { error ->
                item {
                    RouteErrorCard(error)
                }
            }

            if (state.results.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(
                            if (state.learnedKm > 0.0) R.string.route_based_on else R.string.route_default_curve,
                            format(state.learnedKm, 1),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    ResultsSummary(costs = state.results)
                }
            }

            selectedCost?.let { cost ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showSpeedGraph = !showSpeedGraph },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(
                                        if (showSpeedGraph) {
                                            R.string.route_graph_hide
                                        } else {
                                            R.string.route_graph_show
                                        },
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(
                                        if (showSpeedGraph) {
                                            R.string.route_graph_hide_hint
                                        } else {
                                            R.string.route_graph_tap_expand
                                        },
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = stringResource(R.string.route_graph_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (showSpeedGraph) {
                                Text(
                                    text = stringResource(
                                        R.string.route_graph_title_for,
                                        "${state.selectedIndex + 1}. ${stringResource(cost.route.routeLabelRes())}",
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                RouteSpeedGraph(cost = cost, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }

            itemsIndexed(state.results) { index, cost ->
                val cheapest = badges.cheapestIndex?.let { state.results[it] }
                val fastest = badges.fastestIndex?.let { state.results[it] }
                RouteCard(
                    cost = cost,
                    index = index,
                    isCheapest = badges.cheapestIndex == index,
                    isFastest = badges.fastestIndex == index,
                    cheapest = cheapest,
                    fastest = fastest,
                    departureMs = departureMs,
                    isSelected = index == state.selectedIndex,
                    onClick = {
                        viewModel.selectResult(index)
                        detailIndex = index
                    },
                )
            }
        }
    }

    val detail = detailIndex
    if (detail != null) {
        state.results.getOrNull(detail)?.let { cost ->
            val originLocation = state.originLocation
            val originNav = if (state.originIsCurrentLocation && originLocation != null) {
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
            val destinationNav = NavDestination(
                label = state.destination,
                placeId = state.destinationPlaceId,
                latitude = state.destinationLocation?.latitude,
                longitude = state.destinationLocation?.longitude,
            )
            RouteDetailDialog(
                cost = cost,
                origin = originNav,
                destination = destinationNav,
                navigationApp = state.navigationApp,
                onDeparted = viewModel::markDeparted,
                onDismiss = { detailIndex = null },
            )
        }
    }

    editingFavorite?.let { favorite ->
        FavoriteEditDialog(
            initialLabel = favorite.label,
            onDismiss = { editingFavorite = null },
            onRename = { newLabel ->
                viewModel.renameFavorite(favorite.id, newLabel)
                editingFavorite = null
            },
            onDelete = {
                viewModel.removeFavorite(favorite.id)
                editingFavorite = null
            },
        )
    }

    if (showManageFavorites) {
        FavoritesScreen(
            favorites = state.favorites,
            onMoveUp = { favorite -> viewModel.moveFavorite(favorite.id, favorite.sortOrder - 1) },
            onMoveDown = { favorite -> viewModel.moveFavorite(favorite.id, favorite.sortOrder + 1) },
            onDelete = { favorite -> viewModel.removeFavorite(favorite.id) },
            onDismiss = { showManageFavorites = false },
        )
    }
}

@Composable
private fun DeparturePicker(
    departureTimeMs: Long?,
    onChange: (Long?) -> Unit,
) {
    val context = LocalContext.current
    val label = if (departureTimeMs == null) {
        stringResource(R.string.route_departure_now)
    } else {
        formatDateTime(departureTimeMs)
    }

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

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = openPicker,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.route_departure_label, label))
        }
        TextButton(
            onClick = { onChange(null) },
            enabled = departureTimeMs != null,
        ) {
            Text(stringResource(R.string.route_departure_now))
        }
    }
}

@Composable
private fun RouteErrorCard(error: RoutesError) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(error.messageRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            error.actionRes()?.let { action ->
                Text(
                    text = stringResource(action),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@StringRes
private fun RoutesError.messageRes(): Int = when (this) {
    RoutesError.NoNetwork -> R.string.route_error_no_network
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
    RoutesError.Quota -> R.string.route_error_action_wait
    RoutesError.Forbidden -> R.string.route_error_action_check_key
    RoutesError.NoRoute -> R.string.route_error_action_change_time
    RoutesError.SingleRouteOnly -> null
    is RoutesError.Invalid -> R.string.route_error_action_change_time
    is RoutesError.Parse -> R.string.route_error_action_retry
    is RoutesError.Unknown -> R.string.route_error_action_retry
}

@StringRes
private fun TrafficResolution.labelRes(): Int = when (this) {
    TrafficResolution.PER_SEGMENT -> R.string.route_traffic_per_segment
    TrafficResolution.ROUTE_AVERAGE -> R.string.route_traffic_route_average
    TrafficResolution.NONE -> R.string.route_traffic_none
}

private fun formatDateTime(epochMs: Long): String =
    SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(epochMs))

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

@Composable
private fun CurrentLocationCard(
    address: String?,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.route_current_location_set),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (!address.isNullOrBlank()) {
                    Text(
                        text = address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.route_clear))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentDestinations(
    history: List<RecentPlace>,
    onSelect: (RecentPlace) -> Unit,
    onToggleFavorite: (RecentPlace) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.route_recent_destinations),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            history.forEach { place ->
                FilterChip(
                    selected = false,
                    onClick = { onSelect(place) },
                    label = { Text(place.label) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.StarOutline,
                            contentDescription = stringResource(R.string.route_favorite_add),
                            modifier = Modifier
                                .size(FilterChipDefaults.IconSize)
                                .clickable { onToggleFavorite(place) },
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FavoriteDestinations(
    favorites: List<FavoriteDestination>,
    onSelect: (FavoriteDestination) -> Unit,
    onEdit: (FavoriteDestination) -> Unit,
    onManage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.route_favorites_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (favorites.isNotEmpty()) {
                TextButton(onClick = onManage) {
                    Text(stringResource(R.string.route_favorites_manage))
                }
            }
        }
        if (favorites.isEmpty()) {
            Text(
                text = stringResource(R.string.route_favorites_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                favorites.forEach { favorite ->
                    FilterChip(
                        selected = false,
                        onClick = { onSelect(favorite) },
                        label = { Text(favorite.label) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.route_favorite_edit),
                                modifier = Modifier
                                    .size(FilterChipDefaults.IconSize)
                                    .clickable { onEdit(favorite) },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteEditDialog(
    initialLabel: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.route_favorite_rename_title)) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                singleLine = true,
                label = { Text(stringResource(R.string.route_favorite_label_label)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(label) },
                enabled = label.isNotBlank(),
            ) {
                Text(stringResource(R.string.route_favorite_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.route_favorite_delete))
            }
        },
    )
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
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoViewRequester)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        scope.launch {
                            // Let the IME start animating, then scroll the field back into view
                            // so it stays visible above the keyboard.
                            delay(ImeBringIntoViewDelayMs)
                            bringIntoViewRequester.bringIntoView()
                        }
                    }
                },
        )

        if (suggestions.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    suggestions.forEach { suggestion ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(suggestion) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Column {
                                Text(
                                    text = suggestion.mainText,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                if (!suggestion.secondaryText.isNullOrBlank()) {
                                    Text(
                                        text = suggestion.secondaryText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsSummary(costs: List<RouteCost>) {
    val badges = RouteInsights.badges(costs)
    val cheapest = badges.cheapestIndex?.let { costs[it] } ?: return
    val fastest = badges.fastestIndex?.let { costs[it] } ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(
                    R.string.route_cheapest_summary,
                    format(cheapest.totalCost, 2),
                    format(cheapest.durationMinutes, 0),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.route_fastest_summary,
                    format(fastest.durationMinutes, 0),
                    format(fastest.totalCost, 2),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (cheapest !== fastest) {
                Text(
                    text = stringResource(
                        R.string.route_tradeoff_summary,
                        format((fastest.totalCost - cheapest.totalCost).coerceAtLeast(0.0), 2),
                        format((cheapest.durationMinutes - fastest.durationMinutes).coerceAtLeast(0.0), 0),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RouteCard(
    cost: RouteCost,
    index: Int,
    isCheapest: Boolean,
    isFastest: Boolean,
    cheapest: RouteCost?,
    fastest: RouteCost?,
    departureMs: Long,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val reason = routeReason(cost, isCheapest, isFastest, cheapest, fastest)
    val etaMillis = departureMs + (cost.durationMinutes * 60_000.0).toLong()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}. ${stringResource(cost.route.routeLabelRes())}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isCheapest) {
                        Text(
                            text = stringResource(R.string.route_cheapest),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (isFastest) {
                        Text(
                            text = stringResource(R.string.route_fastest),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (isSelected) {
                        Text(
                            text = stringResource(R.string.route_selected),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "₪ ${format(cost.totalCost, 2)}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.route_duration_minutes, format(cost.durationMinutes, 0)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }

            if (costPremium(cost, cheapest) > 0.005) {
                Text(
                    text = stringResource(R.string.route_delta_cost, format(costPremium(cost, cheapest), 2)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (timePenalty(cost, fastest) > 0.5) {
                Text(
                    text = stringResource(R.string.route_delta_time, format(timePenalty(cost, fastest), 0)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoColumn(
                    label = stringResource(R.string.route_time_label),
                    value = "${Math.round(cost.durationMinutes)} ${stringResource(R.string.route_units_min)}",
                )
                InfoColumn(
                    label = stringResource(R.string.route_distance_label),
                    value = "${format(cost.distanceKm, 1)} ${stringResource(R.string.route_units_km)}",
                )
                InfoColumn(
                    label = stringResource(R.string.route_fuel_label),
                    value = "${format(cost.fuelLiters, 1)} L",
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoColumn(
                    label = stringResource(R.string.route_total_cost_label),
                    value = "₪ ${format(cost.totalCost, 2)}",
                )
                InfoColumn(
                    label = stringResource(R.string.route_toll_label),
                    value = when {
                        cost.route.tollUnknown -> "—"
                        cost.route.tollCost == null || cost.route.tollCost <= 0.005 ->
                            stringResource(R.string.route_toll_free)
                        else -> "₪ ${format(cost.tollCost, 1)}"
                    },
                )
                InfoColumn(
                    label = stringResource(R.string.route_eta_label),
                    value = formatTime(etaMillis),
                )
            }

            Text(
                text = stringResource(R.string.route_reason_line, reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (cost.route.tollUnknown) {
                    Text(
                        text = stringResource(R.string.route_toll_unknown),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.route_traffic_label,
                        stringResource(cost.route.trafficResolution.labelRes()),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun costPremium(cost: RouteCost, cheapest: RouteCost?): Double =
    cheapest?.let { (cost.totalCost - it.totalCost).coerceAtLeast(0.0) } ?: 0.0

private fun timePenalty(cost: RouteCost, fastest: RouteCost?): Double =
    fastest?.let { (cost.durationMinutes - it.durationMinutes).coerceAtLeast(0.0) } ?: 0.0

@Composable
private fun routeReason(
    cost: RouteCost,
    isCheapest: Boolean,
    isFastest: Boolean,
    cheapest: RouteCost?,
    fastest: RouteCost?,
): String {
    if (isCheapest && isFastest) return stringResource(R.string.route_reason_both)
    if (isCheapest) {
        val saved = fastest?.let { (it.totalCost - cost.totalCost).coerceAtLeast(0.0) } ?: 0.0
        val extra = timePenalty(cost, fastest)
        return stringResource(R.string.route_reason_saves, format(saved, 2), format(extra, 0))
    }
    if (isFastest) {
        val premium = costPremium(cost, cheapest)
        val savedTime = cheapest?.let { (it.durationMinutes - cost.durationMinutes).coerceAtLeast(0.0) } ?: 0.0
        return stringResource(R.string.route_reason_fastest, format(premium, 2), format(savedTime, 0))
    }
    return stringResource(
        R.string.route_reason_tradeoff,
        format(costPremium(cost, cheapest), 2),
        format(timePenalty(cost, fastest), 0),
    )
}

@Composable
private fun InfoColumn(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun format(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

@Composable
private fun RouteDetailDialog(
    cost: RouteCost,
    origin: NavDestination,
    destination: NavDestination,
    navigationApp: String,
    onDeparted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val pricePerLiter = if (cost.fuelLiters > 0.0) cost.fuelCost / cost.fuelLiters else 0.0
    var showSegments by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (navigationApp == NAV_WAZE) {
                        NavigationLauncher.openWaze(context, destination, origin)
                    } else {
                        NavigationLauncher.openGoogleMaps(context, destination, origin, cost.route.encodedPolyline)
                    }
                },
            ) {
                Text(stringResource(R.string.route_navigate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.route_close))
            }
        },
        title = {
            Column {
                Text(
                    text = stringResource(R.string.route_detail_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "₪ ${format(cost.totalCost, 2)}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = stringResource(R.string.route_duration_minutes, format(cost.durationMinutes, 0)),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Text(
                        text = stringResource(
                            R.string.route_detail_fuel,
                            format(cost.fuelLiters, 2),
                            format(pricePerLiter, 2),
                            format(cost.fuelCost, 2),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item {
                    Text(
                        text = when {
                            cost.route.tollUnknown -> stringResource(R.string.route_toll_unknown)
                            cost.route.tollCost == null || cost.route.tollCost <= 0.005 ->
                                stringResource(R.string.route_toll_free)
                            else -> stringResource(R.string.route_detail_toll, format(cost.tollCost, 2))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (cost.route.tollUnknown) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.route_detail_total, format(cost.totalCost, 2)),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }

                item {
                    OutlinedButton(
                        onClick = onDeparted,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.route_departed_button))
                    }
                }

                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }

                item {
                    Text(
                        text = stringResource(R.string.route_detail_graph_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                item {
                    RouteSpeedGraph(
                        cost = cost,
                        modifier = Modifier.fillMaxWidth(),
                        chartHeight = 180.dp,
                    )
                }

                item {
                    TextButton(
                        onClick = { showSegments = !showSegments },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (showSegments) {
                                stringResource(R.string.route_detail_hide_segments)
                            } else {
                                stringResource(R.string.route_detail_show_segments, cost.segments.size)
                            },
                        )
                    }
                }

                if (showSegments) {
                    item {
                        Text(
                            text = stringResource(R.string.route_detail_segments_title, cost.segments.size),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    items(cost.segments.size) { index ->
                        SegmentRow(index = index, segment = cost.segments[index])
                    }
                }
            }
        },
    )
}

@Composable
private fun SegmentRow(index: Int, segment: SegmentCost) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${format(segment.distanceKm, 1)} ${stringResource(R.string.route_units_km)} • " +
                    stringResource(segment.congestion.labelRes()),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "${format(segment.effectiveSpeedKmh, 0)} ${stringResource(R.string.route_units_kmh)} • " +
                    "${format(segment.litersPer100Km, 1)} ${stringResource(R.string.route_detail_l100)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${format(segment.liters, 2)} ${stringResource(R.string.route_detail_liters)}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private data class SpeedProfilePoint(val distanceKm: Double, val speedKmh: Double)

/** Cumulative-distance/speed profile for the route graph. */
private fun speedProfile(cost: RouteCost): List<SpeedProfilePoint> {
    if (cost.segments.isEmpty()) {
        return listOf(SpeedProfilePoint(cost.distanceKm.coerceAtLeast(0.0), cost.avgSpeedKmh))
    }
    var cumulative = 0.0
    return cost.segments.map { segment ->
        cumulative += segment.distanceKm
        SpeedProfilePoint(cumulative, segment.effectiveSpeedKmh)
    }
}

/** Delay before re-scrolling a focused input into view, letting the IME animation start. */
private const val ImeBringIntoViewDelayMs = 150L

private val GraphYAxisWidth = 44.dp
private val GraphChartHeight = 200.dp

/** Bounds for the responsive route-map header so it neither disappears nor dominates the page. */
private val MinMapHeight = 180.dp
private val MaxMapHeight = 320.dp

/** Rounds the graph's top speed to a friendly tick so the Y-axis labels stay readable. */
private fun niceSpeedMax(raw: Double): Double {
    val value = raw.coerceAtLeast(10.0)
    val step = when {
        value <= 30.0 -> 10.0
        value <= 60.0 -> 20.0
        value <= 120.0 -> 30.0
        else -> 50.0
    }
    return ceil(value / step) * step
}

/**
 * Readable speed-vs-distance graph: labelled km/h (Y) and km (X) axes, a congestion
 * colour band under a smoothed speed line, and a traffic legend.
 */
@Composable
private fun RouteSpeedGraph(
    cost: RouteCost,
    modifier: Modifier = Modifier,
    chartHeight: Dp = GraphChartHeight,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val points = remember(cost) { speedProfile(cost) }
    val maxDistance = (points.maxOfOrNull { it.distanceKm } ?: 0.0).coerceAtLeast(0.1)
    val maxSpeed = niceSpeedMax(points.maxOfOrNull { it.speedKmh } ?: 0.0)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Column(
                modifier = Modifier
                    .width(GraphYAxisWidth)
                    .height(chartHeight)
                    .padding(end = 4.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(format(maxSpeed, 0), style = MaterialTheme.typography.labelSmall, color = labelColor)
                Text(format(maxSpeed / 2.0, 0), style = MaterialTheme.typography.labelSmall, color = labelColor)
                Text("0", style = MaterialTheme.typography.labelSmall, color = labelColor)
            }
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(chartHeight),
            ) {
                if (points.isEmpty()) return@Canvas
                fun x(distanceKm: Double): Float = (distanceKm / maxDistance * size.width).toFloat()
                fun y(speedKmh: Double): Float =
                    (size.height - (speedKmh / maxSpeed).coerceIn(0.0, 1.0) * size.height).toFloat()

                // Vertical grid at 0/25/50/75/100 %.
                listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { fraction ->
                    drawLine(
                        color = gridColor.copy(alpha = 0.6f),
                        start = Offset(size.width * fraction.toFloat(), 0f),
                        end = Offset(size.width * fraction.toFloat(), size.height),
                        strokeWidth = 1f,
                    )
                }
                // Horizontal grid + baseline.
                listOf(0.0, 0.5, 1.0).forEach { fraction ->
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, size.height * fraction.toFloat()),
                        end = Offset(size.width, size.height * fraction.toFloat()),
                        strokeWidth = if (fraction == 1.0) 2f else 1f,
                    )
                }

                // Congestion colour band under the line, one quad per segment.
                for (index in 0 until points.size - 1) {
                    val start = points[index]
                    val end = points[index + 1]
                    val level = cost.segments.getOrNull(index + 1)?.congestion
                        ?: cost.segments.getOrNull(index)?.congestion
                        ?: CongestionLevel.NORMAL
                    val band = Path().apply {
                        moveTo(x(start.distanceKm), size.height)
                        lineTo(x(start.distanceKm), y(start.speedKmh))
                        lineTo(x(end.distanceKm), y(end.speedKmh))
                        lineTo(x(end.distanceKm), size.height)
                        close()
                    }
                    drawPath(path = band, color = level.graphColor().copy(alpha = 0.25f))
                }

                // Smoothed speed line.
                if (points.size == 1) {
                    drawCircle(
                        color = lineColor,
                        radius = 4.dp.toPx(),
                        center = Offset(x(points[0].distanceKm), y(points[0].speedKmh)),
                    )
                } else {
                    val line = Path().apply {
                        moveTo(x(points[0].distanceKm), y(points[0].speedKmh))
                        for (index in 1 until points.size - 1) {
                            val point = points[index]
                            val next = points[index + 1]
                            val midX = (x(point.distanceKm) + x(next.distanceKm)) / 2f
                            val midY = (y(point.speedKmh) + y(next.speedKmh)) / 2f
                            quadraticTo(x(point.distanceKm), y(point.speedKmh), midX, midY)
                        }
                        val last = points.last()
                        lineTo(x(last.distanceKm), y(last.speedKmh))
                    }
                    drawPath(
                        path = line,
                        color = lineColor,
                        style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }

                // Congestion dots on top of the line.
                points.forEachIndexed { index, point ->
                    val level = cost.segments.getOrNull(index)?.congestion ?: return@forEachIndexed
                    drawCircle(
                        color = level.graphColor(),
                        radius = 4.dp.toPx(),
                        center = Offset(x(point.distanceKm), y(point.speedKmh)),
                    )
                }
            }
        }

        // X-axis labels under the plot (0 .. distance in km).
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.width(GraphYAxisWidth))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { fraction ->
                    Text(
                        text = format(maxDistance * fraction, 0),
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.route_detail_graph_distance_axis),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.align(Alignment.End),
        )

        GraphLegend()
    }
}

@Composable
private fun GraphLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.route_detail_graph_speed_axis),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CongestionLevel.values().forEach { level ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(level.graphColor(), CircleShape),
                )
                Text(
                    text = stringResource(level.labelRes()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun CongestionLevel.graphColor(): Color = when (this) {
    CongestionLevel.NORMAL -> Color(0xFF2E7D32)
    CongestionLevel.SLOW -> Color(0xFFF9A825)
    CongestionLevel.TRAFFIC_JAM -> Color(0xFFC62828)
}

@StringRes
private fun CongestionLevel.labelRes(): Int = when (this) {
    CongestionLevel.NORMAL -> R.string.congestion_normal
    CongestionLevel.SLOW -> R.string.congestion_slow
    CongestionLevel.TRAFFIC_JAM -> R.string.congestion_jam
}

@StringRes
private fun Route.routeLabelRes(): Int = when {
    "FUEL_EFFICIENT" in routeLabels -> R.string.route_label_fuel_efficient
    "DEFAULT_ROUTE" in routeLabels -> R.string.route_label_primary
    else -> R.string.route_label_alternate
}