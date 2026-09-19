package com.fuelroute.data.obd

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface BluetoothDevicesRepository {
    suspend fun bondedDevices(): List<BluetoothDevice>
}

@Singleton
class DefaultBluetoothDevicesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : BluetoothDevicesRepository {

    override suspend fun bondedDevices(): List<BluetoothDevice> = withContext(Dispatchers.IO) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withContext emptyList()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext emptyList()
        }
        runCatching { adapter.bondedDevices.toList() }
            .getOrDefault(emptyList())
            .sortedBy { it.name ?: it.address }
    }
}