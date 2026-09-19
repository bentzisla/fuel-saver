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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
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
        return START_NOT_STICKY
    }

    private fun startLogging(address: String?) {
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

        startForeground(NOTIFICATION_ID, buildNotification(null))

        scope.launch {
            val vehicle = vehicleRepository.profile.first()
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
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
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
    }

    override fun onDestroy() {
        engine.stop()
        overlay.hide()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(state: LiveObdState?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
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