package com.fuelroute.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.R
import com.fuelroute.data.history.DriveHistoryEntry
import com.fuelroute.domain.history.PredictionAccuracy
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
                HistoryRow(entry)
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: DriveHistoryEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = routeTitle(entry).ifBlank { stringResource(R.string.history_unlinked_drive) },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = formatDate(entry.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    text = stringResource(R.string.history_predicted_price, format(predicted, 2)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (entry.hasActual && actual != null) {
                Text(
                    text = stringResource(R.string.history_actual_price, format(actual, 2)),
                    style = MaterialTheme.typography.bodyMedium,
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
                        pct?.let { signed(it) } ?: "—",
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (delta > 0.0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            val predictedLiters = entry.predictedLiters
            val actualLiters = entry.actualLiters
            if (predictedLiters != null || actualLiters != null) {
                Text(
                    text = stringResource(
                        R.string.history_liters,
                        predictedLiters?.let { format(it, 1) } ?: "—",
                        actualLiters?.let { format(it, 1) }
                            ?: stringResource(R.string.history_waiting_obd),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            entry.distanceKm?.let { distance ->
                Text(
                    text = stringResource(R.string.history_km, format(distance, 1)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (entry.isUndrivenSearch) {
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
        }
    }
}

private fun routeTitle(entry: DriveHistoryEntry): String = when {
    !entry.originLabel.isNullOrBlank() && !entry.destinationLabel.isNullOrBlank() ->
        "${entry.originLabel} → ${entry.destinationLabel}"
    !entry.destinationLabel.isNullOrBlank() -> entry.destinationLabel
    else -> ""
}

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))

private fun format(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

private fun signed(value: Double): String =
    String.format(Locale.US, "%+.1f", value)