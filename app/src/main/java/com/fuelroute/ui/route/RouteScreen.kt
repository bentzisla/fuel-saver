package com.fuelroute.ui.route

import android.Manifest
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
import com.fuelroute.data.settings.NAV_WAZE
import com.fuelroute.domain.model.CongestionLevel
import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteCost
import com.fuelroute.domain.model.SegmentCost
import com.fuelroute.nav.NavigationLauncher
import java.util.Locale

@Composable
fun RouteScreen(
    modifier: Modifier = Modifier,
    onOpenHistory: () -> Unit = {},
    viewModel: RouteViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var detailIndex by remember { mutableStateOf<Int?>(null) }

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
                    Button(
                        onClick = viewModel::compute,
                        enabled = !state.isLoading &&
                            (state.originIsCurrentLocation || state.origin.isNotBlank()) &&
                            state.destination.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.route_compute))
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

            if (state.error != null) {
                item {
                    Text(
                        text = state.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
            }

            state.routeCountMessage?.let { message ->
                item {
                    Text(
                        text = stringResource(message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            itemsIndexed(state.results) { index, cost ->
                RouteCard(
                    cost = cost,
                    index = index,
                    isCheapest = index == 0,
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
            val originText = if (state.originIsCurrentLocation && originLocation != null) {
                "${originLocation.latitude},${originLocation.longitude}"
            } else {
                state.origin
            }
            RouteDetailDialog(
                cost = cost,
                origin = originText,
                destination = state.destination,
                navigationApp = state.navigationApp,
                onDismiss = { detailIndex = null },
            )
        }
    }
}

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
private fun RouteCard(
    cost: RouteCost,
    index: Int,
    isCheapest: Boolean,
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
                    value = "₪ ${format(cost.tollCost, 1)}",
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
    origin: String,
    destination: String,
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
                        NavigationLauncher.openWaze(context, destination)
                    } else {
                        NavigationLauncher.openGoogleMaps(context, origin, destination, cost.route.encodedPolyline)
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