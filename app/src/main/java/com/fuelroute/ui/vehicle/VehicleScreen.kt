package com.fuelroute.ui.vehicle

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.R
import com.fuelroute.data.price.FuelGrades
import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.VehicleProfile
import com.fuelroute.ui.components.ConfirmDialog
import com.fuelroute.ui.components.Dimens
import com.fuelroute.ui.components.ErrorCard
import com.fuelroute.ui.components.FuelTopBar
import com.fuelroute.ui.components.ListRow
import com.fuelroute.ui.components.PrimaryButton
import com.fuelroute.ui.components.SecondaryButton
import com.fuelroute.ui.components.SectionCard
import com.fuelroute.ui.components.SectionTitle
import com.fuelroute.ui.components.fmt

/**
 * "My car": the active vehicle at the top with its two companion tools (refuel log, consumption
 * curve), other vehicles below, and a focused add/edit form with unmistakable units.
 */
@Composable
fun VehicleScreen(
    modifier: Modifier = Modifier,
    onOpenRefuel: () -> Unit = {},
    onOpenCurve: () -> Unit = {},
    viewModel: VehicleViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        FuelTopBar(
            title = stringResource(
                when {
                    !state.showForm -> R.string.vehicle_title
                    state.editingId == null -> R.string.vehicle_new_title
                    else -> R.string.vehicle_edit_title
                },
            ),
            onBack = if (state.showForm) viewModel::cancelEdit else null,
            actions = {
                if (!state.showForm && state.isLoaded && !state.error) {
                    IconButton(onClick = viewModel::startAdd) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.vehicle_add))
                    }
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = Dimens.l, end = Dimens.l, bottom = Dimens.xl, top = Dimens.s),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            when {
                state.error -> ErrorCard(
                    message = stringResource(R.string.vehicle_error_load),
                    onRetry = viewModel::load,
                )

                !state.isLoaded -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.showForm -> VehicleForm(state, viewModel)

                else -> VehicleOverview(
                    state = state,
                    onOpenRefuel = onOpenRefuel,
                    onOpenCurve = onOpenCurve,
                    viewModel = viewModel,
                )
            }
        }
    }

    state.pendingDelete?.let { pending ->
        ConfirmDialog(
            title = stringResource(R.string.vehicle_delete_title),
            message = stringResource(
                R.string.vehicle_delete_message,
                pending.name.ifBlank { stringResource(R.string.vehicle_untitled) },
            ),
            confirmLabel = stringResource(R.string.vehicle_delete_confirm),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

@Composable
private fun VehicleOverview(
    state: VehicleUiState,
    onOpenRefuel: () -> Unit,
    onOpenCurve: () -> Unit,
    viewModel: VehicleViewModel,
) {
    val active = state.vehicles.firstOrNull { it.id == state.activeId }
    val others = state.vehicles.filter { it.id != state.activeId }

    if (state.saved) {
        Text(
            text = stringResource(R.string.vehicle_saved),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    active?.let { vehicle ->
        SectionCard(
            title = vehicle.name.ifBlank { stringResource(R.string.vehicle_untitled) },
            subtitle = vehicleSummary(vehicle),
            headerTrailing = {
                IconButton(onClick = { viewModel.startEdit(vehicle) }) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.vehicle_edit))
                }
            },
        ) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListRow(
                    title = stringResource(R.string.vehicle_open_refuel),
                    subtitle = stringResource(R.string.vehicle_refuel_hint),
                    leading = { Icon(painterResource(R.drawable.ic_gas_station), contentDescription = null) },
                    onClick = onOpenRefuel,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListRow(
                    title = stringResource(R.string.curve_open),
                    subtitle = stringResource(R.string.vehicle_curve_hint),
                    leading = { Icon(painterResource(R.drawable.ic_show_chart), contentDescription = null) },
                    onClick = onOpenCurve,
                )
            }
        }
    }

    if (others.isNotEmpty()) {
        SectionTitle(stringResource(R.string.vehicle_others_title))
        SectionCard(contentPadding = Dimens.s) {
            Column {
                others.forEachIndexed { index, vehicle ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ListRow(
                        title = vehicle.name.ifBlank { stringResource(R.string.vehicle_untitled) },
                        subtitle = vehicleSummary(vehicle),
                        leading = { Icon(painterResource(R.drawable.ic_car), contentDescription = null) },
                        onClick = { viewModel.select(vehicle.id) },
                        modifier = Modifier.padding(start = Dimens.s),
                        trailing = {
                            IconButton(onClick = { viewModel.startEdit(vehicle) }) {
                                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.vehicle_edit))
                            }
                            IconButton(onClick = { viewModel.requestDelete(vehicle) }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.vehicle_delete))
                            }
                        },
                    )
                }
                Text(
                    text = stringResource(R.string.vehicle_switch_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.s, vertical = Dimens.xs),
                )
            }
        }
    }

    SecondaryButton(text = stringResource(R.string.vehicle_add), onClick = viewModel::startAdd)
}

