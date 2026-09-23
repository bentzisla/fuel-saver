package com.fuelroute.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.fuelroute.BuildConfig

/**
 * Helper around the "ignore battery optimizations" exemption. Keeping the OBD
 * logging foreground service alive while the screen is off depends on the app
 * being exempt from Doze / App Standby, so Settings offers to request it.
 */
object BatteryOptimization {

    /**
     * The Google Play build does not declare REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (Play policy), so
     * there it reports "already exempt": every Settings prompt keyed on `!isIgnoring` disappears.
     */
    fun isIgnoring(context: Context): Boolean {
        if (!BuildConfig.SIDELOAD_FEATURES) return true
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun request(context: Context) {
        if (!BuildConfig.SIDELOAD_FEATURES) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}")),
            )
        }
    }
}