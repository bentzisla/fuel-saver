package com.fuelroute.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.fuelroute.MainActivity
import com.fuelroute.R
import com.fuelroute.data.obd.BluetoothClassicTransport
import com.fuelroute.data.obd.LiveObdState
import com.fuelroute.data.obd.ObdEngine
import com.fuelroute.data.obd.ObdTransport
import com.fuelroute.data.obd.SimulatedObdTransport
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ObdLoggingService : Service() {

    @Inject
    lateinit var engine: ObdEngine

    @Inject
    lateinit var vehicleRepository: VehicleRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Deferred until first use: the Service constructor runs before Android attaches the
    // base Context, so resolving WindowManager (or any system service) here would NPE.
    private val overlay: ObdOverlayController by lazy { ObdOverlayController(this) }
    private var overlayEnabled = false

    private var logging = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastLiveAtMs = 0L
    private var lastStale: Boolean? = null
    private var latestState: LiveObdState? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startLogging(intent?.getStringExtra(EXTRA_ADDRESS))
        return START_REDELIVER_INTENT
    }

    private fun startLogging(address: String?) {
        if (logging) return
        val transport: ObdTransport? = if (address == null) {
            SimulatedObdTransport()
        } else {
            BluetoothAdapter.getDefaultAdapter()
                ?.getRemoteDevice(address)
                ?.let { BluetoothClassicTransport(it) }
        }
        if (transport == null) {
            stopSelf()
            return
        }

        logging = true
        latestState = null
        lastStale = null
        lastLiveAtMs = System.currentTimeMillis()
        acquireWakeLock()

        startForeground(NOTIFICATION_ID, buildNotification(null, stale = false))

        scope.launch {
            val vehicle = vehicleRepository.active()
            engine.start(transport, vehicle)
        }

        scope.launch {
            settingsRepository.settings.collect { settings ->
                overlayEnabled = settings.showOverlay && Settings.canDrawOverlays(this@ObdLoggingService)
                if (!overlayEnabled) overlay.hide()
            }
        }

        scope.launch {
            engine.live.collect { state ->
                latestState = state
                lastLiveAtMs = System.currentTimeMillis()
                lastStale = false
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(state, stale = false))
                if (overlayEnabled) {
                    if (!overlay.isShowing) {
                        overlay.show(onTap = {
                            Intent(this@ObdLoggingService, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .let { startActivity(it) }
                        })
                    }
                    overlay.updateConsumption(state.instantL100)
                    overlay.updateSpeed(state.speedKmh)
                }
            }
        }

        // Heartbeat: renews the wake lock before its 4 h timeout and surfaces a
        // "stale" notification when samples stop arriving (e.g. adapter stalled).
        scope.launch {
            while (isActive) {
                delay(SERVICE_TICK_MS)
                acquireWakeLock()
                if (!engine.isRunning) continue
                val stale = System.currentTimeMillis() - lastLiveAtMs > STALE_AFTER_MS
                if (stale != lastStale) {
                    lastStale = stale
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(latestState, stale))
                }
            }
        }
    }

    override fun onDestroy() {
        logging = false
        engine.stop()
        overlay.hide()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    /** Acquires the partial wake lock, or renews its timeout if already held. */
    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun buildNotification(state: LiveObdState?, stale: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_STATS, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ObdLoggingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (state?.speedKmh != null) {
            val l100 = state.instantL100?.let { String.format(Locale.US, "%.1f", it) } ?: "-"
            getString(R.string.notification_live, Math.round(state.speedKmh), l100)
        } else {
            getString(R.string.notification_text)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSubText(if (stale) getString(R.string.notification_stale) else null)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "obd_logging"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.fuelroute.action.STOP"
        const val EXTRA_ADDRESS = "device_address"
        const val EXTRA_OPEN_STATS = "open_stats"

        private const val WAKE_LOCK_TAG = "FuelRoute:obd"
        private const val WAKE_LOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000
        private const val SERVICE_TICK_MS = 5_000L
        private const val STALE_AFTER_MS = 10_000L

        fun start(context: Context, address: String?) {
            context.startForegroundService(
                Intent(context, ObdLoggingService::class.java)
                    .putExtra(EXTRA_ADDRESS, address),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ObdLoggingService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}