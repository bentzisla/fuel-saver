package com.fuelroute.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fuelroute.MainActivity
import com.fuelroute.R
import com.fuelroute.data.obd.ObdEngine
import com.fuelroute.data.obd.ObdPresenceProbe
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.domain.obd.ObdProbePolicy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One background presence check (see [ObdProbePolicy]): skip cheaply when there is nothing to do,
 * otherwise probe the saved dongle and start logging when the engine is running.
 */
@Singleton
class ObdProbeRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val engine: ObdEngine,
    private val probe: ObdPresenceProbe,
) {

    suspend fun runOnce(nowMs: Long = System.currentTimeMillis()) {
        val settings = settingsRepository.settings.first()
        val address = settings.lastDeviceAddress
        @Suppress("DEPRECATION")
        val bluetoothOn = runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true }.getOrDefault(false)
        val skip = ObdProbePolicy.skipReason(
            enabled = settings.obdProbeEnabled,
            autoConnect = settings.autoConnect,
            hasDevice = !address.isNullOrBlank(),
            hasPermission = BluetoothAclReceiver.hasBluetoothPermission(context),
            bluetoothOn = bluetoothOn,
            loggingActive = ObdLoggingService.isLogging || engine.isRunning,
        )
        if (skip != null || address == null) {
            Log.d(TAG, "OBD probe skipped: $skip")
            settingsRepository.saveProbeResult(nowMs, skip?.name ?: "NO_DEVICE", settings.probeAbsentSinceMs)
            return
        }

        val outcome = probe.probe(address)
        val absentSinceMs = if (outcome == ObdProbePolicy.Outcome.ABSENT) settings.probeAbsentSinceMs ?: nowMs else null
        settingsRepository.saveProbeResult(nowMs, outcome.name, absentSinceMs)

        val action = ObdProbePolicy.afterProbe(
            outcome = outcome,
            manualDisconnect = settings.manualDisconnect,
            absentForMs = absentSinceMs?.let { nowMs - it } ?: 0L,
        )
        when (action) {
            ObdProbePolicy.Action.START_LOGGING -> startLogging(address)
            ObdProbePolicy.Action.CLEAR_MANUAL_DISCONNECT -> {
                Log.i(TAG, "OBD probe: the drive after a manual disconnect is over ($outcome); auto-connect re-armed")
                settingsRepository.saveManualDisconnect(false)
            }
            ObdProbePolicy.Action.NOTHING -> Unit
        }
    }

    /**
     * Starts the logging service. Android only lets a background app start a foreground service
     * in exempt cases (here: the battery-optimization exemption Settings offers). When that is
     * missing, a silent notification lets the user start it with one tap instead.
     */
    private fun startLogging(address: String) {
        try {
            Log.i(TAG, "OBD probe: engine running — starting logging for $address")
            ObdLoggingService.start(context, address, auto = true)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException.
            Log.w(TAG, "OBD probe: cannot start logging from the background (${e.javaClass.simpleName})")
            notifyDriveDetected(address)
        }
    }

    private fun notifyDriveDetected(address: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.probe_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val tap = PendingIntent.getActivity(
            context,
            REQUEST_START,
            Intent(context, MainActivity::class.java)
                .putExtra(ObdLoggingService.EXTRA_OPEN_STATS, true)
                .putExtra(EXTRA_START_ADDRESS, address)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.probe_notification_title))
            .setContentText(context.getString(R.string.probe_notification_text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        private const val TAG = "FuelRoute"
        private const val CHANNEL_ID = "obd_drive_detected"
        const val NOTIFICATION_ID = 2
        private const val REQUEST_START = 3

        /** MainActivity extra: start logging for this dongle (the tap on the fallback notification). */
        const val EXTRA_START_ADDRESS = "probe_start_address"
    }
}

/**
 * Runs [ObdProbeRunner] and queues the next run. WorkManager's periodic work cannot repeat faster
 * than every 15 minutes, so the probe is a chain of one-time works, each appending the next with
 * the configured delay. The chain survives reboots and app updates with the rest of WorkManager's
 * queue; [ObdProbeScheduler.ensureScheduled] restarts it after a force-stop.
 */
class ObdProbeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, ObdProbeEntryPoint::class.java)
        try {
            entryPoint.runner().runOnce()
        } catch (t: Throwable) {
            if (isStopped) throw t
            Log.e("FuelRoute", "OBD probe failed", t)
        }
        // A cancelled run (settings changed: the scheduler already started a fresh chain) must not
        // queue a second one. Success keeps the chain alive; a failure would cancel what follows.
        if (!isStopped) entryPoint.scheduler().scheduleNext()
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "obd_presence_probe"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ObdProbeEntryPoint {
    fun runner(): ObdProbeRunner
    fun scheduler(): ObdProbeScheduler
}

/** Owns the probe chain: start it, restart it when its settings change, or stop it. */
@Singleton
class ObdProbeScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val settingsRepository: SettingsRepository,
) {

    /** App start: make sure a chain exists (keeps a pending one untouched). */
    fun ensureScheduled() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { apply(ExistingWorkPolicy.KEEP) }
                .onFailure { Log.e("FuelRoute", "OBD probe scheduling failed", it) }
        }
    }

    /** The probe settings changed: start over with the new interval (or stop). */
    suspend fun reschedule() = apply(ExistingWorkPolicy.REPLACE)

    /** Called at the end of each run to queue the next one. */
    suspend fun scheduleNext() = apply(ExistingWorkPolicy.APPEND_OR_REPLACE)

    private suspend fun apply(policy: ExistingWorkPolicy) {
        val settings = settingsRepository.settings.first()
        if (!settings.obdProbeEnabled) {
            workManager.cancelUniqueWork(ObdProbeWorker.WORK_NAME)
            return
        }
        val request = OneTimeWorkRequestBuilder<ObdProbeWorker>()
            .setInitialDelay(ObdProbePolicy.clampIntervalMin(settings.obdProbeIntervalMin).toLong(), TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(ObdProbeWorker.WORK_NAME, policy, request)
    }
}
