package com.fuelroute.ui.debug

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.R
import com.fuelroute.domain.fuel.CalibrationAccuracy
import com.fuelroute.domain.fuel.CalibrationAccuracyBands
import com.fuelroute.domain.fuel.ModelConstants
import com.fuelroute.domain.learning.Calibration
import java.util.Locale

/**
 * Guided calibration screen (cards 18 + 33): walks the user from "how much linked data do I
 * have" through fitting, reviewing the suggested correction, and applying/rolling it back.
 * Raw numeric override editing is tucked behind an "מתקדם" expand, with the tank-to-tank
 * refuel calibration surfaced alongside. All model math lives in `domain/`.
 */
@Composable
fun CalibrationScreen(
    modifier: Modifier = Modifier,
    viewModel: CalibrationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var advanced by remember { mutableStateOf(false) }

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

        GuidedFlowCard(state = state, viewModel = viewModel)

        ParametersCard(
            state = state,
            advanced = advanced,
            onAdvancedChange = { advanced = it },
            viewModel = viewModel,
        )

        RefuelCalibrationCard(state = state)

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
private fun GuidedFlowCard(
    state: CalibrationUiState,
    viewModel: CalibrationViewModel,
) {
    val beforeAccuracy = CalibrationAccuracyBands.from(state.currentMape, state.pairCount)
    val afterAccuracy = CalibrationAccuracyBands.from(state.afterMape, state.pairCount)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.calibration_fit_title),
                style = MaterialTheme.typography.titleSmall,
            )

            // (a) How much linked data is there?
            StepTitle(number = 1, titleRes = R.string.calibration_step_data_title)
            if (state.pairCount == 0) {
                Text(
                    text = stringResource(R.string.calibration_no_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.calibration_linked_pairs, state.pairCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.calibration_accuracy_label,
                        accuracyLabel(beforeAccuracy),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = accuracyColor(beforeAccuracy),
                )
                if (state.pairCount < CalibrationAccuracyBands.MIN_PAIRS) {
                    Text(
                        text = stringResource(
                            R.string.calibration_too_few,
                            CalibrationAccuracyBands.MIN_PAIRS,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // (b) Run the fit.
            StepTitle(number = 2, titleRes = R.string.calibration_step_fit_title)
            Button(
                onClick = viewModel::fit,
                enabled = !state.isFitting && state.pairCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.calibration_fit))
            }

            // (c) Review the suggested factor + expected accuracy change.
            StepTitle(number = 3, titleRes = R.string.calibration_step_review_title)
            val factor = state.suggestedFactor
            val beforeMape = state.currentMape
            val afterMape = state.afterMape
            if (factor == null) {
                Text(
                    text = stringResource(R.string.calibration_review_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.calibration_result, format(factor), state.pairCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (beforeMape != null && afterMape != null) {
                    Text(
                        text = stringResource(
                            R.string.calibration_mape,
                            format(beforeMape),
                            format(afterMape),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.calibration_accuracy_label,
                            accuracyLabel(afterAccuracy),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = accuracyColor(afterAccuracy),
                    )
                }
            }

            // (d) Apply or discard.
            StepTitle(number = 4, titleRes = R.string.calibration_step_apply_title)
            if (factor != null) {
                Button(
                    onClick = viewModel::applySuggested,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.calibration_apply))
                }
                OutlinedButton(
                    onClick = viewModel::discardSuggested,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.calibration_discard))
                }
            } else if (state.previousFuelCorrection == null) {
                Text(
                    text = stringResource(R.string.calibration_apply_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.previousFuelCorrection != null) {
                OutlinedButton(
                    onClick = viewModel::rollback,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.calibration_rollback))
                }
            }
        }
    }
}

