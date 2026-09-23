package com.fuelroute.ui.components

import androidx.compose.ui.unit.dp

/**
 * The one spacing scale used by every screen (4-point grid). Screens should not invent their own
 * paddings; picking from here is what keeps the app calm and consistent.
 */
object Dimens {
    /** Between a label and its value, icon and text. */
    val xs = 4.dp

    /** Between related lines inside a card. */
    val s = 8.dp

    /** Between rows / controls inside a card. */
    val m = 12.dp

    /** Card inner padding, screen side padding, gap between cards. */
    val l = 16.dp

    /** Gap between screen sections. */
    val xl = 24.dp

    /** Minimum touch target (Material / WCAG). */
    val touchTarget = 48.dp

    /** Height of a list row with a subtitle. */
    val rowHeight = 56.dp

    /** Big, glove-friendly primary buttons. */
    val primaryButtonHeight = 56.dp
}
