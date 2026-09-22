package com.fuelroute.ui.curve

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.R
import com.fuelroute.domain.fuel.CurveDataQuality
import com.fuelroute.domain.fuel.ManualCurveResult
import com.fuelroute.domain.fuel.ManualCurveValidator
import com.fuelroute.domain.model.SpeedPoint
import java.util.Locale
import kotlin.math.abs
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
    var editingManual by remember { mutableStateOf(false) }

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
        Button(
            onClick = { editingManual = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.curve_edit_manual))
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

    if (editingManual) {
        ManualCurveEditorDialog(
            defaultPoints = state.defaultPoints,
            manualPoints = state.manualPoints,
            onSave = { points ->
                viewModel.saveManualCurve(points)
                editingManual = false
            },
            onDismiss = { editingManual = false },
        )
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

/** One editable speed/consumption row in the manual-curve editor. */
private data class CurveEditRow(
    val id: Long,
    val speed: String,
    val consumption: String,
)

/** Per-row validation state used to highlight bad rows. */
private data class CurveRowState(val blank: Boolean, val valid: Boolean)

/**
 * Dialog for entering a manual consumption curve. Rows can be added/removed and seeded from
 * the current default curve, the current manual curve, or an empty list. Empty save clears
 * the manual curve; otherwise at least two valid points are required.
 */
@Composable
private fun ManualCurveEditorDialog(
    defaultPoints: List<SpeedPoint>,
    manualPoints: List<SpeedPoint>,
    onSave: (List<SpeedPoint>) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialRows = remember(manualPoints, defaultPoints) {
        val source = manualPoints.ifEmpty { defaultPoints }
        val mapped = source.mapIndexed { index, point ->
            CurveEditRow(
                id = index.toLong(),
                speed = format(point.speedKmh, 0),
                consumption = format(point.litersPer100Km, 1),
            )
        }
        if (mapped.isEmpty()) listOf(CurveEditRow(0L, "", "")) else mapped
    }
    var rows by remember(initialRows) { mutableStateOf(initialRows) }

    val nextId = (rows.maxOfOrNull { it.id } ?: -1L) + 1L

    fun seedFrom(source: List<SpeedPoint>) {
        rows = if (source.isEmpty()) {
            listOf(CurveEditRow(nextId, "", ""))
        } else {
            source.mapIndexed { index, point ->
                CurveEditRow(
                    id = nextId + index,
                    speed = format(point.speedKmh, 0),
                    consumption = format(point.litersPer100Km, 1),
                )
            }
        }
    }

    val rowStates = rows.map { row ->
        val blank = row.speed.isBlank() && row.consumption.isBlank()
        val speed = row.speed.trim().toDoubleOrNull()
        val consumption = row.consumption.trim().toDoubleOrNull()
        val valid = !blank &&
            speed != null && speed.isFinite() &&
            speed > ManualCurveValidator.MIN_SPEED_KMH &&
            speed <= ManualCurveValidator.MAX_SPEED_KMH &&
            consumption != null && consumption.isFinite() && consumption > 0.0
        CurveRowState(blank = blank, valid = valid)
    }
    val invalidCount = rowStates.count { !it.blank && !it.valid }

    val candidatePoints = rows.mapNotNull { row ->
        val speed = row.speed.trim().toDoubleOrNull() ?: return@mapNotNull null
        val consumption = row.consumption.trim().toDoubleOrNull() ?: return@mapNotNull null
        SpeedPoint(speed, consumption)
    }
    val validated = if (invalidCount == 0) {
        ManualCurveValidator.validate(candidatePoints)
    } else {
        null
    }
    val canSave = invalidCount == 0 &&
        (validated is ManualCurveResult.Valid || validated is ManualCurveResult.Cleared)

    val errorText = when {
        invalidCount > 0 -> stringResource(R.string.curve_editor_error_row)
        validated is ManualCurveResult.Invalid -> stringResource(R.string.curve_editor_error_too_few)
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.curve_editor_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.curve_editor_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.curve_editor_units_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = { seedFrom(emptyList()) }) {
                        Text(
                            text = stringResource(R.string.curve_editor_preset_empty),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = { seedFrom(defaultPoints) }) {
                        Text(
                            text = stringResource(R.string.curve_editor_preset_default),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = { seedFrom(manualPoints) }) {
                        Text(
                            text = stringResource(R.string.curve_editor_preset_manual),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                rows.forEachIndexed { index, row ->
                    CurveEditRowItem(
                        row = row,
                        isError = !rowStates[index].blank && !rowStates[index].valid,
                        onSpeedChange = { value ->
                            rows = rows.toMutableList().also { it[index] = row.copy(speed = value) }
                        },
                        onConsumptionChange = { value ->
                            rows = rows.toMutableList().also { it[index] = row.copy(consumption = value) }
                        },
                        onRemove = { rows = rows.filterIndexed { i, _ -> i != index } },
                    )
                }

                TextButton(onClick = { rows = rows + CurveEditRow(nextId, "", "") }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(text = stringResource(R.string.curve_editor_add))
                }

                errorText?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (validated is ManualCurveResult.Cleared) {
                    Text(
                        text = stringResource(R.string.curve_editor_clear_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    when (validated) {
                        is ManualCurveResult.Valid -> onSave(validated.points)
                        ManualCurveResult.Cleared -> onSave(emptyList())
                        else -> Unit
                    }
                },
            ) {
                Text(stringResource(R.string.curve_editor_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.curve_editor_cancel))
            }
        },
    )
}

@Composable
private fun CurveEditRowItem(
    row: CurveEditRow,
    isError: Boolean,
    onSpeedChange: (String) -> Unit,
    onConsumptionChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = row.speed,
            onValueChange = onSpeedChange,
            label = { Text(stringResource(R.string.curve_editor_speed_label)) },
            isError = isError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = row.consumption,
            onValueChange = onConsumptionChange,
            label = { Text(stringResource(R.string.curve_editor_consumption_label)) },
            isError = isError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.curve_editor_remove),
            )
        }
    }
}

