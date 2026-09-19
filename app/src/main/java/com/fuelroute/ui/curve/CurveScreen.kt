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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.R
import com.fuelroute.domain.model.SpeedPoint
import java.util.Locale

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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.hasLearnedData) {
            Button(onClick = viewModel::adoptLearned, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.curve_adopt))
            }
        }
        if (state.hasLearnedData) {
            OutlinedButton(onClick = viewModel::resetLearning, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.curve_reset_learning))
            }
        }
        if (state.hasManualCurve) {
            OutlinedButton(onClick = viewModel::clearManual, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.curve_clear_manual))
            }
        }
    }
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
                    .height(240.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.curve_x_axis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Legend()
        }
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendItem(color = DefaultColor, label = stringResource(R.string.curve_legend_default))
        LegendItem(color = ManualColor, label = stringResource(R.string.curve_legend_manual))
        LegendItem(color = EffectiveColor, label = stringResource(R.string.curve_legend_effective))
        LegendItem(color = LearnedColor, label = stringResource(R.string.curve_legend_learned))
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
    Canvas(modifier = modifier) {
        val xMin = 0.0
        val xMax = 140.0
        val allY = (defaultPoints.map { it.litersPer100Km } +
            effectivePoints.map { it.litersPer100Km } +
            manualPoints.map { it.litersPer100Km } +
            learnedPoints.map { it.litersPer100Km })
        val yMinRaw = allY.minOrNull() ?: 0.0
        val yMaxRaw = allY.maxOrNull() ?: 10.0
        val pad = (yMaxRaw - yMinRaw).coerceAtLeast(1.0) * 0.15
        val yMin = (yMinRaw - pad).coerceAtLeast(0.0)
        val yMax = yMaxRaw + pad

        fun x(speed: Double): Float = ((speed - xMin) / (xMax - xMin) * size.width).toFloat()
        fun y(value: Double): Float = (size.height - (value - yMin) / (yMax - yMin) * size.height).toFloat()

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

        val maxKm = learnedPoints.maxOfOrNull { it.distanceKm } ?: 0.0
        learnedPoints.forEach { point ->
            val radius = if (maxKm > 0.0) {
                4.dp.toPx() + 8.dp.toPx() * (point.distanceKm / maxKm).toFloat()
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

private val DefaultColor = Color(0xFF90A4AE)
private val ManualColor = Color(0xFF3F72AF)
private val EffectiveColor = Color(0xFF1B6B4A)
private val LearnedColor = Color(0xFFF2C14E)

private fun format(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)