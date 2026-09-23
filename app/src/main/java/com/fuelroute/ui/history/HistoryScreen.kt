package com.fuelroute.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.R
import com.fuelroute.data.history.DriveHistoryEntry
import com.fuelroute.data.history.LinkableSearch
import com.fuelroute.data.history.RideState
import com.fuelroute.domain.history.ManualCostCalculator
import com.fuelroute.domain.history.ManualCostInput
import com.fuelroute.domain.history.PredictionAccuracy
import com.fuelroute.domain.history.SplitAnchor
import com.fuelroute.ui.components.ConfirmDialog
import com.fuelroute.ui.components.DASH
import com.fuelroute.ui.components.Dimens
import com.fuelroute.ui.components.EmptyState
import com.fuelroute.ui.components.FuelTopBar
import com.fuelroute.ui.components.HeroValue
import com.fuelroute.ui.components.KeyValueRow
import com.fuelroute.ui.components.ListRow
import com.fuelroute.ui.components.PillTone
import com.fuelroute.ui.components.SectionCard
import com.fuelroute.ui.components.SectionTitle
import com.fuelroute.ui.components.StatusPill
import com.fuelroute.ui.components.fmt
import com.fuelroute.ui.components.formatDateTime
import com.fuelroute.ui.components.money
import com.fuelroute.ui.components.signed
import com.fuelroute.ui.theme.FuelTheme
import kotlin.math.abs

/**
 * Search/drive history: one summary (money saved, forecast accuracy) and a calm list of rides.
 * Each row shows only what the ride cost and its state; the full predicted-vs-actual breakdown
 * and every action (manual cost, link, merge/split, delete) live in the ride's detail sheet.
 */
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    state.linkFeedback?.let { feedback ->
        LaunchedEffect(feedback) {
            snackbar.showSnackbar(
                context.getString(
                    when (feedback) {
                        LinkFeedback.LINKED -> R.string.history_link_linked
                        LinkFeedback.NONE_FOUND -> R.string.history_link_none
                    },
                ),
            )
            viewModel.clearLinkFeedback()
        }
    }
    state.mergeSplitFeedback?.let { feedback ->
        LaunchedEffect(feedback) {
            snackbar.showSnackbar(
                context.getString(
                    when (feedback) {
                        MergeSplitFeedback.MERGED -> R.string.history_merge_split_merged
                        MergeSplitFeedback.SPLIT -> R.string.history_merge_split_split
                        MergeSplitFeedback.FAILED -> R.string.history_merge_split_failed
                    },
                ),
            )
            viewModel.clearMergeSplitFeedback()
        }
    }

    HistoryDialogs(state = state, viewModel = viewModel)

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            FuelTopBar(
                title = stringResource(R.string.history_title),
                onBack = onBack,
                actions = {
                    if (state.entries.isNotEmpty()) {
                        TextButton(onClick = viewModel::toggleSelectionMode) {
                            Text(
                                stringResource(
                                    if (state.selectionMode) R.string.history_select_done else R.string.history_select,
                                ),
                            )
                        }
                    }
                },
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Dimens.l, end = Dimens.l, bottom = Dimens.xl, top = Dimens.s),
                verticalArrangement = Arrangement.spacedBy(Dimens.m),
            ) {
                if (state.selectionMode) {
                    item(key = "selection") {
                        SelectionBar(
                            count = state.selectedIds.size,
                            onDelete = viewModel::requestBulkDelete,
                        )
                    }
                } else if (state.entries.isNotEmpty()) {
                    item(key = "summary") { SummaryCard(state) }
                }

                when {
                    state.isLoading -> item(key = "loading") {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    state.entries.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            title = stringResource(R.string.history_empty),
                            body = stringResource(R.string.history_empty_hint),
                        )
                    }

                    else -> items(state.entries, key = { it.selectionId ?: it.timestampMs }) { entry ->
                        val selected = entry.selectionId?.let { it in state.selectedIds } ?: false
                        HistoryRow(
                            entry = entry,
                            selectionMode = state.selectionMode,
                            selected = selected,
                            onClick = {
                                if (state.selectionMode) viewModel.toggleSelection(entry) else viewModel.openDetail(entry)
                            },
                        )
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(Dimens.l),
        )
    }
}

