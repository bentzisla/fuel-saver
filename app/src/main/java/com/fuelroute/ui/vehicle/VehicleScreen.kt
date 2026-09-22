package com.fuelroute.ui.vehicle

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.fuelroute.data.price.FuelGrades
import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.VehicleProfile
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehicleScreen(
    modifier: Modifier = Modifier,
    onOpenRefuel: () -> Unit = {},
    viewModel: VehicleViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.vehicle_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        when {
            state.error -> VehicleLoadErrorCard(onRetry = viewModel::load)

            !state.isLoaded -> Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            state.showForm -> VehicleForm(state, viewModel)

            else -> {
                VehicleList(state, viewModel)

                Button(
                    onClick = viewModel::startAdd,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Text(stringResource(R.string.vehicle_add))
                }

                OutlinedButton(
                    onClick = onOpenRefuel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.vehicle_open_refuel))
                }

                if (state.saved) {
                    Text(
                        text = stringResource(R.string.vehicle_saved),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    state.pendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.vehicle_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.vehicle_delete_message,
                        pending.name.ifBlank { stringResource(R.string.vehicle_untitled) },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.vehicle_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text(stringResource(R.string.vehicle_cancel))
                }
            },
        )
    }
}

/** Shown when loading vehicles fails, so the screen never shows an empty list or a spinner. */
@Composable
private fun VehicleLoadErrorCard(onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.vehicle_error_load),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vehicle_retry))
            }
        }
    }
}

@Composable
private fun VehicleList(state: VehicleUiState, viewModel: VehicleViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.vehicles.forEach { vehicle ->
            VehicleRow(
                vehicle = vehicle,
                isActive = vehicle.id == state.activeId,
                canDelete = state.vehicles.size > 1,
                onSelect = { viewModel.select(vehicle.id) },
                onEdit = { viewModel.startEdit(vehicle) },
                onDelete = { viewModel.requestDelete(vehicle) },
            )
        }
    }
}

@Composable
private fun VehicleRow(
    vehicle: VehicleProfile,
    isActive: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = isActive, onClick = onSelect)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                Text(
                    text = vehicle.name.ifBlank { stringResource(R.string.vehicle_untitled) },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        R.string.vehicle_combined_short,
                        format(vehicle.ratedCombinedL100),
                    ) + " • " + stringResource(vehicle.fuelType.labelRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isActive) {
                    Text(
                        text = stringResource(R.string.vehicle_active),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.vehicle_edit))
            }
            IconButton(onClick = onDelete, enabled = canDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.vehicle_delete))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VehicleForm(state: VehicleUiState, viewModel: VehicleViewModel) {
    Text(
        text = stringResource(
            if (state.editingId == null) R.string.vehicle_new_title else R.string.vehicle_edit_title,
        ),
        style = MaterialTheme.typography.titleMedium,
    )

    OutlinedTextField(
        value = state.name,
        onValueChange = viewModel::onNameChange,
        label = { Text(stringResource(R.string.vehicle_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        text = stringResource(R.string.vehicle_fuel_type_label),
        style = MaterialTheme.typography.titleSmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FuelType.entries.forEach { type ->
            FilterChip(
                selected = state.fuelType == type,
                onClick = { viewModel.onFuelTypeSelect(type) },
                label = { Text(stringResource(type.labelRes())) },
            )
        }
    }

    Text(
        text = stringResource(R.string.vehicle_grade_label),
        style = MaterialTheme.typography.titleSmall,
    )
    if (state.fuelType == FuelType.DIESEL) {
        Text(
            text = stringResource(R.string.vehicle_grade_diesel_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FuelGrades.forFuelType(state.fuelType).forEach { grade ->
            FilterChip(
                selected = state.grade == grade,
                onClick = { viewModel.onGradeSelect(grade) },
                label = { Text(stringResource(gradeLabelRes(grade))) },
            )
        }
    }

    OutlinedTextField(
        value = state.ratedCombined,
        onValueChange = viewModel::onRatedCombinedChange,
        label = { Text(stringResource(R.string.vehicle_rated_combined_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = state.displacement,
        onValueChange = viewModel::onDisplacementChange,
        label = { Text(stringResource(R.string.vehicle_displacement_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = state.tankCapacity,
        onValueChange = viewModel::onTankCapacityChange,
        label = { Text(stringResource(R.string.vehicle_tank_capacity_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )

    Button(
        onClick = viewModel::save,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 14.dp),
    ) {
        Text(stringResource(R.string.vehicle_save))
    }

    OutlinedButton(
        onClick = viewModel::cancelEdit,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.vehicle_cancel))
    }
}

@StringRes
private fun FuelType.labelRes(): Int = when (this) {
    FuelType.GASOLINE -> R.string.fuel_gasoline
    FuelType.DIESEL -> R.string.fuel_diesel
    FuelType.HYBRID -> R.string.fuel_hybrid
}

@StringRes
private fun gradeLabelRes(grade: String): Int = when (grade) {
    FuelGrades.GASOLINE_98 -> R.string.vehicle_grade_98
    FuelGrades.DIESEL -> R.string.vehicle_grade_diesel
    else -> R.string.vehicle_grade_95
}

private fun format(value: Double): String = String.format(Locale.US, "%.1f", value)
