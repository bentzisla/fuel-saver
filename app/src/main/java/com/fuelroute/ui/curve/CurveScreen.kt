package com.fuelroute.ui.curve

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.R
import com.fuelroute.domain.model.SpeedPoint
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Destructive actions that must be confirmed before they touch stored data. */
private enum class PendingCurveAction { AdoptLearned, ResetLearning, ClearManual }

@Composable
fun CurveScreen(
    modifier: Modifier = Modifier,
    viewModel: CurveViewModel = hiltViewModel(),
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
            text = stringResource(R.string.curve_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            SummaryCard(state)
            ChartCard(state)
            ActionsCard(state, viewModel)
        }
    }
}

@Composable
private fun ActionsCard(state: CurveUiState, viewModel: CurveViewModel) {
    var pending by remember { mutableStateOf<PendingCurveAction?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { pending = PendingCurveAction.AdoptLearned },
            enabled = state.hasLearnedData,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.curve_adopt))
        }
        OutlinedButton(
            onClick = { pending = PendingCurveAction.ResetLearning },
            enabled = state.hasLearnedData,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.curve_reset_learning))
        }
        OutlinedButton(
            onClick = { pending = PendingCurveAction.ClearManual },
            enabled = state.hasManualCurve,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.curve_clear_manual))
        }
    }

    when (pending) {
        PendingCurveAction.AdoptLearned -> ConfirmDestructiveDialog(
            title = stringResource(R.string.curve_adopt_confirm_title),
            message = stringResource(R.string.curve_adopt_confirm_message),
            onConfirm = {
                viewModel.adoptLearned()
                pending = null
            },
            onDismiss = { pending = null },
        )

        PendingCurveAction.ResetLearning -> ConfirmDestructiveDialog(
            title = stringResource(R.string.curve_reset_confirm_title),
            message = stringResource(R.string.curve_reset_confirm_message),
            onConfirm = {
                viewModel.resetLearning()
                pending = null
            },
            onDismiss = { pending = null },
        )

        PendingCurveAction.ClearManual -> ConfirmDestructiveDialog(
            title = stringResource(R.string.curve_clear_manual_confirm_title),
            message = stringResource(R.string.curve_clear_manual_confirm_message),
            onConfirm = {
                viewModel.clearManual()
                pending = null
            },
            onDismiss = { pending = null },
        )

        null -> Unit
    }
}

@Composable
private fun ConfirmDestructiveDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.curve_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.curve_delete_cancel))
            }
        },
    )
}