@Composable
private fun vehicleSummary(vehicle: VehicleProfile): String = buildString {
    append(stringResource(R.string.vehicle_combined_short, fmt(vehicle.ratedCombinedL100, 1)))
    append(" · ")
    append(stringResource(vehicle.fuelType.labelRes()))
    if (vehicle.fuelType != FuelType.DIESEL) append(" ${vehicle.grade}")
    vehicle.tankCapacityL?.let { append(" · ").append(stringResource(R.string.vehicle_tank_short, fmt(it, 0))) }
}

/**
 * Add/edit form. Every number field names its unit in the label AND as a suffix, shows an example,
 * and flags implausible values inline — in particular engine displacement typed in cc (1800)
 * instead of litres (1.8), which once made fuel rates 1000× too high. Save is disabled while any
 * field is flagged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VehicleForm(state: VehicleUiState, viewModel: VehicleViewModel) {
    val ratedIssue = VehicleInputRules.ratedCombinedIssue(state.ratedCombined)
    val displacementIssue = VehicleInputRules.displacementIssue(state.displacement)
    val tankIssue = VehicleInputRules.tankIssue(state.tankCapacity)
    val canSave = ratedIssue == null && displacementIssue == null && tankIssue == null

    OutlinedTextField(
        value = state.name,
        onValueChange = viewModel::onNameChange,
        label = { Text(stringResource(R.string.vehicle_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Text(text = stringResource(R.string.vehicle_fuel_type_label), style = MaterialTheme.typography.titleSmall)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        FuelType.entries.forEachIndexed { index, type ->
            SegmentedButton(
                selected = state.fuelType == type,
                onClick = { viewModel.onFuelTypeSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = FuelType.entries.size),
            ) {
                Text(stringResource(type.labelRes()))
            }
        }
    }

    val grades = FuelGrades.forFuelType(state.fuelType)
    if (grades.size > 1) {
        Text(text = stringResource(R.string.vehicle_grade_label), style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            grades.forEachIndexed { index, grade ->
                SegmentedButton(
                    selected = state.grade == grade,
                    onClick = { viewModel.onGradeSelect(grade) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = grades.size),
                ) {
                    Text(stringResource(gradeLabelRes(grade)))
                }
            }
        }
    }

    UnitField(
        value = state.ratedCombined,
        onValueChange = viewModel::onRatedCombinedChange,
        label = stringResource(R.string.vehicle_rated_combined_label),
        unit = stringResource(R.string.vehicle_unit_l100),
        placeholder = "6.5",
        help = stringResource(R.string.vehicle_rated_combined_help),
        error = ratedIssue?.let {
            stringResource(
                R.string.vehicle_error_range,
                fmt(VehicleInputRules.RATED_COMBINED_RANGE.start, 0),
                fmt(VehicleInputRules.RATED_COMBINED_RANGE.endInclusive, 0),
                stringResource(R.string.vehicle_unit_l100),
            )
        },
    )

    UnitField(
        value = state.tankCapacity,
        onValueChange = viewModel::onTankCapacityChange,
        label = stringResource(R.string.vehicle_tank_capacity_label),
        unit = stringResource(R.string.vehicle_unit_liters),
        placeholder = "50",
        help = stringResource(R.string.vehicle_tank_help),
        error = tankIssue?.let {
            stringResource(
                R.string.vehicle_error_range,
                fmt(VehicleInputRules.TANK_RANGE_L.start, 0),
                fmt(VehicleInputRules.TANK_RANGE_L.endInclusive, 0),
                stringResource(R.string.vehicle_unit_liters),
            )
        },
    )

    UnitField(
        value = state.displacement,
        onValueChange = viewModel::onDisplacementChange,
        label = stringResource(R.string.vehicle_displacement_label),
        unit = stringResource(R.string.vehicle_unit_liters),
        placeholder = "1.8",
        help = stringResource(R.string.vehicle_displacement_help),
        error = when (displacementIssue) {
            null -> null
            FieldIssue.LooksLikeCc -> stringResource(
                R.string.vehicle_displacement_error_cc,
                fmt(VehicleInputRules.ccToLitres(state.displacement) ?: 0.0, 1),
            )
            else -> stringResource(
                R.string.vehicle_error_range,
                fmt(VehicleInputRules.DISPLACEMENT_RANGE_L.start, 1),
                fmt(VehicleInputRules.DISPLACEMENT_RANGE_L.endInclusive, 1),
                stringResource(R.string.vehicle_unit_liters),
            )
        },
    )

    PrimaryButton(
        text = stringResource(R.string.vehicle_save),
        onClick = viewModel::save,
        enabled = canSave,
    )
    TextButton(onClick = viewModel::cancelEdit, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.common_cancel))
    }
    val editing = state.vehicles.firstOrNull { it.id == state.editingId }
    if (editing != null && state.vehicles.size > 1) {
        TextButton(onClick = { viewModel.requestDelete(editing) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.vehicle_delete_title), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** A decimal field that states its unit twice (label + suffix) and shows help or an error. */
@Composable
private fun UnitField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    unit: String,
    placeholder: String,
    help: String,
    error: String?,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.vehicle_field_with_unit, label, unit)) },
        placeholder = { Text(placeholder) },
        suffix = { Text(unit) },
        supportingText = { Text(error ?: help) },
        isError = error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
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