/** All modal UI of the screen, driven by the ViewModel's state flags. */
@Composable
private fun HistoryDialogs(state: HistoryUiState, viewModel: HistoryViewModel) {
    state.selectedEntry?.let { entry ->
        RideDetailSheet(
            entry = entry,
            onDismiss = viewModel::dismissDetail,
            onManualCost = { viewModel.openManualCost(entry) },
            onLink = {
                viewModel.dismissDetail()
                viewModel.openManualLink(entry)
            },
            onMergeSplit = { viewModel.openMergeSplit(entry) },
            onDelete = {
                viewModel.dismissDetail()
                viewModel.requestDelete(entry)
            },
        )
    }

    if (state.linkTargetTripId != null) {
        LinkRideDialog(
            candidates = state.linkCandidates,
            isLoading = state.isLoadingCandidates,
            onPick = viewModel::linkToSearch,
            onNearest = viewModel::linkToNearest,
            onDismiss = viewModel::dismissManualLink,
        )
    }

    if (state.pendingDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.history_delete_title),
            message = stringResource(R.string.history_delete_message),
            confirmLabel = stringResource(R.string.history_delete_confirm),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }

    if (state.manualEntryTripId != null) {
        ManualCostDialog(
            pricePerLiter = state.pricePerLiter,
            onSave = viewModel::saveManualCost,
            onDismiss = viewModel::dismissManualCost,
        )
    }

    if (state.pendingBulkDelete) {
        ConfirmDialog(
            title = stringResource(R.string.history_bulk_delete_title),
            message = stringResource(R.string.history_bulk_delete_message, state.selectedIds.size),
            confirmLabel = stringResource(R.string.history_delete_confirm),
            onConfirm = viewModel::confirmBulkDelete,
            onDismiss = viewModel::cancelBulkDelete,
        )
    }

    state.mergeSplitEntry?.let { entry ->
        MergeSplitDialog(
            entry = entry,
            candidates = mergeCandidates(state.entries, entry),
            onMerge = viewModel::requestMerge,
            onSplit = { splitAtMs -> entry.tripId?.let { viewModel.requestSplit(it, splitAtMs) } },
            onDismiss = viewModel::dismissMergeSplit,
        )
    }

    if (state.pendingMerge != null) {
        ConfirmDialog(
            title = stringResource(R.string.history_merge_confirm_title),
            message = stringResource(R.string.history_merge_confirm_message),
            confirmLabel = stringResource(R.string.history_merge_action),
            onConfirm = viewModel::confirmMerge,
            onDismiss = viewModel::cancelMerge,
        )
    }

    if (state.pendingSplit != null) {
        ConfirmDialog(
            title = stringResource(R.string.history_split_confirm_title),
            message = stringResource(R.string.history_split_confirm_message),
            confirmLabel = stringResource(R.string.history_split_action),
            onConfirm = viewModel::confirmSplit,
            onDismiss = viewModel::cancelSplit,
        )
    }
}

