package com.fuelroute

import android.app.Application
import com.fuelroute.data.obd.LearnedDataRepair
import com.fuelroute.data.settings.ObdLinkStore
import com.fuelroute.service.ObdProbeScheduler
import com.fuelroute.service.RetentionScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FuelRouteApp : Application() {

    @Inject
    lateinit var retentionScheduler: RetentionScheduler

    @Inject
    lateinit var learnedDataRepair: LearnedDataRepair

    @Inject
    lateinit var obdProbeScheduler: ObdProbeScheduler

    @Inject
    lateinit var obdLinkStore: ObdLinkStore

    override fun onCreate() {
        super.onCreate()
        // Ensure the daily sample-retention job exists (ExistingPeriodicWorkPolicy.KEEP).
        retentionScheduler.schedule()
        // Background, idempotent repair of learned data corrupted before 0.7 (no-op when clean).
        learnedDataRepair.launch()
        // Remembered OBD bus protocols (ATSP<n> instead of a protocol search), persisted on change.
        obdLinkStore.attach()
        // Quiet periodic check for the saved OBD dongle (KEEP: a pending run is left alone).
        obdProbeScheduler.ensureScheduled()
    }
}