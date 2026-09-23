package com.fuelroute.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    state.linkFeedback?.let { feedback ->
        LaunchedEffect(feedback) {
            delay(FEEDBACK_VISIBLE_MS)
            viewModel.clearLinkFeedback()
        }
    }

    state.mergeSplitFeedback?.let { feedback ->
        LaunchedEffect(feedback) {
            delay(FEEDBACK_VISIBLE_MS)
            viewModel.clearMergeSplitFeedback()
        }
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

    state.selectedEntry?.let { entry ->
        RideDetailDialog(
            entry = entry,
            onDismiss = viewModel::dismissDetail,
        )
    }

    if (state.pendingDelete != null) {
        DeleteRideDialog(
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
        ConfirmActionDialog(
            title = stringResource(R.string.history_bulk_delete_title),
            message = stringResource(R.string.history_bulk_delete_message, state.selectedIds.size),
            onConfirm = viewModel::confirmBulkDelete,
            onDismiss = viewModel::cancelBulkDelete,
        )
    }

    state.mergeSplitEntry?.let { entry ->
        MergeSplitDialog(
            entry = entry,
            candidates = mergeCandidates(state.entries, entry),
            onMerge = viewModel::requestMerge,
            onSplit = { splitAtMs -> viewModel.requestSplit(entry.tripId!!, splitAtMs) },
            onDismiss = viewModel::dismissMergeSplit,
        )
    }

    if (state.pendingMerge != null) {
        ConfirmActionDialog(
            title = stringResource(R.string.history_merge_confirm_title),
            message = stringResource(R.string.history_merge_confirm_message),
            onConfirm = viewModel::confirmMerge,
            onDismiss = viewModel::cancelMerge,
        )
    }

    if (state.pendingSplit != null) {
        ConfirmActionDialog(
            title = stringResource(R.string.history_split_confirm_title),
            message = stringResource(R.string.history_split_confirm_message),
            onConfirm = viewModel::confirmSplit,
            onDismiss = viewModel::cancelSplit,
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.history_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    TextButton(onClick = viewModel::toggleSelectionMode) {
                        Text(
                            stringResource(
                                if (state.selectionMode) {
                                    R.string.history_select_done
                                } else {
                                    R.string.history_select
                                },
                            ),
                        )
                    }
                }
                if (state.selectionMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.history_selected_count,
                                state.selectedIds.size,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        TextButton(
                            onClick = viewModel::requestBulkDelete,
                            enabled = state.selectedIds.isNotEmpty(),
                        ) {
                            Text(stringResource(R.string.history_bulk_delete_action))
                        }
                    }
                }
                state.accuracyPct?.let { accuracy ->
                    Text(
                        text = stringResource(R.string.history_accuracy, format(accuracy, 1)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                val totalSaved = state.entries.filter { it.hasActual }.sumOf { it.savedAmount }
                if (totalSaved > 0.0) {
                    Text(
                        text = stringResource(R.string.history_saved_total, format(totalSaved, 2)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        state.linkFeedback?.let { feedback ->
            item {
                Text(
                    text = stringResource(
                        when (feedback) {
                            LinkFeedback.LINKED -> R.string.history_link_linked
                            LinkFeedback.NONE_FOUND -> R.string.history_link_none
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        state.mergeSplitFeedback?.let { feedback ->
            item {
                Text(
                    text = stringResource(
                        when (feedback) {
                            MergeSplitFeedback.MERGED -> R.string.history_merge_split_merged
                            MergeSplitFeedback.SPLIT -> R.string.history_merge_split_split
                            MergeSplitFeedback.FAILED -> R.string.history_merge_split_failed
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (feedback == MergeSplitFeedback.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }

        if (state.isLoading) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (state.entries.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.entries) { entry ->
                HistoryRideCard(
                    entry = entry,
                    selectionMode = state.selectionMode,
                    selected = entry.selectionId?.let { it in state.selectedIds } ?: false,
                    onClick = {
                        if (state.selectionMode) {
                            viewModel.toggleSelection(entry)
                        } else {
                            viewModel.openDetail(entry)
                        }
                    },
                    onToggleSelect = { viewModel.toggleSelection(entry) },
                    onLink = { viewModel.openManualLink(entry) },
                    onDelete = { viewModel.requestDelete(entry) },
                    onManualCost = { viewModel.openManualCost(entry) },
                    onMergeSplit = { viewModel.openMergeSplit(entry) },
                )
            }
        }
    }
}

/**
 * One combined "ride": the recommended route (predicted ₪/L/min) and the measured OBD
 * outcome (actual ₪/L/min) with a delta and a state label.
 */
@Composable
private fun HistoryRideCard(
    entry: DriveHistoryEntry,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onLink: () -> Unit,
    onDelete: () -> Unit,
    onManualCost: () -> Unit,
    onMergeSplit: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect() },
                    )
                }
                Text(
                    text = routeTitle(entry).ifBlank { stringResource(R.string.history_unlinked_drive) },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatDate(entry.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Own click handler so the delete tap never also opens the detail dialog.
                if (!selectionMode) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.history_delete_title),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RideStateLabel(entry.rideState)
                if (entry.isDemo) {
                    DemoBadge()
                }
            }

            if (entry.hasManualEntry) {
                Text(
                    text = stringResource(R.string.history_manual_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (entry.hasActual && entry.savedAmount > 0.0) {
                Text(
                    text = stringResource(
                        R.string.history_saving_vs_fastest,
                        format(entry.savedAmount, 2),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            val predicted = entry.predictedCost
            val actual = entry.actualCost
            if (predicted != null) {
                Text(
                    text = stringResource(
                        R.string.history_ride_predicted,
                        format(predicted, 2),
                        entry.predictedLiters?.let { format(it, 1) } ?: DASH,
                        entry.predictedMinutes?.let { format(it, 0) } ?: DASH,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (entry.hasActual && actual != null) {
                Text(
                    text = stringResource(
                        R.string.history_ride_actual,
                        format(actual, 2),
                        entry.actualLiters?.let { format(it, 1) } ?: DASH,
                        entry.actualMinutes?.let { format(it, 0) } ?: DASH,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (predicted != null) {
                Text(
                    text = stringResource(R.string.history_ride_actual_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (predicted != null && actual != null && predicted > 0.0) {
                val delta = actual - predicted
                val pct = PredictionAccuracy.errorPct(predicted, actual)
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                Text(
                    text = stringResource(
                        R.string.history_delta,
                        signed(delta),
                        pct?.let { signed(it) } ?: DASH,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (delta > 0.0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            entry.distanceKm?.let { distance ->
                Text(
                    text = stringResource(R.string.history_km, format(distance, 1)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (entry.isUndrivenSearch && entry.tripId == null) {
                Text(
                    text = stringResource(R.string.history_type_search),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.history_not_driven),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!selectionMode && (entry.canEnterManualCost || entry.tripId != null)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    if (entry.canEnterManualCost) {
                        OutlinedButton(onClick = onManualCost) {
                            Text(stringResource(R.string.history_manual_action))
                        }
                    }
                    if (entry.tripId != null) {
                        OutlinedButton(onClick = onMergeSplit) {
                            Text(stringResource(R.string.history_merge_split_action))
                        }
                    }
                }
            }

            if (entry.canLinkManually) {
                OutlinedButton(
                    onClick = onLink,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(stringResource(R.string.history_link_ride))
                }
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.history_manual_price, format(pricePerLiter, 2)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    estimate?.let { preview ->
                        Text(
                            text = stringResource(
                                R.string.history_manual_preview,
                                format(preview.liters, 2),
                                format(preview.cost, 2),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (hasInput && !canSave) {
                    Text(
                        text = stringResource(R.string.history_manual_invalid),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { (directInput ?: consumptionInput)?.let(onSave) },
                enabled = canSave,
            ) {
                Text(stringResource(R.string.history_manual_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.history_manual_cancel))
            }
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
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.history_merge_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (candidates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.history_link_dialog_empty),
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
                                    if (checked) selected.add(candidateId)
                                    else selected.remove(candidateId)
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = formatDate(candidate.timestampMs),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                candidate.distanceKm?.let { distance ->
                                    Text(
                                        text = stringResource(R.string.history_km, format(distance, 1)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(
                    onClick = { onMerge(selected.toList()) },
                    enabled = selected.size >= 2,
                ) {
                    Text(stringResource(R.string.history_merge_action))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // The split anchor needs the real trip window; an undriven search has none.
                if (canSplit && splitAtMs != null && splitStartedAtMs != null && splitEndedAtMs != null) {
                    Text(
                        text = stringResource(R.string.history_split_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(
                            R.string.history_split_point,
                            formatDate(splitAtMs),
                        ),
                        style = MaterialTheme.typography.labelMedium,
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.history_merge_split_close))
            }
        },
    )
}

/** Generic destructive-action confirmation used by bulk delete, merge and split. */
@Composable
private fun ConfirmActionDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.history_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.history_delete_cancel))
            }
        },
    )
}

/** Marks a simulated "הדגמה" ride so it is never mistaken for a real one. */
@Composable
private fun DemoBadge() {
    Text(
        text = stringResource(R.string.history_demo_badge),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun RideStateLabel(state: RideState) {
    val (textRes, color) = when (state) {
        RideState.LINKED ->
            R.string.history_ride_state_linked to MaterialTheme.colorScheme.primary
        RideState.WAITING_OBD ->
            R.string.history_ride_state_waiting to MaterialTheme.colorScheme.onSurfaceVariant
        RideState.NO_PREDICTION ->
            R.string.history_ride_state_no_prediction to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}

/**
 * Full predicted-vs-actual breakdown for one ride, opened by tapping a history card.
 * Delete lives in a later card, so this dialog is read-only.
 */
@Composable
private fun RideDetailDialog(entry: DriveHistoryEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_detail_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = routeTitle(entry).ifBlank {
                        stringResource(R.string.history_detail_unlinked)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = formatDate(entry.timestampMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RideStateLabel(entry.rideState)
                    if (entry.isDemo) {
                        DemoBadge()
                    }
                }

                if (entry.hasManualEntry) {
                    Text(
                        text = stringResource(R.string.history_manual_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                val predicted = entry.predictedCost
                val actual = entry.actualCost

                if (predicted != null) {
                    Text(
                        text = stringResource(
                            R.string.history_ride_predicted,
                            format(predicted, 2),
                            entry.predictedLiters?.let { format(it, 1) } ?: DASH,
                            entry.predictedMinutes?.let { format(it, 0) } ?: DASH,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.history_ride_state_no_prediction),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (entry.hasActual && actual != null) {
                    Text(
                        text = stringResource(
                            R.string.history_ride_actual,
                            format(actual, 2),
                            entry.actualLiters?.let { format(it, 1) } ?: DASH,
                            entry.actualMinutes?.let { format(it, 0) } ?: DASH,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (predicted != null) {
                    Text(
                        text = stringResource(R.string.history_ride_actual_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (predicted != null && actual != null && predicted > 0.0) {
                    val delta = actual - predicted
                    val pct = PredictionAccuracy.errorPct(predicted, actual)
                    Text(
                        text = stringResource(
                            R.string.history_delta,
                            signed(delta),
                            pct?.let { signed(it) } ?: DASH,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (delta > 0.0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }

                entry.distanceKm?.let { distance ->
                    Text(
                        text = stringResource(R.string.history_km, format(distance, 1)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                entry.pricePerLiterAtSearch?.let { price ->
                    Text(
                        text = stringResource(
                            R.string.history_detail_price_search,
                            format(price, 2),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                entry.pricePerLiterAtTrip?.let { price ->
                    Text(
                        text = stringResource(
                            R.string.history_detail_price_trip,
                            format(price, 2),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.history_detail_close))
            }
        },
    )
}

/** Confirmation shown before a History entry (and its underlying row) is deleted. */
@Composable
private fun DeleteRideDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_delete_title)) },
        text = { Text(stringResource(R.string.history_delete_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.history_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.history_delete_cancel))
            }
        },
    )
}

/** Picker shown by "קשר נסיעה" so the user can pair an unlinked drive with a search. */
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                            TextButton(
                                onClick = { onPick(candidate.id) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = candidateTitle(candidate),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = formatDate(candidate.timestampMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.history_link_cancel))
            }
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

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))

private fun format(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

private fun signed(value: Double): String =
    String.format(Locale.US, "%+.1f", value)

private const val DASH = "—"
private const val FEEDBACK_VISIBLE_MS = 3_000L
private const val MERGE_WINDOW_MS = 6L * 60 * 60 * 1000
