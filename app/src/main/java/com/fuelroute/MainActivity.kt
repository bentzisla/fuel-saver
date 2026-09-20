package com.fuelroute

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.fuelroute.nav.FuelRouteNavHost
import com.fuelroute.service.ObdLoggingService
import com.fuelroute.ui.theme.FuelRouteTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val openStats = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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