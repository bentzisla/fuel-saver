package com.fuelroute.ui.stats

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.fuelroute.R
import com.fuelroute.data.obd.LiveObdState
import com.fuelroute.ui.components.StatTile
import com.fuelroute.ui.components.fmt

/**
 * THE single place the live-consumption number is chosen and rendered (dashboard hero tile).
 *
 * Merge note: the OBD work stream is changing how live consumption is exposed (a smoothed
 * L/100km, and L/h instead of L/100km at very low speed). Rewire ONLY this function: pick the
 * value/unit from the new [LiveObdState] fields here; nothing else in the UI reads
 * `instantL100` / `fuelRateLph` for the hero.
 */
@Composable
internal fun LiveConsumptionTile(state: LiveObdState, modifier: Modifier = Modifier) {
    val l100 = state.instantL100
    val lph = state.fuelRateLph
    val (value, unit) = when {
        l100 != null -> fmt(l100, 1) to stringResource(R.string.stats_units_l100)
        lph != null -> fmt(lph, 1) to stringResource(R.string.stats_units_lph)
        else -> "—" to stringResource(R.string.stats_units_l100)
    }
    StatTile(
        label = stringResource(R.string.stats_inst_l100),
        value = value,
        unit = unit,
        large = true,
        modifier = modifier,
    )
}
