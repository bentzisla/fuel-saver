package com.fuelroute

import android.app.Application
import com.fuelroute.service.RetentionScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FuelRouteApp : Application() {

    @Inject
    lateinit var retentionScheduler: RetentionScheduler

    override fun onCreate() {
        super.onCreate()
        // Ensure the daily sample-retention job exists (ExistingPeriodicWorkPolicy.KEEP).
        retentionScheduler.schedule()
    }
}