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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fuelroute.MainActivity
import com.fuelroute.R
import com.fuelroute.data.obd.BluetoothClassicTransport
import com.fuelroute.data.obd.LiveObdState
import com.fuelroute.data.obd.ObdConnectStage
import com.fuelroute.data.obd.ObdEngine
import com.fuelroute.data.obd.ObdStatus
import com.fuelroute.data.obd.ObdTransport
import com.fuelroute.data.obd.SimulatedObdTransport
import com.fuelroute.data.settings.AppSettings
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.model.VehicleProfile
import com.fuelroute.domain.obd.ConsumptionReadout
import com.fuelroute.domain.obd.ObdConnectionPolicy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    @Inject
    lateinit var retentionPruner: RetentionPruner

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Deferred until first use: the Service constructor runs before Android attaches the
    // base Context, so resolving WindowManager (or any system service) here would NPE.
    private val overlay: ObdOverlayController by lazy { ObdOverlayController(this) }
    private var overlayEnabled = false

    private var logging = false
    // Set by an explicit ACTION_STOP. A subsequently (re)delivered start intent is ignored
    // until a fresh user-initiated start() re-arms logging (card 34).
    private var stopped = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastLiveAtMs = 0L
    private var lastStale: Boolean? = null
    // Notification throttle: the live collector fires ~4 Hz, but the notification text only
    // changes rarely, so skip redundant notify() calls.
    private var lastNotifiedKey: String? = null
    private var lastNotifiedAtMs = 0L
    private var latestState: LiveObdState? = null
    // Time the current logging run started. Combined with a short startup grace this keeps
    // the engine's initial Disconnected value from stopping the service before it has had a
    // chance to attempt the connect.
    private var startedAtMs = 0L
    // Set once the engine reaches Connecting/Connected. A terminal Error/Disconnected after
    // that is a real stop even inside the grace window.
    private var sawData = false
    private var lastPersistedError: String? = null
    // Latest settings snapshot, used to gate mid-session auto-reconnect without suspending
    // inside the engine state collector.
    private var latestSettings: AppSettings? = null
    // Kept so a terminal drop can re-arm the engine without rebuilding the transport.
    private var activeTransport: ObdTransport? = null
    private var activeVehicle: VehicleProfile? = null
    // Consecutive automatic re-arms in this logging session (0-based) and the pending backoff.
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Explicit user stop: latch so a re-delivered start cannot re-arm logging, and
            // consume this start so the system does not redeliver it.
            stopped = true
            logging = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val isFreshStart = freshStart
        freshStart = false
        if (stopped && !isFreshStart) {
            // A start intent delivered after an explicit stop (e.g. START_REDELIVER_INTENT)
            // must not re-arm logging. A fresh user connect calls start(), which sets
            // [freshStart] and lets this through.
            Log.i(TAG, "ignoring start intent after explicit stop")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startLogging(
            address = intent?.getStringExtra(EXTRA_ADDRESS),
            auto = intent?.getBooleanExtra(EXTRA_AUTO, false) == true,
        )
        // Never redeliver the start intent: a manual disconnect must stay disconnected.
        return START_NOT_STICKY
    }

    private fun startLogging(address: String?, auto: Boolean) {
        if (logging) return
        val transport: ObdTransport? = if (address == null) {
            SimulatedObdTransport()
        } else {
            try {
                BluetoothAdapter.getDefaultAdapter()
                    ?.getRemoteDevice(address)
                    ?.let { BluetoothClassicTransport(it) }
            } catch (t: Throwable) {
                // A malformed address (or Bluetooth turning off between resolving the device and
                // starting the service) must not crash the foreground service.
                Log.w(TAG, "invalid Bluetooth address \"$address\" — stopping logging service", t)
                null
            }
        }
        if (transport == null) {
            Log.w(TAG, "no usable OBD transport — stopping logging service")
            stopSelf()
            return
        }

        logging = true
        stopped = false
        latestState = null
        lastStale = null
        lastNotifiedKey = null
        lastNotifiedAtMs = 0L
        lastLiveAtMs = System.currentTimeMillis()
        startedAtMs = System.currentTimeMillis()
        sawData = false
        lastPersistedError = null
        // A fresh user-initiated start begins a new reconnect budget and supersedes any
        // pending re-arm from a previous session.
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        activeTransport = transport
        activeVehicle = null
        acquireWakeLock()

        startForeground(NOTIFICATION_ID, buildNotification(null, stale = false))

        if (auto) {
            scope.launch { settingsRepository.saveLastAutoStart(System.currentTimeMillis()) }
        }

        scope.launch {
            val vehicle = vehicleRepository.active()
            activeVehicle = vehicle
            engine.start(transport, vehicle)
        }

        scope.launch {
            settingsRepository.settings.collect { settings ->
                latestSettings = settings
                overlayEnabled = settings.showOverlay && Settings.canDrawOverlays(this@ObdLoggingService)
                if (!overlayEnabled) overlay.hide()
            }
        }

        scope.launch {
            engine.live.collect { state ->
                latestState = state
                lastLiveAtMs = System.currentTimeMillis()
                lastStale = false

                // Surface the last failure without writing to DataStore on every sample.
                state.lastError?.takeIf { it != lastPersistedError }?.let { error ->
                    lastPersistedError = error
                    settingsRepository.saveLastObdError(error)
                }

                if (state.status == ObdStatus.Connecting || state.status == ObdStatus.Connected) {
                    sawData = true
                }
                if (state.status == ObdStatus.Connected) {
                    // A successful connect clears the auto-reconnect budget so one long-running
                    // session can recover from many separate drops.
                    reconnectAttempt = 0
                }
                if (ObdConnectionPolicy.shouldAutoStop(
                        status = state.status.name,
                        elapsedMs = System.currentTimeMillis() - startedAtMs,
                        sawData = sawData,
                    )
                ) {
                    // Ignition off (run loop returned), a protocol-level drop, or a
                    // failed/timed-out connect. When auto-connect is on and the user did not
                    // explicitly disconnect, re-arm instead of lingering on the terminal
                    // status (or, once the budget is exhausted, stopping).
                    if (state.status == ObdStatus.Error) {
                        settingsRepository.saveLastObdError(state.lastError ?: "ERROR")
                    }
                    if (canAutoReconnect()) {
                        scheduleReconnect()
                        return@collect
                    }
                    Log.i(TAG, "engine finished (${state.status}) — stopping logging service")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }

                getSystemService(NotificationManager::class.java)
                    .let { manager ->
                        val key = notificationKey(state, stale = false)
                        val nowMs = System.currentTimeMillis()
                        if (key != lastNotifiedKey || nowMs - lastNotifiedAtMs >= NOTIFY_MIN_INTERVAL_MS) {
                            manager.notify(NOTIFICATION_ID, buildNotification(state, stale = false))
                            lastNotifiedKey = key
                            lastNotifiedAtMs = nowMs
                        }
                    }
                if (overlayEnabled) {
                    if (!overlay.isShowing) {
                        overlay.show(onTap = {
                            Intent(this@ObdLoggingService, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .let { startActivity(it) }
                        })
                    }
                    overlay.updateConsumption(ConsumptionReadout.of(state.instantL100, state.fuelRateLph))
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

    /**
     * True when the terminal engine state should be followed by an automatic re-arm rather
     * than stopping the service. The dongle must still be reachable: a real Bluetooth
     * transport whose ACL link is up and a remembered last device address. The simulated
     * demo transport is never auto-reconnected.
     */
    private fun canAutoReconnect(): Boolean {
        val settings = latestSettings ?: return false
        val transport = activeTransport as? BluetoothClassicTransport ?: return false
        val deviceConnected = settings.lastDeviceAddress != null && transport.isDeviceAclConnected
        return ObdConnectionPolicy.shouldAutoReconnect(
            attempt = reconnectAttempt,
            autoConnect = settings.autoConnect,
            manualDisconnect = settings.manualDisconnect,
            deviceConnected = deviceConnected,
        )
    }

    /**
     * Schedules a single re-arm of the engine after [ObdConnectionPolicy.backoffDelayMs] for
     * the current attempt, and immediately surfaces the reconnecting state so the ongoing
     * notification never lingers on the terminal Error/Disconnected text.
     */
    private fun scheduleReconnect() {
        val transport = activeTransport
        val vehicle = activeVehicle
        if (transport == null || vehicle == null) {
            Log.w(TAG, "auto-reconnect skipped: no active transport/vehicle")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val attempt = reconnectAttempt
        reconnectAttempt++
        val delayMs = ObdConnectionPolicy.backoffDelayMs(attempt)
        Log.i(
            TAG,
            "auto-reconnect ${attempt + 1}/${ObdConnectionPolicy.MAX_AUTO_RECONNECT_ATTEMPTS} " +
                "in ${delayMs}ms",
        )

        val reconnecting = (latestState ?: LiveObdState()).copy(
            status = ObdStatus.Connecting,
            lastError = null,
            connectionStage = ObdConnectStage.ConnectingSocket,
            connectingSinceMs = System.currentTimeMillis(),
        )
        latestState = reconnecting
        lastLiveAtMs = System.currentTimeMillis()
        lastStale = false
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(reconnecting, stale = false))

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!logging || stopped) return@launch
            // Reset the per-attempt startup gate so a failed re-arm still gets its grace
            // window before the service would stop itself.
            startedAtMs = System.currentTimeMillis()
            sawData = false
            lastLiveAtMs = System.currentTimeMillis()
            acquireWakeLock()
            engine.start(transport, vehicle)
        }
    }

    override fun onDestroy() {
        logging = false
        reconnectJob?.cancel()
        reconnectJob = null
        activeTransport = null
        activeVehicle = null
        // Never block the main thread on the engine: a run stuck on a silent adapter used to
        // freeze onDestroy here (runBlocking + join) until ANR. The stop runs on the engine's
        // own application-lifetime scope, bounded in time, and still flushes samples/bins/trip
        // and sends ATPC + closes the socket. A later start() is never cancelled by it.
        engine.requestStop()
        overlay.hide()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        scope.cancel()
        // Best-effort: prune expired raw samples immediately on stop (the daily worker is the
        // guarantee; this is a short-lived detached scope so it isn't cancelled with the service).
        CoroutineScope(Dispatchers.IO).launch { runCatching { retentionPruner.prune() } }
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

    /**
     * Signature of the text [buildNotification] would render, so the ~4 Hz live collector can
     * skip a redundant `notify()` until the display actually changes (or a second elapses).
     */
    private fun notificationKey(state: LiveObdState?, stale: Boolean): String {
        val status = state?.status?.name ?: "null"
        val speed = state?.speedKmh?.let { Math.round(it).toString() } ?: "-"
        val readout = state?.let { ConsumptionReadout.of(it.instantL100, it.fuelRateLph) }
        val consumption = readout?.let { String.format(Locale.US, "%.1f%s", it.value, if (it.perHour) "h" else "") } ?: "-"
        return "$status|$speed|$consumption|$stale"
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
        val text = when {
            state?.status == ObdStatus.Connecting -> getString(R.string.notification_connecting)
            state?.status == ObdStatus.Disconnected -> getString(R.string.notification_waiting)
            state?.speedKmh != null -> {
                // L/100 km while moving, L/h when crawling/idle (never fuel/speed per sample).
                val readout = ConsumptionReadout.of(state.instantL100, state.fuelRateLph)
                val value = readout?.let { String.format(Locale.US, "%.1f", it.value) } ?: "-"
                val template = if (readout?.perHour == true) R.string.notification_live_lph else R.string.notification_live
                getString(template, Math.round(state.speedKmh), value)
            }
            else -> getString(R.string.notification_text)
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
        const val EXTRA_AUTO = "auto_started"
        const val EXTRA_OPEN_STATS = "open_stats"

        private const val WAKE_LOCK_TAG = "FuelRoute:obd"
        private const val WAKE_LOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000
        private const val SERVICE_TICK_MS = 5_000L
        private const val STALE_AFTER_MS = 10_000L
        private const val NOTIFY_MIN_INTERVAL_MS = 1_000L
        private const val TAG = "FuelRoute"

        /**
         * Set by [start] just before the service is launched so an explicit user reconnect
         * can override the `stopped` latch. A redelivered start intent does not go through
         * [start], so it stays latched out (card 34).
         */
        @Volatile
        private var freshStart = false

        fun start(context: Context, address: String?, auto: Boolean = false) {
            freshStart = true
            context.startForegroundService(
                Intent(context, ObdLoggingService::class.java)
                    .putExtra(EXTRA_ADDRESS, address)
                    .putExtra(EXTRA_AUTO, auto),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ObdLoggingService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}