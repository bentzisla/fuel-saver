package com.fuelroute.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The hero number of a screen: one big value with its unit and a quiet label. Read as a single
 * phrase by TalkBack ("label value unit").
 */
@Composable
fun HeroValue(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    label: String? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
    valueStyle: TextStyle = MaterialTheme.typography.displayMedium,
) {
    val spoken = listOfNotNull(label, value, unit).joinToString(" ")
    Column(modifier = modifier.clearAndSetSemantics { contentDescription = spoken }) {
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Text(text = value, style = valueStyle, color = color, maxLines = 1)
            unit?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Dimens.s),
                )
            }
        }
    }
}

/**
 * A compact metric tile ("מהירות 82 קמ״ש"). [large] makes the number glanceable for the live
 * dashboard; otherwise it is a secondary stat.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    large: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
) {
    val spoken = listOfNotNull(label, value, unit).joinToString(" ")
    Card(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.m),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
                color = valueColor,
                maxLines = 1,
            )
            unit?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}


/** Colour intent of a [StatusPill]. */
enum class PillTone { Positive, Neutral, Caution, Negative, Accent }

/** Small rounded label: "הזול ביותר", "הדגמה", "מקושר", "+4%". */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.Neutral,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        PillTone.Positive -> scheme.primaryContainer to scheme.onPrimaryContainer
        PillTone.Neutral -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        PillTone.Caution -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        PillTone.Negative -> scheme.errorContainer to scheme.onErrorContainer
        PillTone.Accent -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Dimens.s, vertical = 2.dp),
        )
    }
}
