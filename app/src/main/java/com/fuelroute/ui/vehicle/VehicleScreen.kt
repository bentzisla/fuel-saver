package com.fuelroute.ui.vehicle

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import com.fuelroute.domain.model.FuelType

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

@StringRes
private fun FuelType.labelRes(): Int = when (this) {
    FuelType.GASOLINE -> R.string.fuel_gasoline
    FuelType.DIESEL -> R.string.fuel_diesel
    FuelType.HYBRID -> R.string.fuel_hybrid
}