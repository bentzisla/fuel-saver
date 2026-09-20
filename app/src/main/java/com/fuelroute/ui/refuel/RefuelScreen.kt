package com.fuelroute.ui.refuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.R
import com.fuelroute.domain.model.Refuel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RefuelScreen(
    modifier: Modifier = Modifier,
    viewModel: RefuelViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.refuel_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        OutlinedTextField(
            value = state.liters,
            onValueChange = viewModel::onLitersChange,
            label = { Text(stringResource(R.string.refuel_liters_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.totalPrice,
            onValueChange = viewModel::onPriceChange,
            label = { Text(stringResource(R.string.refuel_price_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.refuel_full),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.isFull, onCheckedChange = viewModel::onFullChange)
        }

        Button(
            onClick = viewModel::save,
            enabled = state.liters.isNotBlank() && state.totalPrice.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.refuel_save))
        }

        if (state.saved) {
            Text(
                text = stringResource(R.string.refuel_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (state.calibrationClamped) {
            Text(
                text = stringResource(R.string.refuel_calibration_clamped),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text(
            text = stringResource(R.string.refuel_correction, format(state.correction, 3)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(R.string.refuel_history),
            style = MaterialTheme.typography.titleMedium,
        )

        if (state.refuels.isEmpty()) {
            Text(
                text = stringResource(R.string.refuel_no_history),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.refuels.forEach { refuel ->
                RefuelRow(refuel)
            }
        }
    }

    if (state.showTankWarning) {
        AlertDialog(
            onDismissRequest = viewModel::dismissTankWarning,
            title = { Text(stringResource(R.string.refuel_over_capacity_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.refuel_over_capacity_message,
                        state.liters,
                        state.tankCapacityL?.let { format(it, 1) } ?: "—",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmTankWarning) {
                    Text(stringResource(R.string.refuel_over_capacity_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissTankWarning) {
                    Text(stringResource(R.string.vehicle_cancel))
                }
            },
        )
    }
}

@Composable
private fun RefuelRow(refuel: Refuel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = formatDate(refuel.timestampMs),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "${format(refuel.liters, 1)} L • ₪ ${format(refuel.totalPrice, 2)} • " +
                    "${
                        if (refuel.isFull) stringResource(R.string.refuel_full)
                        else stringResource(R.string.refuel_partial)
                    }",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochMs))

private fun format(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)