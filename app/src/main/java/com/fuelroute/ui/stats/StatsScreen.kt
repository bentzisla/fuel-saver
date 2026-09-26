package com.fuelroute.ui.stats

import android.Manifest
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star as StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.R
import com.fuelroute.data.obd.LiveObdState
import com.fuelroute.data.obd.ObdConnectStage
import com.fuelroute.data.obd.ObdStatus
import com.fuelroute.data.price.FuelGrades
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.settings.AppSettings
import com.fuelroute.domain.fuel.ModelConstants
import com.fuelroute.domain.fuel.RangeEstimator
import com.fuelroute.domain.model.VehicleProfile
import com.fuelroute.domain.obd.ObdConnectionPolicy
import com.fuelroute.domain.obd.ObdDevice
import com.fuelroute.ui.components.ConfirmDialog
import com.fuelroute.ui.components.DASH
import com.fuelroute.ui.components.Dimens
import com.fuelroute.ui.components.EmptyState
import com.fuelroute.ui.components.ExpandableSection
import com.fuelroute.ui.components.FuelTopBar
import com.fuelroute.ui.components.KeyValueRow
import com.fuelroute.ui.components.ListRow
import com.fuelroute.ui.components.PrimaryButton
import com.fuelroute.ui.components.SecondaryButton
import com.fuelroute.ui.components.SectionCard
import com.fuelroute.ui.components.SectionTitle
import com.fuelroute.ui.components.StatTile
import com.fuelroute.ui.components.StatusDot
import com.fuelroute.ui.components.fmt
import com.fuelroute.ui.components.formatDateTime
import com.fuelroute.ui.components.money
import com.fuelroute.ui.permission.PermissionGate
import com.fuelroute.ui.permission.findActivity
import com.fuelroute.ui.theme.FuelTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

/**
 * "Drive" tab: connection first, then the glanceable live numbers, then everything else folded.
 *
 *  1. Connection card — status dot + one line of state, and exactly one primary action for that
 *     state (connect to the last dongle / cancel / disconnect / retry).
 *  2. Live dashboard (when connected) — two big tiles (live consumption, trip cost) and three
 *     small ones (speed, distance, time).
 *  3. Collapsed sections: engine & fuel, learning progress, diagnostics & advanced (reset).
 *  4. Recent trips (5 shown, expandable).
 */
