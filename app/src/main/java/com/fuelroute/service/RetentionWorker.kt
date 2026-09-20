package com.fuelroute.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fuelroute.data.db.ObdSampleDao
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.domain.retention.RetentionPolicy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The actual prune operation, shared by [RetentionWorker] and [ObdLoggingService] so a
 * just-finished drive is cleaned up immediately instead of waiting for the next daily tick.
 * Reads the retention window from settings at call time, so a changed setting applies on the
 * next run without re-scheduling the job.
 */
@Singleton
class RetentionPruner @Inject constructor(
    private val sampleDao: ObdSampleDao,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun prune(nowMs: Long = System.currentTimeMillis()): Int {
        val retentionDays = settingsRepository.settings.first().retentionDays
        val deleted = sampleDao.deleteOlderThan(RetentionPolicy.cutoffMs(nowMs, retentionDays))
        Log.i(TAG, "retention prune removed $deleted samples older than $retentionDays days")
        return deleted
    }

    companion object {
        const val TAG = "FuelRoute"
    }
}

/**
 * Daily sample-retention job. A plain [CoroutineWorker] plus a Hilt [EntryPoint] keeps the
 * dependency surface minimal — deliberately no `androidx.hilt:hilt-work` / `@HiltWorker`.
 */
class RetentionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        entryPoint().retentionPruner().prune()
        Result.success()
    } catch (t: Throwable) {
        Log.e(RetentionPruner.TAG, "retention prune failed", t)
        Result.retry()
    }

    private fun entryPoint(): RetentionWorkerEntryPoint = EntryPointAccessors.fromApplication(
        applicationContext,
        RetentionWorkerEntryPoint::class.java,
    )

    companion object {
        const val WORK_NAME = "obd_sample_retention"
    }
}

/** Hilt access to the DB/settings singletons the worker needs (same pattern as `CarEntryPoint`). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RetentionWorkerEntryPoint {
    fun obdSampleDao(): ObdSampleDao
    fun settingsRepository(): SettingsRepository
    fun retentionPruner(): RetentionPruner
}

/**
 * Idempotently ensures the daily retention job exists. Uses [ExistingPeriodicWorkPolicy.KEEP] so
 * repeated calls on app start / setting change never reset the 24 h window.
 */
@Singleton
class RetentionScheduler @Inject constructor(
    private val workManager: WorkManager,
) {
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build()
        workManager.enqueueUniquePeriodicWork(
            RetentionWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}