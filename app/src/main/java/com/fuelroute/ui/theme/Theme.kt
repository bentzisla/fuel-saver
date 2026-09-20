package com.fuelroute.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    secondary = GreenSecondary,
    tertiary = AmberTertiary,
)

private val DarkColors = darkColorScheme(
    primary = GreenSecondary,
    secondary = GreenPrimary,
    tertiary = AmberTertiary,
)

@Composable
fun FuelRouteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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

    MaterialTheme(
        colorScheme = colorScheme,
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