package com.fuelroute.ui.route

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.fuelroute.R
import com.fuelroute.domain.model.RouteCost
import com.fuelroute.domain.model.SegmentCost
import com.fuelroute.domain.model.TrafficResolution
import com.fuelroute.ui.components.Dimens
import com.fuelroute.ui.components.ExpandableSection
import com.fuelroute.ui.components.KeyValueRow
import com.fuelroute.ui.components.PrimaryButton
import com.fuelroute.ui.components.SecondaryButton
import com.fuelroute.ui.components.SectionTitle
import com.fuelroute.ui.components.fmt
import com.fuelroute.ui.components.formatTime
import com.fuelroute.ui.components.money

/**
 * Everything about one route that does not belong on the results page: the cost breakdown, why
 * it was ranked where it is, the speed profile and the per-segment table. Navigate stays one tap
 * away at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteDetailSheet(
    cost: RouteCost,
    index: Int,
    comparison: RouteComparison?,
    departureMs: Long,
    isWaze: Boolean,
    onNavigate: () -> Unit,
    onDeparted: () -> Unit,
    onDismiss: () -> Unit,
    learnedKm: Double = 0.0,
    hasManualCurve: Boolean = false,
    fuelCorrectionFactor: Double = 1.0,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pricePerLiter = if (cost.fuelLiters > 0.0) cost.fuelCost / cost.fuelLiters else 0.0
    val etaMs = departureMs + (cost.durationMinutes * 60_000.0).toLong()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier.navigationBarsPadding(),
            contentPadding = PaddingValues(start = Dimens.l, end = Dimens.l, bottom = Dimens.xl),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            item {
                RouteTitleRow(cost = cost, index = index, comparison = comparison, recommended = index == 0)
            }
            item {
                RouteHeadline(cost = cost, comparison = comparison, etaMs = etaMs)
            }
            if (isWaze) {
                item {
                    Text(
                        text = stringResource(R.string.nav_waze_destination_only),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    PrimaryButton(
                        text = stringResource(R.string.route_navigate),
                        onClick = onNavigate,
                        icon = painterResource(R.drawable.ic_navigation),
                    )
                    SecondaryButton(
                        text = stringResource(R.string.route_departed_button),
                        onClick = onDeparted,
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.route_detail_title)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    KeyValueRow(
                        label = stringResource(
                            R.string.route_detail_fuel_label,
                            fmt(cost.fuelLiters, 2),
                            fmt(pricePerLiter, 2),
                        ),
                        value = money(cost.fuelCost),
                    )
                    // Self-diagnosing breakdown (bug report investigation: an absurd cost is
                    // otherwise invisible until someone reads the source). Shows what the curve
                    // is based on and, only when it differs from 1.0, the calibration factor
                    // that is the most likely source of a wildly wrong number.
                    KeyValueRow(
                        label = stringResource(R.string.route_detail_consumption_label),
                        value = stringResource(
                            R.string.route_detail_consumption_value,
                            fmt(if (cost.distanceKm > 0.0) cost.fuelLiters / cost.distanceKm * 100.0 else 0.0, 1),
                        ),
                    )
                    KeyValueRow(
                        label = stringResource(R.string.route_detail_curve_source_label),
                        value = when {
                            learnedKm > 0.0 && hasManualCurve ->
                                stringResource(R.string.route_detail_curve_source_learned_manual, fmt(learnedKm, 0))
                            learnedKm > 0.0 ->
                                stringResource(R.string.route_detail_curve_source_learned, fmt(learnedKm, 0))
                            hasManualCurve -> stringResource(R.string.route_detail_curve_source_manual)
                            else -> stringResource(R.string.route_detail_curve_source_default)
                        },
                    )
                    if (kotlin.math.abs(fuelCorrectionFactor - 1.0) > 0.005) {
                        KeyValueRow(
                            label = stringResource(R.string.route_detail_correction_label),
                            value = stringResource(R.string.route_detail_correction_value, fmt(fuelCorrectionFactor, 2)),
                        )
                    }
                    if (cost.route.gradeDataMissing) {
                        Text(
                            text = stringResource(R.string.route_grade_missing_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    KeyValueRow(
                        label = stringResource(R.string.route_toll_label),
                        value = when {
                            cost.route.tollUnknown -> stringResource(R.string.route_toll_unknown)
                            cost.tollCost <= RouteHighlights.MONEY_EPSILON -> stringResource(R.string.route_toll_free)
                            else -> money(cost.tollCost)
                        },
                        valueColor = if (cost.route.tollUnknown) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    HorizontalDivider()
                    KeyValueRow(
                        label = stringResource(R.string.route_total_cost_label),
                        value = money(cost.totalCost),
                        emphasize = true,
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.route_detail_trip_title)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    KeyValueRow(
                        label = stringResource(R.string.route_time_label),
                        value = stringResource(R.string.route_duration_minutes, fmt(cost.durationMinutes, 0)),
                    )
                    KeyValueRow(
                        label = stringResource(R.string.route_distance_label),
                        value = "${fmt(cost.distanceKm, 1)} ${stringResource(R.string.route_units_km)}",
                    )
                    KeyValueRow(
                        label = stringResource(R.string.route_avg_speed_label),
                        value = "${fmt(cost.avgSpeedKmh, 0)} ${stringResource(R.string.route_units_kmh)}",
                    )
                    KeyValueRow(
                        label = stringResource(R.string.route_eta_label),
                        value = formatTime(etaMs),
                    )
                    KeyValueRow(
                        label = stringResource(R.string.route_traffic_title),
                        value = stringResource(cost.route.trafficResolution.labelRes()),
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.route_detail_graph_title)) }
            item { RouteSpeedGraph(cost = cost, modifier = Modifier.fillMaxWidth()) }

            if (cost.segments.isNotEmpty()) {
                item {
                    ExpandableSection(
                        title = stringResource(R.string.route_detail_segments_title, cost.segments.size),
                    ) {
                        cost.segments.forEachIndexed { segmentIndex, segment ->
                            SegmentRow(index = segmentIndex, segment = segment)
                        }
                    }
                }
            }
        }
    }
}

/** "① המסלול הראשי  [מומלץ] [הזול ביותר]" */
@Composable
internal fun RouteTitleRow(
    cost: RouteCost,
    index: Int,
    comparison: RouteComparison?,
    recommended: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
    ) {
        RouteNumberDot(index = index)
        Text(
            text = stringResource(cost.route.routeLabelRes()),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        RoutePills(comparison = comparison, recommended = recommended)
    }
}

@Composable
private fun SegmentRow(index: Int, segment: SegmentCost) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.m),
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${fmt(segment.distanceKm, 1)} ${stringResource(R.string.route_units_km)} · " +
                    stringResource(segment.congestion.labelRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = congestionColor(segment.congestion),
            )
            Text(
                text = "${fmt(segment.effectiveSpeedKmh, 0)} ${stringResource(R.string.route_units_kmh)} · " +
                    "${fmt(segment.litersPer100Km, 1)} ${stringResource(R.string.route_detail_l100)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${fmt(segment.liters, 2)} ${stringResource(R.string.route_detail_liters)}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@StringRes
private fun TrafficResolution.labelRes(): Int = when (this) {
    TrafficResolution.PER_SEGMENT -> R.string.route_traffic_per_segment
    TrafficResolution.ROUTE_AVERAGE -> R.string.route_traffic_route_average
    TrafficResolution.NONE -> R.string.route_traffic_none
}