@Composable
private fun SummaryCard(state: CurveUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.totalKm > 0.0) {
                Text(
                    text = stringResource(R.string.curve_based_on, format(state.totalKm, 1)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = stringResource(R.string.curve_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.efficientSpeedKmh != null && state.efficientL100 != null) {
                Text(
                    text = stringResource(
                        R.string.curve_efficient,
                        "${Math.round(state.efficientSpeedKmh)}",
                        format(state.efficientL100, 1),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (state.idleLph != null) {
                Text(
                    text = stringResource(R.string.curve_idle, format(state.idleLph, 1)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChartCard(state: CurveUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.curve_y_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CurveChart(
                defaultPoints = state.defaultPoints,
                effectivePoints = state.effectivePoints,
                manualPoints = state.manualPoints,
                learnedPoints = state.learnedPoints,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            )

            Text(
                text = stringResource(R.string.curve_x_axis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            Legend()
        }
    }
}

@Composable
private fun Legend() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(color = DefaultColor, label = stringResource(R.string.curve_legend_default))
            LegendItem(color = ManualColor, label = stringResource(R.string.curve_legend_manual))
            LegendItem(color = EffectiveColor, label = stringResource(R.string.curve_legend_effective))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(color = LearnedColor, label = stringResource(R.string.curve_legend_learned))
            LegendItem(
                color = LearnedColor.copy(alpha = 0.3f),
                label = stringResource(R.string.curve_legend_band),
            )
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CurveChart(
    defaultPoints: List<SpeedPoint>,
    effectivePoints: List<SpeedPoint>,
    manualPoints: List<SpeedPoint>,
    learnedPoints: List<LearnedPoint>,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = modifier) {
        val xMin = 0.0
        val xMax = 140.0
        val xStep = 20.0

        // Include the learned uncertainty band so it is never clipped by the Y range.
        val bandValues = learnedPoints.flatMap { point ->
            val half = uncertainty(point.distanceKm)
            listOf(
                point.litersPer100Km + half,
                (point.litersPer100Km - half).coerceAtLeast(0.0),
            )
        }
        val allY = defaultPoints.map { it.litersPer100Km } +
            effectivePoints.map { it.litersPer100Km } +
            manualPoints.map { it.litersPer100Km } +
            learnedPoints.map { it.litersPer100Km } +
            bandValues
        val yRange = computeYRange(allY)

        val leftPad = 38.dp.toPx()
        val rightPad = 10.dp.toPx()
        val topPad = 10.dp.toPx()
        val bottomPad = 24.dp.toPx()
        val plotLeft = leftPad
        val plotTop = topPad
        val plotRight = (size.width - rightPad).coerceAtLeast(leftPad + 1f)
        val plotBottom = (size.height - bottomPad).coerceAtLeast(topPad + 1f)
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop

        fun x(speed: Double): Float =
            (plotLeft + (speed - xMin) / (xMax - xMin) * plotWidth).toFloat()

        fun y(value: Double): Float =
            (plotBottom - (value - yRange.min) / (yRange.max - yRange.min) * plotHeight).toFloat()

        val labelStyle = TextStyle(color = labelColor, fontSize = 10.sp)

        // Horizontal gridlines + Y tick labels (L/100km).
        val yTickCount = ((yRange.max - yRange.min) / yRange.step).roundToInt()
        for (i in 0..yTickCount) {
            val value = yRange.min + i * yRange.step
            val py = y(value)
            drawLine(
                color = gridColor,
                start = Offset(plotLeft, py),
                end = Offset(plotRight, py),
                strokeWidth = 1f,
            )
            val layout = textMeasurer.measure(formatTick(value, yRange.step), labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    plotLeft - 6.dp.toPx() - layout.size.width,
                    py - layout.size.height / 2f,
                ),
            )
        }

        // Vertical gridlines + X tick labels (km/h).
        var speed = xMin
        while (speed <= xMax + 0.001) {
            val px = x(speed)
            drawLine(
                color = gridColor,
                start = Offset(px, plotTop),
                end = Offset(px, plotBottom),
                strokeWidth = 1f,
            )
            val layout = textMeasurer.measure(formatTick(speed, xStep), labelStyle)
            val centered = (px - layout.size.width / 2f)
                .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(centered, plotBottom + 4.dp.toPx()),
            )
            speed += xStep
        }

        // Axis lines.
        drawLine(
            color = axisColor,
            start = Offset(plotLeft, plotTop),
            end = Offset(plotLeft, plotBottom),
            strokeWidth = 1.5.dp.toPx(),
        )
        drawLine(
            color = axisColor,
            start = Offset(plotLeft, plotBottom),
            end = Offset(plotRight, plotBottom),
            strokeWidth = 1.5.dp.toPx(),
        )

        // Learned uncertainty band (drawn under the curves).
        buildBandPath(learnedPoints, { x(it) }, { y(it) })?.let { band ->
            drawPath(band, color = LearnedColor.copy(alpha = 0.18f))
        }

        fun drawSeries(points: List<SpeedPoint>, color: Color, width: Float) {
            if (points.size < 2) return
            val sorted = points.sortedBy { it.speedKmh }
            val path = Path()
            sorted.forEachIndexed { index, point ->
                val px = x(point.speedKmh)
                val py = y(point.litersPer100Km)
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, color = color, style = Stroke(width = width))
        }

        drawSeries(defaultPoints, DefaultColor, width = 4f)
        drawSeries(manualPoints, ManualColor, width = 4f)
        drawSeries(effectivePoints, EffectiveColor, width = 6f)

        // Learned polyline connecting the measured points.
        val sortedLearned = learnedPoints.sortedBy { it.speedKmh }
        if (sortedLearned.size >= 2) {
            val learnedPath = Path()
            sortedLearned.forEachIndexed { index, point ->
                val px = x(point.speedKmh)
                val py = y(point.litersPer100Km)
                if (index == 0) learnedPath.moveTo(px, py) else learnedPath.lineTo(px, py)
            }
            drawPath(learnedPath, color = LearnedColor, style = Stroke(width = 3f))
        }

        val maxKm = learnedPoints.maxOfOrNull { it.distanceKm } ?: 0.0
        learnedPoints.forEach { point ->
            val radius = if (maxKm > 0.0) {
                4.dp.toPx() + 6.dp.toPx() * (point.distanceKm / maxKm).toFloat()
            } else {
                5.dp.toPx()
            }
            drawCircle(
                color = LearnedColor,
                radius = radius,
                center = Offset(x(point.speedKmh), y(point.litersPer100Km)),
            )
        }
    }
}

private data class YRange(val min: Double, val max: Double, val step: Double)

/**
 * Picks a rounded Y range with headroom above and below the data so the curve is not
 * squashed against the top of the plot, plus a "nice" tick step (1/2/5 x 10^n).
 */
private fun computeYRange(values: List<Double>): YRange {
    val finite = values.filter { it.isFinite() }
    val rawMin = finite.minOrNull() ?: 0.0
    val rawMax = finite.maxOrNull() ?: 10.0
    val span = (rawMax - rawMin).coerceAtLeast(0.5)
    val pad = (span * 0.15).coerceAtLeast(0.3)
    val step = niceStep((span + 2 * pad) / 5.0)
    val min = (floor((rawMin - pad) / step) * step).coerceAtLeast(0.0)
    var max = ceil((rawMax + pad) / step) * step
    if (max - min < step * 2) max = min + step * 2
    return YRange(min = min, max = max, step = step)
}

private fun niceStep(rawStep: Double): Double {
    if (rawStep <= 0.0 || !rawStep.isFinite()) return 1.0
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val normalized = rawStep / magnitude
    val nice = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * magnitude
}

/** Half-height of the learned uncertainty band, shrinking as measured distance grows. */
private fun uncertainty(distanceKm: Double): Double {
    val confidence = if (distanceKm <= 0.0) 0.0 else distanceKm / (distanceKm + 20.0)
    return 0.25 + 1.75 * (1.0 - confidence)
}

private fun buildBandPath(
    points: List<LearnedPoint>,
    x: (Double) -> Float,
    y: (Double) -> Float,
): Path? {
    if (points.size < 2) return null
    val sorted = points.sortedBy { it.speedKmh }
    val path = Path()
    sorted.forEachIndexed { index, point ->
        val half = uncertainty(point.distanceKm)
        val px = x(point.speedKmh)
        val py = y((point.litersPer100Km + half).coerceAtLeast(0.0))
        if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    for (index in sorted.indices.reversed()) {
        val point = sorted[index]
        val half = uncertainty(point.distanceKm)
        path.lineTo(x(point.speedKmh), y((point.litersPer100Km - half).coerceAtLeast(0.0)))
    }
    path.close()
    return path
}

private val DefaultColor = Color(0xFF90A4AE)
private val ManualColor = Color(0xFF3F72AF)
private val EffectiveColor = Color(0xFF1B6B4A)
private val LearnedColor = Color(0xFFF2C14E)

private fun formatTick(value: Double, step: Double): String =
    format(value, if (step < 1.0) 1 else 0)

private fun format(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)