/** The one hero of the screen: how much the recommendations saved, and how accurate they were. */
@Composable
private fun SummaryCard(state: HistoryUiState) {
    val totalSaved = state.entries.filter { it.hasActual }.sumOf { it.savedAmount }
    SectionCard {
        Row(verticalAlignment = Alignment.Bottom) {
            HeroValue(
                value = money(totalSaved),
                label = stringResource(R.string.history_saved_label),
                color = if (totalSaved > 0.0) FuelTheme.colors.positive else MaterialTheme.colorScheme.onSurface,
                valueStyle = MaterialTheme.typography.displaySmall,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.End) {
                state.accuracyPct?.let { accuracy ->
                    Text(
                        text = stringResource(R.string.history_accuracy, fmt(accuracy, 1)),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(
                    text = stringResource(R.string.history_rides_count, state.entries.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(count: Int, onDelete: () -> Unit) {
    SectionCard(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentPadding = Dimens.s) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.history_selected_count, count),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Dimens.s),
            )
            TextButton(onClick = onDelete, enabled = count > 0) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Text(
                    text = stringResource(R.string.history_bulk_delete_action),
                    modifier = Modifier.padding(start = Dimens.xs),
                )
            }
        }
    }
}

/**
 * One ride, readable at a glance: where, when, what it cost (actual, else predicted) and at
 * most three state pills. Tap for the breakdown and actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryRow(
    entry: DriveHistoryEntry,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val actual = entry.actualCost?.takeIf { entry.hasActual }
    val predicted = entry.predictedCost
    SectionCard(
        onClick = onClick,
        containerColor = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = null)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = routeTitle(entry).ifBlank { stringResource(R.string.history_unlinked_drive) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        formatDateTime(entry.timestampMs),
                        entry.distanceKm?.let { "${fmt(it, 1)} ${stringResource(R.string.route_units_km)}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = (actual ?: predicted)?.let { money(it) } ?: DASH,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(
                        if (actual != null) R.string.history_actual_label else R.string.history_predicted_label,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.xs), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            RideStatePill(entry.rideState)
            if (entry.isDemo) StatusPill(stringResource(R.string.history_demo_badge), tone = PillTone.Caution)
            if (entry.hasManualEntry) StatusPill(stringResource(R.string.history_manual_short), tone = PillTone.Accent)
            if (predicted != null && actual != null && predicted > 0.0) {
                PredictionAccuracy.errorPct(predicted, actual)?.let { pct ->
                    StatusPill(
                        text = stringResource(R.string.history_delta_pill, signed(pct)),
                        tone = if (abs(pct) <= 10.0) PillTone.Positive else PillTone.Caution,
                    )
                }
            }
            if (entry.hasActual && entry.savedAmount > 0.0) {
                StatusPill(
                    text = stringResource(R.string.history_saved_pill, fmt(entry.savedAmount, 2)),
                    tone = PillTone.Positive,
                )
            }
        }
    }
}

@Composable
private fun RideStatePill(state: RideState) {
    when (state) {
        RideState.LINKED -> StatusPill(stringResource(R.string.history_ride_state_linked), tone = PillTone.Positive)
        RideState.WAITING_OBD -> StatusPill(stringResource(R.string.history_ride_state_waiting))
        RideState.NO_PREDICTION -> StatusPill(stringResource(R.string.history_ride_state_no_prediction))
    }
}

/**
 * Full predicted-vs-actual breakdown for one ride, plus every action that applies to it. Replaces
 * the per-card buttons/delete icon of the old list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RideDetailSheet(
    entry: DriveHistoryEntry,
    onDismiss: () -> Unit,
    onManualCost: () -> Unit,
    onLink: () -> Unit,
    onMergeSplit: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val predicted = entry.predictedCost
    val actual = entry.actualCost?.takeIf { entry.hasActual }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = Dimens.l, end = Dimens.l, bottom = Dimens.xl),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            Column {
                Text(
                    text = routeTitle(entry).ifBlank { stringResource(R.string.history_detail_unlinked) },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = formatDateTime(entry.timestampMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                RideStatePill(entry.rideState)
                if (entry.isDemo) StatusPill(stringResource(R.string.history_demo_badge), tone = PillTone.Caution)
                if (entry.hasManualEntry) StatusPill(stringResource(R.string.history_manual_badge), tone = PillTone.Accent)
            }

            SectionTitle(stringResource(R.string.history_detail_title))
            KeyValueRow(
                label = stringResource(R.string.history_predicted_label),
                value = predicted?.let {
                    stringResource(
                        R.string.history_value_triplet,
                        fmt(it, 2),
                        entry.predictedLiters?.let { l -> fmt(l, 1) } ?: DASH,
                        entry.predictedMinutes?.let { m -> fmt(m, 0) } ?: DASH,
                    )
                } ?: stringResource(R.string.history_ride_state_no_prediction),
            )
            KeyValueRow(
                label = stringResource(R.string.history_actual_label),
                value = actual?.let {
                    stringResource(
                        R.string.history_value_triplet,
                        fmt(it, 2),
                        entry.actualLiters?.let { l -> fmt(l, 1) } ?: DASH,
                        entry.actualMinutes?.let { m -> fmt(m, 0) } ?: DASH,
                    )
                } ?: stringResource(R.string.history_ride_state_waiting),
            )
            if (predicted != null && actual != null && predicted > 0.0) {
                val delta = actual - predicted
                val pct = PredictionAccuracy.errorPct(predicted, actual)
                KeyValueRow(
                    label = stringResource(R.string.history_delta_label),
                    value = stringResource(R.string.history_delta, signed(delta), pct?.let { signed(it) } ?: DASH),
                    emphasize = true,
                    valueColor = if (delta > 0.0) MaterialTheme.colorScheme.error else FuelTheme.colors.positive,
                )
            }
            if (entry.hasActual && entry.savedAmount > 0.0) {
                KeyValueRow(
                    label = stringResource(R.string.history_saving_label),
                    value = money(entry.savedAmount),
                    valueColor = FuelTheme.colors.positive,
                )
            }
            entry.distanceKm?.let {
                KeyValueRow(
                    label = stringResource(R.string.route_distance_label),
                    value = "${fmt(it, 1)} ${stringResource(R.string.route_units_km)}",
                )
            }
            entry.pricePerLiterAtSearch?.let {
                KeyValueRow(label = stringResource(R.string.history_price_search_label), value = money(it))
            }
            entry.pricePerLiterAtTrip?.let {
                KeyValueRow(label = stringResource(R.string.history_price_trip_label), value = money(it))
            }

            SectionTitle(stringResource(R.string.history_actions_title))
            Column {
                if (entry.canEnterManualCost) {
                    ListRow(
                        title = stringResource(R.string.history_manual_action),
                        leading = { Icon(painterResource(R.drawable.ic_gas_station), contentDescription = null) },
                        onClick = onManualCost,
                    )
                }
                if (entry.canLinkManually) {
                    ListRow(
                        title = stringResource(R.string.history_link_ride),
                        leading = { Icon(painterResource(R.drawable.ic_navigation), contentDescription = null) },
                        onClick = onLink,
                    )
                }
                if (entry.tripId != null) {
                    ListRow(
                        title = stringResource(R.string.history_merge_split_action),
                        leading = { Icon(painterResource(R.drawable.ic_tune), contentDescription = null) },
                        onClick = onMergeSplit,
                    )
                }
                ListRow(
                    title = stringResource(R.string.history_delete_title),
                    titleColor = MaterialTheme.colorScheme.error,
                    leading = {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = onDelete,
                )
            }
        }
    }
}

/**
 * Manual post-drive cost entry. Two modes share one dialog: a direct ₪ amount, or distance +
 * average consumption which is converted with the current fuel price (live preview).
 */
@Composable
private fun ManualCostDialog(
    pricePerLiter: Double,
    onSave: (ManualCostInput) -> Unit,
    onDismiss: () -> Unit,
) {
    var consumptionMode by remember { mutableStateOf(false) }
    var costText by remember { mutableStateOf("") }
    var distanceText by remember { mutableStateOf("") }
    var consumptionText by remember { mutableStateOf("") }

    val cost = costText.toDoubleOrNull()
    val distance = distanceText.toDoubleOrNull()
    val consumption = consumptionText.toDoubleOrNull()

    val directInput = if (!consumptionMode) ManualCostCalculator.fromCost(cost ?: Double.NaN) else null
    val consumptionInput = if (consumptionMode && distance != null && consumption != null) {
        ManualCostCalculator.fromConsumption(distance, consumption, pricePerLiter)
    } else {
        null
    }
    val estimate = if (consumptionMode && distance != null && consumption != null) {
        ManualCostCalculator.estimate(distance, consumption, pricePerLiter)
    } else {
        null
    }
    val hasInput = costText.isNotBlank() || distanceText.isNotBlank() || consumptionText.isNotBlank()
    val canSave = directInput != null || consumptionInput != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_manual_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.s),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    FilterChip(
                        selected = !consumptionMode,
                        onClick = { consumptionMode = false },
                        label = { Text(stringResource(R.string.history_manual_mode_direct)) },
                    )
                    FilterChip(
                        selected = consumptionMode,
                        onClick = { consumptionMode = true },
                        label = { Text(stringResource(R.string.history_manual_mode_consumption)) },
                    )
                }
                if (!consumptionMode) {
                    DecimalField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = stringResource(R.string.history_manual_cost_label),
                    )
                } else {
                    DecimalField(
                        value = distanceText,
                        onValueChange = { distanceText = it },
                        label = stringResource(R.string.history_manual_distance_label),
                    )
                    DecimalField(
                        value = consumptionText,
                        onValueChange = { consumptionText = it },
                        label = stringResource(R.string.history_manual_consumption_label),
                    )
                    Text(
                        text = stringResource(R.string.history_manual_price, fmt(pricePerLiter, 2)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    estimate?.let { preview ->
                        Text(
                            text = stringResource(
                                R.string.history_manual_preview,
                                fmt(preview.liters, 2),
                                fmt(preview.cost, 2),
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (hasInput && !canSave) {
                    Text(
                        text = stringResource(R.string.history_manual_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { (directInput ?: consumptionInput)?.let(onSave) }, enabled = canSave) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun DecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Merge/split picker for one drive: nearby trips to merge, and a slider that picks the split
 * time between the drive's start and end. Both actions go through a confirmation dialog.
 */
@Composable
private fun MergeSplitDialog(
    entry: DriveHistoryEntry,
    candidates: List<DriveHistoryEntry>,
    onMerge: (List<Long>) -> Unit,
    onSplit: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember(entry.tripId) {
        mutableStateListOf<Long>().apply { entry.tripId?.let { add(it) } }
    }
    var splitFraction by remember(entry.tripId) { mutableFloatStateOf(0.5f) }

    val splitStartedAtMs = entry.tripStartedAtMs
    val splitEndedAtMs = entry.tripEndedAtMs
    val canSplit = SplitAnchor.canSplit(splitStartedAtMs, splitEndedAtMs)
    val splitAtMs = SplitAnchor.splitAtMs(splitStartedAtMs, splitEndedAtMs, splitFraction)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_merge_split_action)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.s),
            ) {
                Text(
                    text = stringResource(R.string.history_merge_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.history_merge_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (candidates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.history_merge_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    candidates.forEach { candidate ->
                        val candidateId = candidate.tripId ?: return@forEach
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = candidateId in selected,
                                onCheckedChange = { checked ->
                                    if (checked) selected.add(candidateId) else selected.remove(candidateId)
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = formatDateTime(candidate.timestampMs), style = MaterialTheme.typography.bodyMedium)
                                candidate.distanceKm?.let { distance ->
                                    Text(
                                        text = "${fmt(distance, 1)} ${stringResource(R.string.route_units_km)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { onMerge(selected.toList()) }, enabled = selected.size >= 2) {
                    Text(stringResource(R.string.history_merge_action))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = Dimens.xs))

                // The split anchor needs the real trip window; an undriven search has none.
                if (canSplit && splitAtMs != null && splitStartedAtMs != null && splitEndedAtMs != null) {
                    Text(text = stringResource(R.string.history_split_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(R.string.history_split_point, formatDateTime(splitAtMs)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = splitFraction,
                        onValueChange = { splitFraction = it },
                        valueRange = 0.05f..0.95f,
                    )
                    TextButton(onClick = { onSplit(splitAtMs) }) {
                        Text(stringResource(R.string.history_split_action))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.history_split_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

/** Picker shown by "קשר לחיפוש" so the user can pair an unlinked drive with a search. */
@Composable
private fun LinkRideDialog(
    candidates: List<LinkableSearch>,
    isLoading: Boolean,
    onPick: (Long) -> Unit,
    onNearest: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_link_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                TextButton(onClick = onNearest) {
                    Text(stringResource(R.string.history_link_nearest))
                }
                when {
                    isLoading -> CircularProgressIndicator()
                    candidates.isEmpty() -> Text(
                        text = stringResource(R.string.history_link_dialog_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        candidates.forEach { candidate ->
                            ListRow(
                                title = candidateTitle(candidate),
                                subtitle = formatDateTime(candidate.timestampMs),
                                onClick = { onPick(candidate.id) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Trips that can be merged with [anchor]: drives close enough in time that they are likely the
 * same journey split by a pause/restart. Proximity uses the underlying trip start, not the display
 * timestamp (which is the search time for a linked ride). Demo rides may only merge with demo
 * rides and real drives only with real drives, so a simulated ride is never folded into real data.
 */
internal fun mergeCandidates(
    entries: List<DriveHistoryEntry>,
    anchor: DriveHistoryEntry,
): List<DriveHistoryEntry> {
    val anchorStartMs = anchor.tripStartedAtMs ?: return emptyList()
    return entries.filter { candidate ->
        val candidateStartMs = candidate.tripStartedAtMs
        candidate.tripId != null &&
            candidate.tripId != anchor.tripId &&
            candidateStartMs != null &&
            candidate.isDemo == anchor.isDemo &&
            abs(candidateStartMs - anchorStartMs) <= MERGE_WINDOW_MS
    }
}

private fun routeTitle(entry: DriveHistoryEntry): String = when {
    !entry.originLabel.isNullOrBlank() && !entry.destinationLabel.isNullOrBlank() ->
        "${entry.originLabel} → ${entry.destinationLabel}"
    !entry.destinationLabel.isNullOrBlank() -> entry.destinationLabel
    else -> ""
}

private fun candidateTitle(candidate: LinkableSearch): String =
    "${candidate.originLabel} → ${candidate.destinationLabel}"

private const val MERGE_WINDOW_MS = 6L * 60 * 60 * 1000
