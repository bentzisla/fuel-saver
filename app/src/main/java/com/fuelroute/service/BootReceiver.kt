package com.fuelroute.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-arms zero-touch OBD logging after a reboot.
 *
 * Starting a `connectedDevice` foreground service directly from `BOOT_COMPLETED` is
 * blocked on modern Android (and would be wasteful), so this receiver only clears the
 * start/stop debounce. The manifest-registered [BluetoothAclReceiver] then starts logging
 * when the adapter reconnects — including when the user never opened the app that day.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        BluetoothAclReceiver.clearDebounce()
        Log.i(TAG, "boot completed — OBD auto-connect re-armed")
    }

    companion object {
        private const val TAG = "FuelRoute"
    }
}
