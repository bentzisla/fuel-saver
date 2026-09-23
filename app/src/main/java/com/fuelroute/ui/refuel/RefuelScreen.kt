package com.fuelroute.ui.refuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.R
import com.fuelroute.domain.model.Refuel
import com.fuelroute.ui.components.ConfirmDialog
import com.fuelroute.ui.components.DASH
import com.fuelroute.ui.components.Dimens
import com.fuelroute.ui.components.EmptyState
import com.fuelroute.ui.components.FuelTopBar
import com.fuelroute.ui.components.KeyValueRow
import com.fuelroute.ui.components.ListRow
import com.fuelroute.ui.components.PrimaryButton
import com.fuelroute.ui.components.SectionCard
import com.fuelroute.ui.components.SectionTitle
import com.fuelroute.ui.components.SwitchRow
import com.fuelroute.ui.components.fmt
import com.fuelroute.ui.components.formatDate
import com.fuelroute.ui.components.money

/** Log a fill-up in three fields; the history and the resulting correction factor sit below. */
@Composable
fun RefuelScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: RefuelViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val liters = state.liters.toDoubleOrNull()
    val total = state.totalPrice.toDoubleOrNull()

    Column(modifier = modifier.fillMaxSize()) {
        FuelTopBar(title = stringResource(R.string.refuel_title), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = Dimens.l, end = Dimens.l, bottom = Dimens.xl, top = Dimens.s),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            SectionCard {
                OutlinedTextField(
                    value = state.liters,
                    onValueChange = viewModel::onLitersChange,
                    label = { Text(stringResource(R.string.refuel_liters_label)) },
                    suffix = { Text(stringResource(R.string.vehicle_unit_liters)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.totalPrice,
                    onValueChange = viewModel::onPriceChange,
                    label = { Text(stringResource(R.string.refuel_price_label)) },
                    suffix = { Text("₪") },
                    supportingText = if (liters != null && total != null && liters > 0.0) {
                        { Text(stringResource(R.string.refuel_price_per_liter, fmt(total / liters, 2))) }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    title = stringResource(R.string.refuel_full),
                    subtitle = stringResource(R.string.refuel_full_hint),
                    checked = state.isFull,
                    onCheckedChange = viewModel::onFullChange,
                )
                PrimaryButton(
                    text = stringResource(R.string.refuel_save),
                    onClick = { viewModel.save() },
                    enabled = state.liters.isNotBlank() && state.totalPrice.isNotBlank(),
                )
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
                KeyValueRow(
                    label = stringResource(R.string.refuel_correction_label),
                    value = fmt(state.correction, 3),
                )
            }

            SectionTitle(stringResource(R.string.refuel_history))
            if (state.refuels.isEmpty()) {
                EmptyState(title = stringResource(R.string.refuel_no_history))
            } else {
                SectionCard(contentPadding = Dimens.s) {
                    Column {
                        state.refuels.forEachIndexed { index, refuel ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            RefuelRow(refuel)
                        }
                    }
                }
            }
        }
    }

    if (state.showTankWarning) {
        ConfirmDialog(
            title = stringResource(R.string.refuel_over_capacity_title),
            message = stringResource(
                R.string.refuel_over_capacity_message,
                state.liters,
                state.tankCapacityL?.let { fmt(it, 1) } ?: DASH,
            ),
            confirmLabel = stringResource(R.string.refuel_over_capacity_confirm),
            destructive = false,
            onConfirm = viewModel::confirmTankWarning,
            onDismiss = viewModel::dismissTankWarning,
        )
    }
}

@Composable
private fun RefuelRow(refuel: Refuel) {
    ListRow(
        title = formatDate(refuel.timestampMs),
        subtitle = "${fmt(refuel.liters, 1)} ${stringResource(R.string.vehicle_unit_liters)} · " +
            stringResource(if (refuel.isFull) R.string.refuel_full else R.string.refuel_partial) + " · " +
            stringResource(R.string.refuel_price_per_liter, fmt(refuel.pricePerLiter, 2)),
        modifier = Modifier.padding(horizontal = Dimens.s),
        trailing = { Text(text = money(refuel.totalPrice), style = MaterialTheme.typography.titleMedium) },
    )
}
