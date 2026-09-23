package com.fuelroute.ui.route

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fuelroute.R
import com.fuelroute.domain.model.CongestionLevel
import com.fuelroute.domain.model.RouteCost
import com.fuelroute.ui.components.Dimens
import com.fuelroute.ui.components.fmt
import com.fuelroute.ui.theme.FuelTheme
import kotlin.math.ceil

private data class SpeedProfilePoint(val distanceKm: Double, val speedKmh: Double)

/** Cumulative-distance/speed profile for the route graph. */
private fun speedProfile(cost: RouteCost): List<SpeedProfilePoint> {
    if (cost.segments.isEmpty()) {
        return listOf(SpeedProfilePoint(cost.distanceKm.coerceAtLeast(0.0), cost.avgSpeedKmh))
    }
    var cumulative = 0.0
    return cost.segments.map { segment ->
        cumulative += segment.distanceKm
        SpeedProfilePoint(cumulative, segment.effectiveSpeedKmh)
    }
}

private val GraphYAxisWidth = 40.dp

/** Rounds the graph's top speed to a friendly tick so the Y-axis labels stay readable. */
private fun niceSpeedMax(raw: Double): Double {
    val value = raw.coerceAtLeast(10.0)
    val step = when {
        value <= 30.0 -> 10.0
        value <= 60.0 -> 20.0
        value <= 120.0 -> 30.0
        else -> 50.0
    }
    return ceil(value / step) * step
}

@Composable
internal fun congestionColor(level: CongestionLevel): Color = when (level) {
    CongestionLevel.NORMAL -> FuelTheme.colors.trafficNormal
    CongestionLevel.SLOW -> FuelTheme.colors.trafficSlow
    CongestionLevel.TRAFFIC_JAM -> FuelTheme.colors.trafficJam
}

@StringRes
internal fun CongestionLevel.labelRes(): Int = when (this) {
    CongestionLevel.NORMAL -> R.string.congestion_normal
    CongestionLevel.SLOW -> R.string.congestion_slow
    CongestionLevel.TRAFFIC_JAM -> R.string.congestion_jam
}

/**
 * Speed-vs-distance graph: labelled km/h (Y) and km (X) axes, a congestion colour band under a
 * smoothed speed line, and a traffic legend. Distance charts are inherently LTR (distance grows
 * to the right), so the whole graph is laid out LTR even under the Hebrew locale — otherwise the
 * mirrored axis labels would disagree with the plotted curve.
 */
@Composable
internal fun RouteSpeedGraph(
    cost: RouteCost,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 160.dp,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        RouteSpeedGraphContent(cost = cost, modifier = modifier, chartHeight = chartHeight)
    }
}

@Composable
private fun RouteSpeedGraphContent(
    cost: RouteCost,
    modifier: Modifier,
    chartHeight: Dp,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val colorNormal = congestionColor(CongestionLevel.NORMAL)
    val colorSlow = congestionColor(CongestionLevel.SLOW)
    val colorJam = congestionColor(CongestionLevel.TRAFFIC_JAM)
    fun colorOf(level: CongestionLevel): Color = when (level) {
        CongestionLevel.NORMAL -> colorNormal
        CongestionLevel.SLOW -> colorSlow
        CongestionLevel.TRAFFIC_JAM -> colorJam
    }
    val points = remember(cost) { speedProfile(cost) }
    val maxDistance = (points.maxOfOrNull { it.distanceKm } ?: 0.0).coerceAtLeast(0.1)
    val maxSpeed = niceSpeedMax(points.maxOfOrNull { it.speedKmh } ?: 0.0)
    val labelStyle = MaterialTheme.typography.labelSmall

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Text(
            text = stringResource(R.string.route_detail_graph_speed_axis),
            style = labelStyle,
            color = labelColor,
        )
        Row {
            Column(
                modifier = Modifier
                    .width(GraphYAxisWidth)
                    .height(chartHeight)
                    .padding(end = Dimens.xs),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(fmt(maxSpeed, 0), style = labelStyle, color = labelColor)
                Text(fmt(maxSpeed / 2.0, 0), style = labelStyle, color = labelColor)
                Text("0", style = labelStyle, color = labelColor)
            }
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(chartHeight),
            ) {
                if (points.isEmpty()) return@Canvas
                fun x(distanceKm: Double): Float = (distanceKm / maxDistance * size.width).toFloat()
                fun y(speedKmh: Double): Float =
                    (size.height - (speedKmh / maxSpeed).coerceIn(0.0, 1.0) * size.height).toFloat()

                listOf(0.0, 0.5, 1.0).forEach { fraction ->
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, size.height * fraction.toFloat()),
                        end = Offset(size.width, size.height * fraction.toFloat()),
                        strokeWidth = if (fraction == 1.0) 2f else 1f,
                    )
                }

                // Congestion colour band under the line, one quad per segment.
                for (index in 0 until points.size - 1) {
                    val start = points[index]
                    val end = points[index + 1]
                    val level = cost.segments.getOrNull(index + 1)?.congestion
                        ?: cost.segments.getOrNull(index)?.congestion
                        ?: CongestionLevel.NORMAL
                    val band = Path().apply {
                        moveTo(x(start.distanceKm), size.height)
                        lineTo(x(start.distanceKm), y(start.speedKmh))
                        lineTo(x(end.distanceKm), y(end.speedKmh))
                        lineTo(x(end.distanceKm), size.height)
                        close()
                    }
                    drawPath(path = band, color = colorOf(level).copy(alpha = 0.28f))
                }

                if (points.size == 1) {
                    drawCircle(
                        color = lineColor,
                        radius = 4.dp.toPx(),
                        center = Offset(x(points[0].distanceKm), y(points[0].speedKmh)),
                    )
                } else {
                    val line = Path().apply {
                        moveTo(x(points[0].distanceKm), y(points[0].speedKmh))
                        for (index in 1 until points.size - 1) {
                            val point = points[index]
                            val next = points[index + 1]
                            val midX = (x(point.distanceKm) + x(next.distanceKm)) / 2f
                            val midY = (y(point.speedKmh) + y(next.speedKmh)) / 2f
                            quadraticTo(x(point.distanceKm), y(point.speedKmh), midX, midY)
                        }
                        val last = points.last()
                        lineTo(x(last.distanceKm), y(last.speedKmh))
                    }
                    drawPath(
                        path = line,
                        color = lineColor,
                        style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.width(GraphYAxisWidth))
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0.0, 0.5, 1.0).forEach { fraction ->
                    Text(text = fmt(maxDistance * fraction, 0), style = labelStyle, color = labelColor)
                }
            }
        }
        Text(
            text = stringResource(R.string.route_detail_graph_distance_axis),
            style = labelStyle,
            color = labelColor,
            modifier = Modifier.align(Alignment.End),
        )
        CongestionLegend()
    }
}

@Composable
private fun CongestionLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CongestionLevel.entries.forEach { level ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(congestionColor(level), CircleShape),
                )
                Text(
                    text = stringResource(level.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
