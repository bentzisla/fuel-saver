package com.fuelroute.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.R
import java.util.Locale

/**
 * Debug-only calibration screen (card 18): edits the runtime fuel-model constants and fits a
 * global correction factor against already-logged linked drives.
 */
@Composable
fun CalibrationScreen(
    modifier: Modifier = Modifier,
    viewModel: CalibrationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.calibration_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.calibration_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!state.isLoaded) return@Column

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CalibrationField(
                    value = state.slowFactor,
                    labelRes = R.string.calibration_slow_factor,
                    onValueChange = viewModel::onSlowFactorChange,
                )
                CalibrationField(
                    value = state.jamFactor,
                    labelRes = R.string.calibration_jam_factor,
                    onValueChange = viewModel::onJamFactorChange,
                )
                CalibrationField(
                    value = state.stopGoWeight,
                    labelRes = R.string.calibration_stop_go,
                    onValueChange = viewModel::onStopGoWeightChange,
                )
                CalibrationField(
                    value = state.coldStartDefaultL,
                    labelRes = R.string.calibration_cold_start,
                    onValueChange = viewModel::onColdStartChange,
                )
                CalibrationField(
                    value = state.idleLphDefault,
                    labelRes = R.string.calibration_idle_lph,
                    onValueChange = viewModel::onIdleLphChange,
                )
                CalibrationField(
                    value = state.fuelCorrection,
                    labelRes = R.string.calibration_fuel_correction,
                    onValueChange = viewModel::onFuelCorrectionChange,
                )
                OutlinedButton(
                    onClick = viewModel::resetToDefaults,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.calibration_reset))
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.calibration_fit_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Button(
                    onClick = viewModel::fit,
                    enabled = !state.isFitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.calibration_fit))
                }

                val factor = state.suggestedFactor
                if (factor == null) {
                    if (state.pairCount == 0 && !state.isFitting) {
                        Text(
                            text = stringResource(R.string.calibration_no_data),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(
                            R.string.calibration_result,
                            format(factor),
                            state.pairCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val before = state.currentMape
                    val after = state.afterMape
                    if (before != null && after != null) {
                        Text(
                            text = stringResource(
                                R.string.calibration_mape,
                                format(before),
                                format(after),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = viewModel::applySuggested,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.calibration_apply))
                    }
                }
            }
        }

        state.message?.let { messageRes ->
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CalibrationField(
    value: String,
    labelRes: Int,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun format(value: Double): String = String.format(Locale.US, "%.3f", value)