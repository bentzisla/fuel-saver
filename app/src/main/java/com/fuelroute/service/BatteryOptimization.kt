package com.fuelroute.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Helper around the "ignore battery optimizations" exemption. Keeping the OBD
 * logging foreground service alive while the screen is off depends on the app
 * being exempt from Doze / App Standby, so Settings offers to request it.
 */
object BatteryOptimization {

    fun isIgnoring(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun request(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}")),
            )
        }
    }
}