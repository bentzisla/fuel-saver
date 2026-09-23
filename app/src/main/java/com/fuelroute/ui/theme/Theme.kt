package com.fuelroute.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val LocalFuelColors = staticCompositionLocalOf { LightFuelColors }

/** Accessor for the app's semantic colours: `FuelTheme.colors.positive`. */
object FuelTheme {
    val colors: FuelColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFuelColors.current
}

private val FuelShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Default M3 type scale with slightly heavier titles so hierarchy reads at a glance. */
private val FuelTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.SemiBold),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold),
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/**
 * App theme. Dynamic (wallpaper) colour is opt-in: the brand green carries meaning ("cheapest",
 * "saving"), and a wallpaper-derived primary would make the recommended route look arbitrary.
 */
@Composable
fun FuelRouteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }
    val fuelColors = if (darkTheme) DarkFuelColors else LightFuelColors

    CompositionLocalProvider(LocalFuelColors provides fuelColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FuelTypography,
            shapes = FuelShapes,
        ) {
            // Paint the themed background across the whole window, including behind the system
            // bars and the IME. Without this, the area reserved by imePadding() can be left
            // unpainted (a black block above the keyboard).
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colorScheme.background,
                content = content,
            )
        }
    }
}
