package com.fuelroute

import android.app.NotificationManager
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
import com.fuelroute.service.ObdProbeRunner
import com.fuelroute.service.ObdProbeScheduler
import com.fuelroute.ui.theme.FuelRouteTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val openStats = mutableStateOf(false)

    @Inject
    lateinit var obdProbeScheduler: ObdProbeScheduler

    override fun onStart() {
        super.onStart()
        // The user is around: the background probe drops its parked-car backoff.
        obdProbeScheduler.resetBackoff()
    }

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
        // A recreated activity (rotation) still carries its original intent: act on it once.
        if (savedInstanceState == null) startProbeLogging(intent)
        setContent {
            FuelRouteTheme {
                FuelRouteNavHost(openStats = openStats.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.wantsStats()) openStats.value = true
        startProbeLogging(intent)
    }

    /**
     * The tap on the "drive detected" notification: the background probe found the engine running
     * but Android did not let it start the logging service from the background. The activity is
     * in the foreground now, so start it here.
     */
    private fun startProbeLogging(intent: Intent?) {
        val address = intent?.getStringExtra(ObdProbeRunner.EXTRA_START_ADDRESS) ?: return
        intent.removeExtra(ObdProbeRunner.EXTRA_START_ADDRESS)
        getSystemService(NotificationManager::class.java)?.cancel(ObdProbeRunner.NOTIFICATION_ID)
        runCatching { ObdLoggingService.start(this, address, auto = true) }
    }

    private fun Intent?.wantsStats(): Boolean =
        this?.getBooleanExtra(ObdLoggingService.EXTRA_OPEN_STATS, false) == true
}