@Composable
private fun SummaryCard(state: CurveUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.curve_quality_label, qualityLabel(state.quality)),
                style = MaterialTheme.typography.titleSmall,
                color = qualityColor(state.quality),
            )
            Text(
                text = stringResource(R.string.curve_quality_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.totalKm > 0.0) {
                Text(
                    text = stringResource(R.string.curve_based_on, format(state.totalKm, 1)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.curve_blend_split,
                        (state.learnedShare * 100.0).roundToInt().toString(),
                        stringResource(
                            if (state.hasManualCurve) {
                                R.string.curve_legend_manual
                            } else {
                                R.string.curve_legend_default
                            },
                        ),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.curve_samples, state.totalSamples.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            state.idleLph?.let { idle ->
                Text(
                    text = stringResource(R.string.curve_idle, format(idle, 1)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.curve_calibration, format(state.calibrationFactor, 2)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun qualityLabel(quality: CurveDataQuality): String = stringResource(
    when (quality) {
        CurveDataQuality.NONE -> R.string.curve_quality_none
        CurveDataQuality.LOW -> R.string.curve_quality_low
        CurveDataQuality.MEDIUM -> R.string.curve_quality_medium
        CurveDataQuality.HIGH -> R.string.curve_quality_high
    },
)

private fun qualityColor(quality: CurveDataQuality): Color = when (quality) {
    CurveDataQuality.NONE -> Color(0xFF9E9E9E)
    CurveDataQuality.LOW -> Color(0xFFD9534F)
    CurveDataQuality.MEDIUM -> Color(0xFFE0A800)
    CurveDataQuality.HIGH -> Color(0xFF1B6B4A)
}

@Composable
private fun ChartCard(state: CurveUiState) {
    val kmSuffix = stringResource(R.string.curve_km_suffix)
    var selectedBin by remember(state.learnedPoints) { mutableStateOf<LearnedPoint?>(null) }

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
                selectedPoint = selectedBin,
                onSelect = { selectedBin = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            )

            selectedBin?.let { point ->
                CurveBinChip(point = point, kmSuffix = kmSuffix)
            }

            Text(
                text = stringResource(R.string.curve_x_axis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            Legend()

            Text(
                text = stringResource(R.string.curve_confidence_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.curve_basis_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.curve_basis_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    selectedPoint: LearnedPoint?,
    onSelect: (LearnedPoint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier = modifier
            .pointerInput(learnedPoints) {
                detectTapGestures { offset ->
                    onSelect(
                        nearestLearnedPoint(
                            points = learnedPoints,
                            xPx = offset.x,
                            widthPx = size.width.toFloat(),
                            leftPadPx = PlotLeftPad.toPx(),
                            rightPadPx = PlotRightPad.toPx(),
                        ),
                    )
                }
            }
            .pointerInput(learnedPoints) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onSelect(
                            nearestLearnedPoint(
                                points = learnedPoints,
                                xPx = offset.x,
                                widthPx = size.width.toFloat(),
                                leftPadPx = PlotLeftPad.toPx(),
                                rightPadPx = PlotRightPad.toPx(),
                            ),
                        )
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        onSelect(
                            nearestLearnedPoint(
                                points = learnedPoints,
                                xPx = change.position.x,
                                widthPx = size.width.toFloat(),
                                leftPadPx = PlotLeftPad.toPx(),
                                rightPadPx = PlotRightPad.toPx(),
                            ),
                        )
                    },
                )
            },
    ) {
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

        val leftPad = PlotLeftPad.toPx()
        val rightPad = PlotRightPad.toPx()
        val topPad = 10.dp.toPx()
        val bottomPad = 24.dp.toPx()
        val plotLeft = leftPad
        val plotTop = topPad
        val plotRight = (size.width - rightPad).coerceAtLeast(leftPad + 1f)
        val plotBottom = (size.height - bottomPad).coerceAtLeast(topPad + 1f)
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop

        fun x(speed: Double): Float =
            (plotLeft + (speed - X_MIN) / (X_MAX - X_MIN) * plotWidth).toFloat()

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
        var speed = X_MIN
        while (speed <= X_MAX + 0.001) {
            val px = x(speed)
            drawLine(
                color = gridColor,
                start = Offset(px, plotTop),
                end = Offset(px, plotBottom),
                strokeWidth = 1f,
            )
            val layout = textMeasurer.measure(formatTick(speed, X_STEP), labelStyle)
            val centered = (px - layout.size.width / 2f)
                .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(centered, plotBottom + 4.dp.toPx()),
            )
            speed += X_STEP
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

        // Crosshair for the bin selected by tap/drag, drawn above the points.
        selectedPoint?.let { point ->
            val px = x(point.speedKmh)
            val py = y(point.litersPer100Km)
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            drawLine(
                color = axisColor.copy(alpha = 0.8f),
                start = Offset(px, plotTop),
                end = Offset(px, plotBottom),
                strokeWidth = 1.5f,
                pathEffect = dash,
            )
            drawLine(
                color = axisColor.copy(alpha = 0.8f),
                start = Offset(plotLeft, py),
                end = Offset(plotRight, py),
                strokeWidth = 1.5f,
                pathEffect = dash,
            )
            drawCircle(
                color = LearnedColor,
                radius = 8.dp.toPx(),
                center = Offset(px, py),
                style = Stroke(width = 2.5f),
            )
        }
    }
}

/**
 * Small info chip shown for the bin nearest to the user's tap/drag on the curve:
 * speed, consumption, measured km and the confidence (learning weight) for that bin.
 */
@Composable
private fun CurveBinChip(
    point: LearnedPoint,
    kmSuffix: String,
    modifier: Modifier = Modifier,
) {
    val container = MaterialTheme.colorScheme.surfaceVariant
    val content = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .background(container, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.curve_bin_speed, format(point.speedKmh, 0)),
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
        Text(
            text = stringResource(R.string.curve_bin_consumption, format(point.litersPer100Km, 1)),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
        Text(
            text = stringResource(R.string.curve_bin_km, format(point.distanceKm, 1), kmSuffix),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
        Text(
            text = stringResource(
                R.string.curve_bin_confidence,
                (binConfidence(point.distanceKm) * 100.0).roundToInt().toString(),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

/**
 * Maps a horizontal touch position (px, Canvas-local) to the learned bin whose plotted
 * X coordinate is nearest, using the same padding/domain as the Canvas.
 */
private fun nearestLearnedPoint(
    points: List<LearnedPoint>,
    xPx: Float,
    widthPx: Float,
    leftPadPx: Float,
    rightPadPx: Float,
): LearnedPoint? {
    if (points.isEmpty()) return null
    val plotLeft = leftPadPx
    val plotRight = (widthPx - rightPadPx).coerceAtLeast(plotLeft + 1f)
    val plotWidth = plotRight - plotLeft
    return points.minByOrNull { point ->
        val px = plotLeft + ((point.speedKmh - X_MIN) / (X_MAX - X_MIN) * plotWidth).toFloat()
        abs(px - xPx)
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

/** Learning weight for a bin: w = km / (km + 20), the same confidence used by the blender. */
private fun binConfidence(distanceKm: Double): Double =
    if (distanceKm <= 0.0) 0.0 else distanceKm / (distanceKm + 20.0)

/** Half-height of the learned uncertainty band, shrinking as measured distance grows. */
private fun uncertainty(distanceKm: Double): Double {
    val confidence = binConfidence(distanceKm)
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

/** Plot padding, shared by the Canvas mapping and the touch hit-testing. */
private val PlotLeftPad = 38.dp
private val PlotRightPad = 10.dp

/** X (speed) axis domain, in km/h. */
private const val X_MIN = 0.0
private const val X_MAX = 140.0
private const val X_STEP = 20.0

private fun formatTick(value: Double, step: Double): String =
    format(value, if (step < 1.0) 1 else 0)

private fun format(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)
