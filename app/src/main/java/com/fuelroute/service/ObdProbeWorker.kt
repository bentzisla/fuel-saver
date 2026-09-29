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
import com.fuelroute.data.obd.ObdDongleSession
import com.fuelroute.data.obd.ObdEngine
import com.fuelroute.data.obd.ObdPresenceProbe
import com.fuelroute.data.settings.ObdLinkStore
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
    private val linkStore: ObdLinkStore,
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
            loggingActive = ObdLoggingService.isLogging || engine.isRunning || ObdDongleSession.isEngineActive,
        )
        if (skip != null || address == null) {
            Log.d(TAG, "OBD probe skipped: $skip")
            settingsRepository.saveProbeResult(nowMs, skip?.name ?: "NO_DEVICE", settings.probeAbsentSinceMs)
            // A drive is being logged: the next probe after it starts from the normal interval.
            if (skip == ObdProbePolicy.Skip.ALREADY_LOGGING) linkStore.saveProbeStreaks(ObdLinkStore.ProbeStreaks())
            return
        }

        val streaks = linkStore.probeStreaks()
        val knownProtocol = linkStore.protocols()[address.uppercase()]
        val outcome = probe.probe(address, knownProtocol, streaks.off)
        linkStore.saveProbeStreaks(
            ObdLinkStore.ProbeStreaks(
                quiet = ObdProbePolicy.nextQuietStreak(streaks.quiet, outcome),
                off = ObdProbePolicy.nextOffStreak(streaks.off, outcome),
            ),
        )
        val absentSinceMs = if (outcome == ObdProbePolicy.Outcome.ABSENT) settings.probeAbsentSinceMs ?: nowMs else null
        // A probe that yielded to the engine means logging took over (shown as such in Settings).
        val recorded = if (outcome == ObdProbePolicy.Outcome.YIELDED) ObdProbePolicy.Skip.ALREADY_LOGGING.name else outcome.name
        settingsRepository.saveProbeResult(nowMs, recorded, absentSinceMs)

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
     * missing, a heads-up notification lets the user start it with one tap instead.
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

    /**
     * "Drive detected — tap to start logging" on a dedicated high-importance channel. The tap (and
     * the action button) opens [MainActivity] with [EXTRA_START_ADDRESS]; the activity is then in
     * the foreground, so it may start the logging service.
     */
    private fun notifyDriveDetected(address: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.deleteNotificationChannel(ObdProbePolicy.LEGACY_FALLBACK_CHANNEL_ID) }
        manager.createNotificationChannel(
            NotificationChannel(
                ObdProbePolicy.FALLBACK_CHANNEL_ID,
                context.getString(R.string.probe_alert_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.probe_alert_channel_description)
            },
        )
        val start = PendingIntent.getActivity(
            context,
            REQUEST_START,
            Intent(context, MainActivity::class.java)
                .putExtra(ObdLoggingService.EXTRA_OPEN_STATS, true)
                .putExtra(EXTRA_START_ADDRESS, address)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, ObdProbePolicy.FALLBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.probe_notification_title))
            .setContentText(context.getString(R.string.probe_notification_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(start)
            .addAction(0, context.getString(R.string.probe_notification_action_start), start)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(ObdProbePolicy.FALLBACK_NOTIFICATION_TIMEOUT_MS)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        private const val TAG = "FuelRoute"
        const val NOTIFICATION_ID = 2
        private const val REQUEST_START = 3

        /** MainActivity extra: start logging for this dongle (the tap on the fallback notification). */
        const val EXTRA_START_ADDRESS = "probe_start_address"
    }
}

/**
 * Runs [ObdProbeRunner] and queues the next run. WorkManager's periodic work cannot repeat faster
 * than every 15 minutes, so the probe is a chain of one-time works, each appending the next with
 * the configured delay (stretched by [ObdProbePolicy.nextDelayMin] while the car stays parked or
 * away). The chain survives reboots and app updates with the rest of WorkManager's queue;
 * [ObdProbeScheduler.ensureScheduled] restarts it after a force-stop.
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
    private val linkStore: ObdLinkStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** App start: make sure a chain exists (keeps a pending one untouched). */
    fun ensureScheduled() {
        scope.launch {
            runCatching { apply(ExistingWorkPolicy.KEEP) }
                .onFailure { Log.e("FuelRoute", "OBD probe scheduling failed", it) }
        }
    }

    /**
     * The user is around (the app was opened, or the dongle's ACL link came up outside a probe):
     * end the quiet-probe backoff, and when it had stretched the interval, restart the chain with
     * the normal one so a drive starting now is not missed for up to 30 minutes.
     */
    fun resetBackoff() {
        scope.launch {
            runCatching {
                val streaks = linkStore.probeStreaks()
                if (streaks.quiet == 0) return@runCatching
                linkStore.saveProbeStreaks(streaks.copy(quiet = 0))
                val interval = settingsRepository.settings.first().obdProbeIntervalMin
                if (ObdProbePolicy.isBackedOff(interval, streaks.quiet)) {
                    Log.i("FuelRoute", "OBD probe: backoff reset after ${streaks.quiet} quiet probes")
                    apply(ExistingWorkPolicy.REPLACE)
                }
            }.onFailure { Log.e("FuelRoute", "OBD probe backoff reset failed", it) }
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
        val delayMin = ObdProbePolicy.nextDelayMin(settings.obdProbeIntervalMin, linkStore.probeStreaks().quiet)
        val request = OneTimeWorkRequestBuilder<ObdProbeWorker>()
            .setInitialDelay(delayMin.toLong(), TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(ObdProbeWorker.WORK_NAME, policy, request)
    }
}
