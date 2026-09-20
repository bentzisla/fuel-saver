package com.fuelroute

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import com.fuelroute.nav.FuelRouteNavHost
import com.fuelroute.service.ObdLoggingService
import com.fuelroute.ui.theme.FuelRouteTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val openStats = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge before super.onCreate so the window is laid out behind the system bars
        // and the IME; Compose consumes the resulting insets (Scaffold + imePadding).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Compose draws behind the keyboard, so the window must resize (not pan) when the IME
        // appears; RouteScreen then lifts its inputs with imePadding().
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        openStats.value = intent.wantsStats()
        setContent {
            FuelRouteTheme {
                FuelRouteNavHost(openStats = openStats.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.wantsStats()) openStats.value = true
    }

    private fun Intent?.wantsStats(): Boolean =
        this?.getBooleanExtra(ObdLoggingService.EXTRA_OPEN_STATS, false) == true
}