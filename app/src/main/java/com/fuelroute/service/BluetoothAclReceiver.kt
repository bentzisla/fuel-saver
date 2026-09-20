package com.fuelroute.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.fuelroute.data.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restarts OBD logging automatically when the previously used adapter reconnects.
 *
 * `ACTION_ACL_CONNECTED` is only delivered while the app is in the background when
 * `BLUETOOTH_CONNECT` is granted, which makes it a valid `connectedDevice`
 * foreground-service start trigger on API 34+. We still verify that the user opted
 * into auto-connect and that the adapter is the last one they used.
 */
@AndroidEntryPoint
class BluetoothAclReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device = intent.bluetoothDeviceExtra() ?: return
        val address = device.address ?: return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.settings.first()
                if (settings.autoConnect && settings.lastDeviceAddress == address) {
                    Log.i(TAG, "adapter $address reconnected — starting logging")
                    ObdLoggingService.start(context, address)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG = "FuelRoute"
    }
}