package com.fuelroute

import android.app.Application
import com.fuelroute.data.obd.LearnedDataRepair
import com.fuelroute.service.RetentionScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FuelRouteApp : Application() {

    @Inject
    lateinit var retentionScheduler: RetentionScheduler

    @Inject
    lateinit var learnedDataRepair: LearnedDataRepair

    override fun onCreate() {
        super.onCreate()
        // Ensure the daily sample-retention job exists (ExistingPeriodicWorkPolicy.KEEP).
        retentionScheduler.schedule()
        // Background, idempotent repair of learned data corrupted before 0.7 (no-op when clean).
        learnedDataRepair.launch()
    }
}