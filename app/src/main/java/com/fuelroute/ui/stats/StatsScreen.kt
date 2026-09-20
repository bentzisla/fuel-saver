package com.fuelroute.ui.stats

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.R
import com.fuelroute.data.obd.LiveObdState
import com.fuelroute.data.obd.ObdStatus
import com.fuelroute.domain.model.Trip
import com.fuelroute.ui.permission.PermissionGate
import com.fuelroute.ui.permission.findActivity
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    onOpenCurve: () -> Unit = {},
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.live.collectAsStateWithLifecycle()
    val bonded by viewModel.bonded.collectAsStateWithLifecycle()
    val connectingName by viewModel.connectingName.collectAsStateWithLifecycle()
    val trips by viewModel.trips.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // BLUETOOTH_CONNECT is required to list/connect bonded adapters; SCAN and
    // POST_NOTIFICATIONS are requested together but never gate the feature.
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyList()
        }
    }
    val optionalPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    val onPermissionsGranted: () -> Unit = remember(viewModel) {
        {
            viewModel.refreshDevices()
            viewModel.autoConnect()
        }
    }

    // While the live dashboard is connected, optionally hold the screen on.
    DisposableEffect(activity, settings.keepScreenOn, state.status) {
        val window = activity?.window
        if (settings.keepScreenOn && state.status == ObdStatus.Connected) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.stats_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        item {
            OutlinedButton(onClick = onOpenCurve, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.curve_open))
            }
        }

        when (state.status) {
            ObdStatus.Disconnected -> {
                item {
                    PermissionGate(
                        permissions = requiredPermissions,
                        optionalPermissions = optionalPermissions,
                        rationale = stringResource(R.string.permission_obd_rationale),
                        permanentlyDeniedMessage = stringResource(R.string.permission_obd_permanently_denied),
                        onGranted = onPermissionsGranted,
                    ) {
                        ConnectionCard(bonded = bonded, viewModel = viewModel)
                    }
                }
            }
            ObdStatus.Connecting -> {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = connectingName?.let { stringResource(R.string.stats_connecting_to, it) }
                                ?: stringResource(R.string.stats_connecting),
                        )
                    }
                }
            }
            ObdStatus.Connected -> {
                item { SpeedGauge(state) }
                item { MetricRow(state) }
                if (state.lastError != null || state.lastRawReply != null) {
                    item { DebugCard(state) }
                }
                item { TripSummaryCard(state) }
                item { LearnedCard(state) }
                item {
                    OutlinedButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.stats_stop))
                    }
                }
            }
            ObdStatus.Error -> {
                item {
                    Text(
                        text = stringResource(R.string.stats_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.lastError != null || state.lastRawReply != null) {
                    item { DebugCard(state) }
                }
                item {
                    PermissionGate(
                        permissions = requiredPermissions,
                        optionalPermissions = optionalPermissions,
                        rationale = stringResource(R.string.permission_obd_rationale),
                        permanentlyDeniedMessage = stringResource(R.string.permission_obd_permanently_denied),
                        onGranted = onPermissionsGranted,
                    ) {
                        ConnectionCard(bonded = bonded, viewModel = viewModel)
                    }
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.stats_trips),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (trips.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.stats_no_trips),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(trips.size) { index ->
                TripRow(trips[index].trip, trips[index].predictedL100)
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    bonded: List<BluetoothDevice>,
    viewModel: StatsViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.stats_connect_header),
                    style = MaterialTheme.typography.titleMedium,
                )

                if (bonded.isEmpty()) {
                    Text(
                        text = stringResource(R.string.stats_no_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    bonded.forEach { device ->
                        DeviceRow(device = device, onClick = { viewModel.connect(device) })
                    }
                }

                Button(onClick = viewModel::connectDemo, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.stats_demo_row))
                }

                TextButton(onClick = viewModel::refreshDevices) {
                    Text(stringResource(R.string.stats_refresh))
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceRow(device: BluetoothDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = device.name ?: stringResource(R.string.stats_demo_row),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpeedGauge(state: LiveObdState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.stats_speed),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${Math.round(state.speedKmh ?: 0.0)}",
                style = MaterialTheme.typography.displayLarge,
            )
            Text(
                text = stringResource(R.string.route_units_kmh),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricRow(state: LiveObdState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricTile(
            label = stringResource(R.string.stats_inst_l100),
            value = state.instantL100?.let { format(it, 1) } ?: "-",
            unit = stringResource(R.string.stats_units_l100),
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = stringResource(R.string.stats_fuel_rate),
            value = state.fuelRateLph?.let { format(it, 1) } ?: "-",
            unit = stringResource(R.string.stats_units_lph),
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = stringResource(R.string.stats_coolant),
            value = state.coolantTempC?.let { "${Math.round(it)}°" } ?: "-",
            unit = "",
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = stringResource(R.string.stats_rpm),
            value = state.rpm?.let { "${Math.round(it)}" } ?: "-",
            unit = "",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
            )
            if (unit.isNotBlank()) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DebugCard(state: LiveObdState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.stats_debug_title),
                style = MaterialTheme.typography.titleSmall,
            )
            state.lastError?.let {
                Text(
                    text = stringResource(R.string.stats_last_error) + ": " + it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.lastRawReply?.let {
                Text(
                    text = stringResource(R.string.stats_raw_reply) + ": " + it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.stats_sample_rate) + ": " + format(state.sampleRateHz, 1) + " Hz",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.batteryVoltage?.let {
                Text(
                    text = stringResource(R.string.stats_battery) + ": " + format(it, 1) + " V",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.vin?.let {
                Text(
                    text = stringResource(R.string.stats_vin) + ": " + it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TripSummaryCard(state: LiveObdState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.stats_trip),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoColumn(
                    label = stringResource(R.string.stats_trip_distance),
                    value = "${format(state.tripDistanceKm, 1)} ${stringResource(R.string.route_units_km)}",
                )
                InfoColumn(
                    label = stringResource(R.string.stats_trip_fuel),
                    value = "${format(state.tripFuelL, 2)} L",
                )
                InfoColumn(
                    label = stringResource(R.string.stats_trip_time),
                    value = "${format(state.tripSeconds / 60.0, 1)} ${stringResource(R.string.route_units_min)}",
                )
            }
        }
    }
}

@Composable
private fun LearnedCard(state: LiveObdState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.stats_learned),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoColumn(
                    label = stringResource(R.string.stats_learned_distance),
                    value = "${format(state.totalDistanceKm, 1)} ${stringResource(R.string.route_units_km)}",
                )
                InfoColumn(
                    label = stringResource(R.string.stats_learned_bins),
                    value = "${state.bins.size}",
                )
                InfoColumn(
                    label = stringResource(R.string.stats_samples),
                    value = "${state.sampleCount}",
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

@Composable
private fun TripRow(trip: Trip, predictedL100: Double?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = formatDate(trip.startedAtMs),
                style = MaterialTheme.typography.titleSmall,
            )
            val l100 = trip.litersPer100Km?.let { format(it, 1) } ?: "-"
            Text(
                text = "${format(trip.distanceKm, 1)} ${stringResource(R.string.route_units_km)} • " +
                    "$l100 ${stringResource(R.string.stats_units_l100)} • " +
                    "${Math.round(trip.durationSeconds / 60.0)} ${stringResource(R.string.route_units_min)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (predictedL100 != null && trip.litersPer100Km != null) {
                Text(
                    text = stringResource(R.string.stats_prediction, format(predictedL100, 1)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochMs))

private fun format(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)