@Composable
private fun ParametersCard(
    state: CalibrationUiState,
    advanced: Boolean,
    onAdvancedChange: (Boolean) -> Unit,
    viewModel: CalibrationViewModel,
) {
    val rows = listOf(
        OverrideRowSpec(
            labelRes = R.string.calibration_slow_factor,
            provenanceRes = R.string.calibration_provenance_plan,
            raw = state.slowFactor,
            defaultValue = ModelConstants.SLOW_FACTOR,
            onRawChange = viewModel::onSlowFactorChange,
        ),
        OverrideRowSpec(
            labelRes = R.string.calibration_jam_factor,
            provenanceRes = R.string.calibration_provenance_plan,
            raw = state.jamFactor,
            defaultValue = ModelConstants.JAM_FACTOR,
            onRawChange = viewModel::onJamFactorChange,
        ),
        OverrideRowSpec(
            labelRes = R.string.calibration_stop_go,
            provenanceRes = R.string.calibration_provenance_plan,
            raw = state.stopGoWeight,
            defaultValue = ModelConstants.STOP_GO_WEIGHT,
            onRawChange = viewModel::onStopGoWeightChange,
        ),
        OverrideRowSpec(
            labelRes = R.string.calibration_cold_start,
            provenanceRes = R.string.calibration_provenance_plan,
            raw = state.coldStartDefaultL,
            defaultValue = ModelConstants.COLD_START_DEFAULT_L,
            onRawChange = viewModel::onColdStartChange,
        ),
        OverrideRowSpec(
            labelRes = R.string.calibration_idle_lph,
            provenanceRes = R.string.calibration_provenance_plan,
            raw = state.idleLphDefault,
            defaultValue = ModelConstants.IDLE_LPH_DEFAULT,
            onRawChange = viewModel::onIdleLphChange,
        ),
        OverrideRowSpec(
            labelRes = R.string.calibration_fuel_correction,
            provenanceRes = R.string.calibration_provenance_fit,
            raw = state.fuelCorrection,
            defaultValue = 1.0,
            onRawChange = viewModel::onFuelCorrectionChange,
        ),
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.calibration_params_title),
                style = MaterialTheme.typography.titleSmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::applyRecommendedPreset,
                    enabled = state.suggestedFactor != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.calibration_preset_recommended))
                }
                OutlinedButton(
                    onClick = viewModel::resetToDefaults,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.calibration_reset))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAdvancedChange(!advanced) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.calibration_advanced),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (advanced) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = null,
                )
            }
            if (advanced) {
                Text(
                    text = stringResource(R.string.calibration_advanced_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            rows.forEach { row -> OverrideRow(spec = row, advanced = advanced) }
        }
    }
}

@Composable
private fun OverrideRow(spec: OverrideRowSpec, advanced: Boolean) {
    val effective = spec.raw.toDoubleOrNull() ?: spec.defaultValue
    val overridden = spec.raw.toDoubleOrNull() != null

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(spec.labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.calibration_default_value,
                        format(spec.defaultValue),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(spec.provenanceRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = format(effective),
                style = MaterialTheme.typography.titleMedium,
                color = if (overridden) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        if (advanced) {
            OutlinedTextField(
                value = spec.raw,
                onValueChange = spec.onRawChange,
                label = { Text(stringResource(spec.labelRes)) },
                placeholder = { Text(format(spec.defaultValue)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RefuelCalibrationCard(state: CalibrationUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.calibration_refuel_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    R.string.calibration_refuel_current,
                    format(state.refuelCorrection),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val interval = state.refuelInterval
            if (interval == null) {
                Text(
                    text = stringResource(R.string.calibration_refuel_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.calibration_refuel_interval,
                        format(interval.pumpedLitres, 1),
                        format(interval.obdLitres, 1),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                when (val calibration = state.refuelCalibration) {
                    is Calibration.Exact -> Text(
                        text = stringResource(
                            R.string.calibration_refuel_exact,
                            format(calibration.factor),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is Calibration.Clamped -> Text(
                        text = stringResource(
                            R.string.calibration_refuel_clamped,
                            format(calibration.factor),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Calibration.Insufficient -> Text(
                        text = stringResource(R.string.calibration_refuel_insufficient),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    null -> Unit
                }
            }

            Text(
                text = stringResource(R.string.calibration_refuel_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepTitle(number: Int, @StringRes titleRes: Int) {
    Text(
        text = "$number. ${stringResource(titleRes)}",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun accuracyLabel(accuracy: CalibrationAccuracy): String = stringResource(
    when (accuracy) {
        CalibrationAccuracy.NONE -> R.string.calibration_accuracy_none
        CalibrationAccuracy.LOW -> R.string.calibration_accuracy_low
        CalibrationAccuracy.MEDIUM -> R.string.calibration_accuracy_medium
        CalibrationAccuracy.HIGH -> R.string.calibration_accuracy_high
    },
)

private fun accuracyColor(accuracy: CalibrationAccuracy): Color = when (accuracy) {
    CalibrationAccuracy.NONE -> Color(0xFF9E9E9E)
    CalibrationAccuracy.LOW -> Color(0xFFD9534F)
    CalibrationAccuracy.MEDIUM -> Color(0xFFE0A800)
    CalibrationAccuracy.HIGH -> Color(0xFF1B6B4A)
}

private data class OverrideRowSpec(
    @param:StringRes val labelRes: Int,
    @param:StringRes val provenanceRes: Int,
    val raw: String,
    val defaultValue: Double,
    val onRawChange: (String) -> Unit,
)

private fun format(value: Double): String = String.format(Locale.US, "%.3f", value)

private fun format(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)