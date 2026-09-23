package com.fuelroute.ui.components

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Shared number/date formatting for the UI. Numbers always use Locale.US digits (the app shows
 * Western digits inside Hebrew text); dates follow the device locale.
 */

/** Fixed-decimals number, e.g. `fmt(6.456, 1)` = "6.5". */
fun fmt(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

/** Money in shekels, e.g. `money(12.3)` = "₪12.30". */
fun money(value: Double, decimals: Int = 2): String = "₪" + fmt(value, decimals)

/** Signed number with one decimal, e.g. "+1.2" / "-0.4". */
fun signed(value: Double, decimals: Int = 1): String =
    String.format(Locale.US, "%+.${decimals}f", value)

fun formatDateTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))

fun formatDate(epochMs: Long): String =
    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochMs))

fun formatTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

/** Placeholder for a value that is not available yet. */
const val DASH = "—"
