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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.R
import com.fuelroute.data.history.DriveHistoryEntry
import com.fuelroute.data.history.LinkableSearch
import com.fuelroute.data.history.RideState
import com.fuelroute.domain.history.PredictionAccuracy
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
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
                    onClick = { viewModel.openDetail(entry) },
                    onLink = { viewModel.openManualLink(entry) },
                    onDelete = { viewModel.requestDelete(entry) },
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
    onClick: () -> Unit,
    onLink: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.history_delete_title),
                        tint = MaterialTheme.colorScheme.error,
                    )
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
