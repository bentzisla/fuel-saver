package com.fuelroute.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Brand palette: a single green seed (#1B6B4A) expanded into full Material 3 tonal schemes, so
 * no role falls back to the baseline purple. Green deliberately means "cheap / saving" across
 * the app, which is why dynamic (wallpaper) colour is off by default in [FuelRouteTheme].
 */

internal val LightColors = lightColorScheme(
    primary = Color(0xFF1B6B4A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA6F2C8),
    onPrimaryContainer = Color(0xFF002113),
    inversePrimary = Color(0xFF8BD6AD),
    secondary = Color(0xFF4D6356),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE9D8),
    onSecondaryContainer = Color(0xFF0A1F15),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA0),
    onTertiaryContainer = Color(0xFF261900),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FBF6),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF6FBF6),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DD),
    onSurfaceVariant = Color(0xFF404943),
    surfaceTint = Color(0xFF1B6B4A),
    inverseSurface = Color(0xFF2C322E),
    inverseOnSurface = Color(0xFFEDF2ED),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFBFC9C1),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF6FBF6),
    surfaceDim = Color(0xFFD6DBD6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F0),
    surfaceContainer = Color(0xFFEAEFEA),
    surfaceContainerHigh = Color(0xFFE4EAE5),
    surfaceContainerHighest = Color(0xFFDFE4DF),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD6AD),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005236),
    onPrimaryContainer = Color(0xFFA6F2C8),
    inversePrimary = Color(0xFF1B6B4A),
    secondary = Color(0xFFB4CCBD),
    onSecondary = Color(0xFF1F352A),
    secondaryContainer = Color(0xFF354B3F),
    onSecondaryContainer = Color(0xFFCFE9D8),
    tertiary = Color(0xFFF0C048),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFDEA0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFDFE4DF),
    surface = Color(0xFF0F1512),
    onSurface = Color(0xFFDFE4DF),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFBFC9C1),
    surfaceTint = Color(0xFF8BD6AD),
    inverseSurface = Color(0xFFDFE4DF),
    inverseOnSurface = Color(0xFF2C322E),
    outline = Color(0xFF8A938C),
    outlineVariant = Color(0xFF404943),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353B37),
    surfaceDim = Color(0xFF0F1512),
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainer = Color(0xFF1B211E),
    surfaceContainerHigh = Color(0xFF262B28),
    surfaceContainerHighest = Color(0xFF303633),
)

/**
 * Semantic colours that Material 3 has no role for (traffic levels, chart series, "good/ok/bad"
 * quality). Resolved once per theme so screens never hardcode hex values or branch on
 * `isSystemInDarkTheme()` themselves.
 */
data class FuelColors(
    /** Good / saving / high confidence. */
    val positive: Color,
    /** Medium / caution. */
    val caution: Color,
    /** Bad / low confidence (softer than `error`, which is reserved for failures). */
    val negative: Color,
    /** Not enough data. */
    val neutral: Color,
    val trafficNormal: Color,
    val trafficSlow: Color,
    val trafficJam: Color,
    val chartDefault: Color,
    val chartManual: Color,
    val chartEffective: Color,
    val chartLearned: Color,
)

internal val LightFuelColors = FuelColors(
    positive = Color(0xFF1B6B4A),
    caution = Color(0xFF8A6100),
    negative = Color(0xFFB3261E),
    neutral = Color(0xFF6F7973),
    trafficNormal = Color(0xFF2E7D32),
    trafficSlow = Color(0xFFE09B00),
    trafficJam = Color(0xFFC62828),
    chartDefault = Color(0xFF8A9A92),
    chartManual = Color(0xFF3F72AF),
    chartEffective = Color(0xFF1B6B4A),
    chartLearned = Color(0xFFD99A00),
)

internal val DarkFuelColors = FuelColors(
    positive = Color(0xFF8BD6AD),
    caution = Color(0xFFFFCA28),
    negative = Color(0xFFFF8A80),
    neutral = Color(0xFF9EA8A2),
    trafficNormal = Color(0xFF66BB6A),
    trafficSlow = Color(0xFFFFCA28),
    trafficJam = Color(0xFFEF5350),
    chartDefault = Color(0xFF8E9A94),
    chartManual = Color(0xFF8AB4F8),
    chartEffective = Color(0xFF8BD6AD),
    chartLearned = Color(0xFFF2C14E),
)
