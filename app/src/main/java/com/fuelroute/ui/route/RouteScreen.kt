package com.fuelroute.ui.route

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.R
import com.fuelroute.data.places.PlaceSuggestion
import com.fuelroute.data.places.RecentPlace
import com.fuelroute.data.routes.RoutesError
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

@Composable
fun RouteScreen(
    modifier: Modifier = Modifier,
    onOpenHistory: () -> Unit = {},
    viewModel: RouteViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var detailIndex by remember { mutableStateOf<Int?>(null) }
    val badges = RouteInsights.badges(state.results)

    val context = LocalContext.current
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

    Column(modifier = modifier.fillMaxSize()) {
        if (state.results.isNotEmpty()) {
            RouteMap(
                routes = state.results.map { it.route },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
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
                )
            }

            if (state.destination.isBlank() && state.history.isNotEmpty()) {
                item {
                    RecentDestinations(
                        history = state.history,
                        onSelect = viewModel::onRecentSelected,
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

            itemsIndexed(state.results) { index, cost ->
                val cheapest = badges.cheapestIndex?.let { state.results[it] }
                val fastest = badges.fastestIndex?.let { state.results[it] }
                RouteCard(
                    cost = cost,
                    index = index,
                    isCheapest = badges.cheapestIndex == index,
                    isFastest = badges.fastestIndex == index,
                    costPremium = cheapest?.let { (cost.totalCost - it.totalCost).coerceAtLeast(0.0) } ?: 0.0,
                    timePenaltyMinutes = fastest?.let { (cost.durationMinutes - it.durationMinutes).coerceAtLeast(0.0) } ?: 0.0,
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
                onDismiss = { detailIndex = null },
            )
        }
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
                )
            }
        }
    }
}

@Composable
private fun PlaceField(
    value: String,
    onValueChange: (String) -> Unit,
    onSelect: (PlaceSuggestion) -> Unit,
    suggestions: List<PlaceSuggestion>,
    label: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
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
                text = stringResource(R.string.route_cheapest_summary, format(cheapest.totalCost, 2)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.route_fastest_summary, format(fastest.durationMinutes, 0)),
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
    costPremium: Double,
    timePenaltyMinutes: Double,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
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

            Text(
                text = "₪ ${format(cost.totalCost, 2)}",
                style = MaterialTheme.typography.headlineSmall,
            )

            if (costPremium > 0.005) {
                Text(
                    text = stringResource(R.string.route_delta_cost, format(costPremium, 2)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (timePenaltyMinutes > 0.5) {
                Text(
                    text = stringResource(R.string.route_delta_time, format(timePenaltyMinutes, 0)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoColumn(
                    label = stringResource(R.string.route_fuel_cost_label),
                    value = "₪ ${format(cost.fuelCost, 1)}",
                )
                InfoColumn(
                    label = stringResource(R.string.route_fuel_label),
                    value = "${format(cost.fuelLiters, 1)} L",
                )
                InfoColumn(
                    label = stringResource(R.string.route_toll_label),
                    value = if (cost.route.tollUnknown) "—" else "₪ ${format(cost.tollCost, 1)}",
                )
            }

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
                    label = stringResource(R.string.route_avg_speed_label),
                    value = "${format(cost.avgSpeedKmh, 0)} ${stringResource(R.string.route_units_kmh)}",
                )
            }

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
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val pricePerLiter = if (cost.fuelLiters > 0.0) cost.fuelCost / cost.fuelLiters else 0.0
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
                Text(
                    text = "₪ ${format(cost.totalCost, 2)}",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(
                        R.string.route_detail_fuel,
                        format(cost.fuelLiters, 2),
                        format(pricePerLiter, 2),
                        format(cost.fuelCost, 2),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.route_detail_toll, format(cost.tollCost, 2)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.route_detail_total, format(cost.totalCost, 2)),
                    style = MaterialTheme.typography.titleSmall,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = stringResource(R.string.route_detail_segments_title, cost.segments.size),
                    style = MaterialTheme.typography.titleSmall,
                )
                LazyColumn(modifier = Modifier.height(260.dp)) {
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