@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    onOpenCurve: () -> Unit = {},
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.live.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val connectingName by viewModel.connectingName.collectAsStateWithLifecycle()
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    val vehicles by viewModel.vehicles.collectAsStateWithLifecycle()
    val activeVehicle by viewModel.activeVehicle.collectAsStateWithLifecycle()
    val vinEvent by viewModel.vinEvent.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showAllTrips by rememberSaveable { mutableStateOf(false) }

    // Fuel price used for the live trip cost (₪). Read directly through a minimal Hilt entry
    // point instead of widening `StatsViewModel` (which the OBD work stream owns).
    val priceRepository = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            StatsPriceEntryPoint::class.java,
        ).fuelPriceRepository()
    }
    val fuelGrade = activeVehicle?.grade ?: FuelGrades.GASOLINE_95
    val fuelPricePerLiter by remember(priceRepository, fuelGrade) {
        priceRepository.price(fuelGrade).map { it.pricePerLiter }
    }.collectAsStateWithLifecycle(initialValue = ModelConstants.DEFAULT_FUEL_PRICE)

    LaunchedEffect(vinEvent) {
        val name = vinEvent ?: return@LaunchedEffect
        val label = name.ifBlank { context.getString(R.string.vehicle_untitled) }
        Toast.makeText(context, context.getString(R.string.stats_vin_detected, label), Toast.LENGTH_LONG).show()
        viewModel.consumeVinEvent()
    }

    // BLUETOOTH_CONNECT is required to list/connect bonded adapters; SCAN and
    // POST_NOTIFICATIONS are requested together but never gate the feature.
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
    }
    val optionalPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Do NOT auto-connect here: PermissionGate invokes onGranted on every (re)composition while
    // granted, which would re-arm the connection and fight a manual disconnect. Zero-touch
    // auto-connect is handled by the ACL receiver; here we only refresh the bonded-device list.
    val onPermissionsGranted: () -> Unit = remember(viewModel) { { viewModel.refreshDevices() } }

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

    val devicePicker: @Composable (Boolean) -> Unit = { initiallyOpen ->
        PermissionGate(
            permissions = requiredPermissions,
            optionalPermissions = optionalPermissions,
            rationale = stringResource(R.string.permission_obd_rationale),
            permanentlyDeniedMessage = stringResource(R.string.permission_obd_permanently_denied),
            onGranted = onPermissionsGranted,
        ) {
            DevicePicker(
                devices = devices,
                initiallyOpen = initiallyOpen,
                onConnect = viewModel::connect,
                onToggleFavorite = viewModel::toggleFavorite,
                onDemo = viewModel::connectDemo,
                onRefresh = viewModel::refreshDevices,
            )
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        FuelTopBar(
            title = stringResource(R.string.stats_title),
            actions = {
                if (vehicles.size > 1) {
                    VehicleMenu(vehicles = vehicles, active = activeVehicle, onSelect = viewModel::selectVehicle)
                }
                IconButton(onClick = onOpenCurve) {
                    Icon(
                        painter = painterResource(R.drawable.ic_show_chart),
                        contentDescription = stringResource(R.string.curve_open),
                    )
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Dimens.l, end = Dimens.l, bottom = Dimens.xl, top = Dimens.s),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            item(key = "connection") {
                ConnectionCard(
                    state = state,
                    connectingName = connectingName,
                    settings = settings,
                    devices = devices,
                    onConnectLast = { devices.firstOrNull()?.let(viewModel::connect) },
                    onRetry = viewModel::retry,
                    onDisconnect = viewModel::disconnect,
                    devicePicker = devicePicker,
                )
            }

            if (state.status == ObdStatus.Connected) {
                item(key = "live") { LiveDashboard(state = state, pricePerLiter = fuelPricePerLiter) }
                item(key = "engine") { EngineSection(state = state, vehicle = activeVehicle) }
                item(key = "learned") { LearnedSection(state = state) }
            }

            item(key = "diagnostics") {
                DiagnosticsSection(
                    state = state,
                    settings = settings,
                    initiallyExpanded = state.status == ObdStatus.Error,
                    onReset = { showResetConfirm = true },
                )
            }

            item(key = "trips-title") { SectionTitle(stringResource(R.string.stats_trips)) }
            if (trips.isEmpty()) {
                item(key = "trips-empty") { EmptyState(title = stringResource(R.string.stats_no_trips)) }
            } else {
                val visible = if (showAllTrips) trips else trips.take(TRIPS_PREVIEW)
                items(visible, key = { it.trip.id }) { display ->
                    TripRow(display)
                }
                if (trips.size > TRIPS_PREVIEW) {
                    item(key = "trips-more") {
                        TextButton(onClick = { showAllTrips = !showAllTrips }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (showAllTrips) {
                                    stringResource(R.string.stats_show_less)
                                } else {
                                    stringResource(R.string.stats_show_all, trips.size)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showResetConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.stats_reset_confirm_title),
            message = stringResource(R.string.stats_reset_confirm_message),
            confirmLabel = stringResource(R.string.stats_reset_confirm),
            onConfirm = {
                showResetConfirm = false
                viewModel.reset()
            },
            onDismiss = { showResetConfirm = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Connection
// ---------------------------------------------------------------------------------------------

/**
 * Always the first thing on the screen: where the connection stands and the single action that
 * moves it forward. Errors show a human reason; raw codes live in diagnostics.
 */
@Composable
private fun ConnectionCard(
    state: LiveObdState,
    connectingName: String?,
    settings: AppSettings,
    devices: List<ObdDevice>,
    onConnectLast: () -> Unit,
    onRetry: () -> Unit,
    onDisconnect: () -> Unit,
    devicePicker: @Composable (Boolean) -> Unit,
) {
    val failed = state.status == ObdStatus.Error ||
        (state.status == ObdStatus.Disconnected && state.lastError != null)
    SectionCard {
        when {
            state.status == ObdStatus.Connecting -> {
                ConnectingStatus(state = state, connectingName = connectingName)
                SecondaryButton(text = stringResource(R.string.common_cancel), onClick = onDisconnect)
            }

            state.status == ObdStatus.Connected -> {
                // The link itself is fine here — a persistent `lastError` in this branch is
                // only ever "NO DATA" (see ObdEngine): the RFCOMM link is up but the ECU stays
                // quiet, most commonly because the ignition is off while the dongle keeps
                // itself powered from the OBD port. Surfacing it (instead of staying silent)
                // tells the user why the live numbers stopped moving without implying the
                // connection dropped.
                StatusLine(
                    dotColor = FuelTheme.colors.positive,
                    title = state.deviceName?.let { stringResource(R.string.stats_status_connected, it) }
                        ?: stringResource(R.string.stats_status_connected_plain),
                    subtitle = state.lastError?.let { obdErrorText(it) },
                    trailing = {
                        TextButton(onClick = onDisconnect) { Text(stringResource(R.string.stats_disconnect)) }
                    },
                )
            }

            failed -> {
                StatusLine(
                    dotColor = MaterialTheme.colorScheme.error,
                    title = stringResource(R.string.stats_error),
                    subtitle = obdErrorText(state.lastError),
                )
                PrimaryButton(text = stringResource(R.string.stats_retry), onClick = onRetry)
                devicePicker(false)
            }

            else -> {
                StatusLine(
                    dotColor = FuelTheme.colors.neutral,
                    title = stringResource(R.string.stats_status_disconnected),
                    subtitle = stringResource(
                        if (settings.autoConnect) R.string.stats_auto_on_hint else R.string.stats_auto_off_hint,
                    ),
                )
                devices.firstOrNull()?.let { last ->
                    PrimaryButton(
                        text = stringResource(R.string.stats_connect_to, last.name),
                        onClick = onConnectLast,
                        icon = painterResource(R.drawable.ic_bluetooth),
                    )
                }
                devicePicker(devices.isEmpty())
            }
        }
    }
}

@Composable
private fun StatusLine(
    dotColor: Color,
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
        StatusDot(color = dotColor)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/** Stage + elapsed seconds while connecting; a hint that a powered-off dongle fails fast. */
@Composable
private fun ConnectingStatus(state: LiveObdState, connectingName: String?) {
    val sinceMs = state.connectingSinceMs
    var elapsedSec by remember(sinceMs) { mutableLongStateOf(0L) }
    LaunchedEffect(sinceMs) {
        if (sinceMs == null) {
            elapsedSec = 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsedSec = ((System.currentTimeMillis() - sinceMs) / 1000L).coerceAtLeast(0L)
            delay(1_000L)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = connectingName?.let { stringResource(R.string.stats_connecting_to, it) }
                    ?: stringResource(R.string.stats_connecting),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = connectStageText(state.connectionStage) + " · " +
                    stringResource(R.string.stats_connect_elapsed, elapsedSec),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        text = stringResource(R.string.stats_connect_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Bonded adapters (last used first, then favorites) — tap a row to connect, star to pin it.
 * Folded behind one "other dongle" button when a primary connect action is already shown.
 */
@Composable
private fun DevicePicker(
    devices: List<ObdDevice>,
    initiallyOpen: Boolean,
    onConnect: (ObdDevice) -> Unit,
    onToggleFavorite: (ObdDevice) -> Unit,
    onDemo: () -> Unit,
    onRefresh: () -> Unit,
) {
    // Re-evaluated when the list goes from empty to populated (bonded devices load async).
    var open by rememberSaveable(initiallyOpen) { mutableStateOf(initiallyOpen) }
    if (!open) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            TextButton(onClick = { open = true }) { Text(stringResource(R.string.stats_other_devices)) }
            TextButton(onClick = onDemo) { Text(stringResource(R.string.stats_demo_button)) }
        }
        return
    }
    Column {
        if (devices.isEmpty()) {
            Text(
                text = stringResource(R.string.stats_no_devices),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        devices.forEachIndexed { index, device ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ListRow(
                title = device.name,
                subtitle = if (device.isLastUsed) {
                    stringResource(R.string.stats_obd_last_used) + " · " + device.address
                } else {
                    device.address
                },
                leading = { Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null) },
                trailing = {
                    IconButton(onClick = { onToggleFavorite(device) }) {
                        Icon(
                            imageVector = if (device.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = stringResource(
                                if (device.isFavorite) R.string.stats_obd_favorite_remove else R.string.stats_obd_favorite_add,
                            ),
                            tint = if (device.isFavorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                onClick = { onConnect(device) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.stats_refresh)) }
            TextButton(onClick = onDemo) { Text(stringResource(R.string.stats_demo_button)) }
        }
    }
}

@Composable
private fun VehicleMenu(
    vehicles: List<VehicleProfile>,
    active: VehicleProfile?,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(
                text = active?.name?.ifBlank { null } ?: stringResource(R.string.vehicle_untitled),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.stats_vehicle_switch))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            vehicles.forEach { vehicle ->
                DropdownMenuItem(
                    text = { Text(vehicle.name.ifBlank { stringResource(R.string.vehicle_untitled) }) },
                    onClick = {
                        open = false
                        onSelect(vehicle.id)
                    },
                    leadingIcon = if (vehicle.id == active?.id) {
                        { Icon(Icons.Filled.Star, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Live
// ---------------------------------------------------------------------------------------------

/** The glanceable part: two big tiles and a row of three small ones. */
@Composable
private fun LiveDashboard(state: LiveObdState, pricePerLiter: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            LiveConsumptionTile(state = state, modifier = Modifier.weight(1f))
            StatTile(
                label = stringResource(R.string.stats_trip_cost),
                value = money(state.tripFuelL * pricePerLiter),
                unit = stringResource(R.string.stats_trip_fuel_value, fmt(state.tripFuelL, 2)),
                large = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            StatTile(
                label = stringResource(R.string.stats_speed),
                value = state.speedKmh?.let { fmt(it, 0) } ?: DASH,
                unit = stringResource(R.string.route_units_kmh),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.stats_trip_distance),
                value = fmt(state.tripDistanceKm, 1),
                unit = stringResource(R.string.route_units_km),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.stats_trip_time),
                value = fmt(state.tripSeconds / 60.0, 0),
                unit = stringResource(R.string.route_units_min),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EngineSection(state: LiveObdState, vehicle: VehicleProfile?) {
    val rangeKm = RangeEstimator.remainingRangeKm(
        tankCapacityL = vehicle?.tankCapacityL,
        levelPct = state.fuelLevelPct,
        litersPer100Km = state.instantL100,
    )
    ExpandableSection(
        title = stringResource(R.string.stats_engine_title),
        subtitle = rangeKm?.let { stringResource(R.string.stats_remaining_range, fmt(it, 0)) },
    ) {
        KeyValueRow(
            label = stringResource(R.string.stats_fuel_rate),
            value = state.fuelRateLph?.let { "${fmt(it, 1)} ${stringResource(R.string.stats_units_lph)}" } ?: DASH,
        )
        KeyValueRow(label = stringResource(R.string.stats_rpm), value = state.rpm?.let { fmt(it, 0) } ?: DASH)
        KeyValueRow(
            label = stringResource(R.string.stats_coolant),
            value = state.coolantTempC?.let { "${fmt(it, 0)}°" } ?: DASH,
        )
        KeyValueRow(
            label = stringResource(R.string.stats_battery),
            value = state.batteryVoltage?.let { "${fmt(it, 1)} V" } ?: DASH,
        )
        KeyValueRow(
            label = stringResource(R.string.stats_fuel_level),
            value = state.fuelLevelPct?.let { "${fmt(it, 0)}%" } ?: DASH,
        )
        rangeKm?.let {
            KeyValueRow(
                label = stringResource(R.string.stats_range_label),
                value = "${fmt(it, 0)} ${stringResource(R.string.route_units_km)}",
            )
            Text(
                text = stringResource(
                    R.string.stats_remaining_range_hint,
                    fmt(state.fuelLevelPct ?: 0.0, 0),
                    fmt(state.instantL100 ?: 0.0, 1),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LearnedSection(state: LiveObdState) {
    ExpandableSection(
        title = stringResource(R.string.stats_learned),
        subtitle = "${fmt(state.totalDistanceKm, 1)} ${stringResource(R.string.route_units_km)}",
    ) {
        KeyValueRow(
            label = stringResource(R.string.stats_learned_distance),
            value = "${fmt(state.totalDistanceKm, 1)} ${stringResource(R.string.route_units_km)}",
        )
        KeyValueRow(label = stringResource(R.string.stats_learned_bins), value = "${state.bins.size}")
        KeyValueRow(label = stringResource(R.string.stats_samples), value = "${state.sampleCount}")
    }
}

/**
 * Everything a normal driver never needs but a debugging session does: raw codes, VIN, PIDs,
 * sample rate, the auto-logging record — plus the "reset" action, kept away from the main flow.
 */
@Composable
private fun DiagnosticsSection(
    state: LiveObdState,
    settings: AppSettings,
    initiallyExpanded: Boolean,
    onReset: () -> Unit,
) {
    ExpandableSection(
        title = stringResource(R.string.stats_debug_title),
        initiallyExpanded = initiallyExpanded,
    ) {
        state.lastError?.let {
            KeyValueRow(
                label = stringResource(R.string.stats_last_error),
                value = it,
                valueColor = MaterialTheme.colorScheme.error,
            )
        }
        state.lastRawReply?.let {
            KeyValueRow(label = stringResource(R.string.stats_raw_reply), value = it)
        }
        KeyValueRow(
            label = stringResource(R.string.stats_sample_rate),
            value = "${fmt(state.sampleRateHz, 1)} Hz",
        )
        state.vin?.takeIf { it.isNotBlank() }?.let {
            KeyValueRow(label = stringResource(R.string.stats_vin), value = it)
        }
        if (state.supportedPids.isNotEmpty()) {
            KeyValueRow(label = stringResource(R.string.stats_pids_label), value = "${state.supportedPids.size}")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(text = stringResource(R.string.settings_auto_logging_title), style = MaterialTheme.typography.titleSmall)
        KeyValueRow(
            label = stringResource(R.string.stats_auto_state_label),
            value = stringResource(if (settings.autoConnect) R.string.settings_on else R.string.settings_off),
        )
        KeyValueRow(
            label = stringResource(R.string.stats_auto_device_label),
            value = settings.lastDeviceName?.takeIf { it.isNotBlank() }
                ?: settings.lastDeviceAddress
                ?: stringResource(R.string.settings_auto_logging_no_device),
        )
        KeyValueRow(
            label = stringResource(R.string.stats_auto_last_start_label),
            value = settings.lastAutoStartMs?.let { formatDateTime(it) }
                ?: stringResource(R.string.settings_auto_logging_never),
        )
        settings.lastObdError?.let {
            Text(
                text = stringResource(R.string.settings_auto_logging_last_error, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = stringResource(R.string.stats_reset_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SecondaryButton(text = stringResource(R.string.stats_reset), onClick = onReset)
    }
}

@Composable
private fun TripRow(display: TripDisplay) {
    val trip = display.trip
    val l100 = trip.litersPer100Km
    val details = buildString {
        append("${fmt(trip.distanceKm, 1)} ${stringResource(R.string.route_units_km)}")
        append(" · ${Math.round(trip.durationSeconds / 60.0)} ${stringResource(R.string.route_units_min)}")
        if (display.predictedL100 != null && l100 != null) {
            append(" · ")
            append(stringResource(R.string.stats_prediction, fmt(display.predictedL100, 1)))
        }
    }
    SectionCard(contentPadding = Dimens.s) {
        ListRow(
            title = formatDateTime(trip.startedAtMs),
            subtitle = details,
            modifier = Modifier.padding(horizontal = Dimens.s),
            trailing = {
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = l100?.let { fmt(it, 1) } ?: DASH, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.stats_units_l100),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

/** Maps the engine's machine error codes to a human-readable Hebrew reason. */
@Composable
private fun obdErrorText(code: String?): String = when {
    code == null -> stringResource(R.string.stats_status_disconnected)
    code.startsWith("bad ATZ") -> stringResource(R.string.stats_error_atz)
    code == "CONNECT TIMEOUT" -> stringResource(R.string.stats_error_connect_timeout)
    code == "CONNECT" -> stringResource(R.string.stats_error_connect)
    code == "SOCKET CLOSED" -> stringResource(R.string.stats_error_socket_closed)
    code == "SECURITY" -> stringResource(R.string.stats_error_security)
    code == "INIT" -> stringResource(R.string.stats_error_init)
    code == "SEARCHING" -> stringResource(R.string.stats_error_searching)
    code == "NO DATA" -> stringResource(R.string.stats_error_no_data)
    code == "PARSE" -> stringResource(R.string.stats_error_parse)
    code == "TIMEOUT" -> stringResource(R.string.stats_error_timeout)
    code == "RECONNECT" -> stringResource(R.string.stats_error_reconnect)
    code == "RECONNECT FAILED" -> stringResource(R.string.stats_error_reconnect_failed)
    code == "INIT TIMEOUT" -> stringResource(R.string.stats_error_init_timeout)
    code == ObdConnectionPolicy.ERROR_INIT_WRITE_FAILED -> stringResource(R.string.stats_error_init_write_failed)
    code == ObdConnectionPolicy.ERROR_INIT_EOF -> stringResource(R.string.stats_error_init_eof)
    code == ObdConnectionPolicy.ERROR_INIT_READ_ERROR -> stringResource(R.string.stats_error_init_read_error)
    code == ObdConnectionPolicy.ERROR_INIT_LINK_CLOSED -> stringResource(R.string.stats_error_init_link_closed)
    else -> code
}

/** Hebrew label for the current connect stage (falls back to the generic "מתחבר"). */
@Composable
private fun connectStageText(stage: ObdConnectStage?): String = when (stage) {
    ObdConnectStage.ConnectingSocket -> stringResource(R.string.stats_connect_stage_socket)
    ObdConnectStage.InitializingElm -> stringResource(R.string.stats_connect_stage_init)
    ObdConnectStage.SettlingProtocol -> stringResource(R.string.stats_connect_stage_settle)
    ObdConnectStage.NegotiatingPids -> stringResource(R.string.stats_connect_stage_pids)
    ObdConnectStage.ReadingVin -> stringResource(R.string.stats_connect_stage_vin)
    null -> stringResource(R.string.stats_connecting)
}

private const val TRIPS_PREVIEW = 5

/** Hilt access to the fuel-price store for the live trip cost. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface StatsPriceEntryPoint {
    fun fuelPriceRepository(): FuelPriceRepository
}
