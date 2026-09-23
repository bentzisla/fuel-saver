package com.fuelroute.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.fuelroute.data.obd.BluetoothClassicTransport
import com.fuelroute.data.obd.ObdEngine
import com.fuelroute.data.obd.ObdStatus
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.domain.obd.AutoConnectDebounce
import com.fuelroute.domain.obd.BondedObdDevice
import com.fuelroute.domain.obd.ObdConnectionPolicy
import com.fuelroute.domain.obd.ObdDeviceMatcher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Zero-touch OBD entry point. Manifest-registered so it fires while the app is dead:
 *
 * - `ACL_CONNECTED` → start logging for the resolved adapter (last used, or a bonded
 *   ELM-pattern name match on first ever use), then remember it.
 * - `ACL_DISCONNECTED` → stop logging (the engine flushes bins and closes the open trip)
 *   and arm a short debounce so a flap does not spin the run loop.
 * - `STATE_CHANGED` → `STATE_ON` re-arms only when the resolved target is already
 *   ACL-connected (tracked from the ACL broadcasts in this process). Turning Bluetooth on
 *   with a paired-but-absent dongle must not summon the foreground notification.
 *
 * `ACTION_ACL_CONNECTED` is only delivered while the app is in the background when
 * `BLUETOOTH_CONNECT` is granted, which makes it a valid `connectedDevice`
 * foreground-service start trigger on API 34+.
 */
@AndroidEntryPoint
class BluetoothAclReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var engine: ObdEngine

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> onConnected(context, intent)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> onDisconnected(context, intent)
            BluetoothAdapter.ACTION_STATE_CHANGED ->
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                    BluetoothAdapter.STATE_ON
                ) {
                    onBluetoothOn(context)
                }
        }
    }

    private fun onConnected(context: Context, intent: Intent) {
        val device = intent.bluetoothDeviceExtra() ?: return
        val address = device.address ?: return
        if (!hasBluetoothPermission(context)) {
            Log.w(TAG, "ACL_CONNECTED ignored: BLUETOOTH_CONNECT not granted")
            return
        }
        markConnected(address)

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.settings.first()
                if (ObdConnectionPolicy.shouldSuppressAutoConnect(settings.manualDisconnect)) {
                    Log.i(TAG, "ACL_CONNECTED ignored: manual disconnect latch is set")
                    return@launch
                }
                val resolved = decideStart(
                    autoConnect = settings.autoConnect,
                    lastDeviceAddress = settings.lastDeviceAddress,
                    address = address,
                    name = deviceName(device),
                ) ?: return@launch
                if (AutoConnectDebounce.shouldIgnoreStart(lastStopAtMs, System.currentTimeMillis())) {
                    Log.i(TAG, "ignoring start for $resolved inside the debounce window")
                    return@launch
                }
                val name = deviceName(device)
                if (settings.lastDeviceAddress.isNullOrBlank()) {
                    settingsRepository.saveLastDeviceAddress(resolved)
                    Log.i(TAG, "auto-resolved OBD adapter $resolved from a name match")
                }
                if (!name.isNullOrBlank()) settingsRepository.saveLastDeviceName(name)
                Log.i(TAG, "adapter $resolved reconnected — starting logging")
                ObdLoggingService.start(context, resolved, auto = true)
            } catch (t: Throwable) {
                Log.e(TAG, "ACL_CONNECTED handling failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun onDisconnected(context: Context, intent: Intent) {
        val device = intent.bluetoothDeviceExtra() ?: return
        val address = device.address ?: return
        markDisconnected(address)

        // For an SPP-only dongle the ACL link exists only while one of OUR sockets is open, so
        // every failed connect variant, init-retry reopen or reconnect makes it flap. Stopping
        // the service on those flaps killed our own connect mid-way (and armed the start
        // debounce against the retry). While the engine is connecting it handles a genuinely
        // vanished dongle itself (the connect fails and the service stops).
        val connecting = BluetoothClassicTransport.isConnectInFlight(address) ||
            engine.live.value.status == ObdStatus.Connecting
        if (ObdConnectionPolicy.shouldIgnoreAclDisconnect(connecting)) {
            Log.i(TAG, "ACL_DISCONNECTED for $address ignored: our own connect/init is in progress")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.settings.first()
                if (ObdDeviceMatcher.isTargetDevice(settings.lastDeviceAddress, address, deviceName(device))) {
                    lastStopAtMs = System.currentTimeMillis()
                    Log.i(TAG, "adapter $address disconnected — stopping logging")
                    ObdLoggingService.stop(context)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "ACL_DISCONNECTED handling failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onBluetoothOn(context: Context) {
        if (!hasBluetoothPermission(context)) {
            Log.w(TAG, "STATE_ON ignored: BLUETOOTH_CONNECT not granted")
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.settings.first()
                if (ObdConnectionPolicy.shouldSuppressAutoConnect(settings.manualDisconnect)) {
                    Log.i(TAG, "STATE_ON ignored: manual disconnect latch is set")
                    return@launch
                }
                if (!settings.autoConnect) return@launch
                val bonded = runCatching {
                    adapter.bondedDevices.orEmpty().map { BondedObdDevice(it.address, deviceName(it)) }
                }.getOrDefault(emptyList())
                val target = ObdDeviceMatcher.resolveBonded(
                    autoConnect = settings.autoConnect,
                    lastDeviceAddress = settings.lastDeviceAddress,
                    bonded = bonded,
                ) ?: return@launch
                if (!ObdConnectionPolicy.shouldAutoStartOnAdapterOn(target, connectedAddresses)) {
                    Log.i(TAG, "Bluetooth on but $target is not connected — not starting logging")
                    return@launch
                }
                if (AutoConnectDebounce.shouldIgnoreStart(lastStopAtMs, System.currentTimeMillis())) {
                    Log.i(TAG, "ignoring Bluetooth-on start for $target inside the debounce window")
                    return@launch
                }
                bonded.firstOrNull { it.address == target }?.name?.takeIf { it.isNotBlank() }?.let {
                    settingsRepository.saveLastDeviceName(it)
                }
                Log.i(TAG, "Bluetooth on — starting logging for bonded $target")
                ObdLoggingService.start(context, target, auto = true)
            } catch (t: Throwable) {
                Log.e(TAG, "STATE_ON handling failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice): String? =
        runCatching { device.name }.getOrNull()

    private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG = "FuelRoute"

        @Volatile
        private var lastStopAtMs: Long? = null

        /**
         * Addresses currently ACL-connected in this process, tracked from the ACL
         * broadcasts. Used so `STATE_ON` cannot start logging for a paired-but-absent
         * dongle. Empty after process death; the next `ACL_CONNECTED` repopulates it.
         */
        @Volatile
        private var connectedAddresses: Set<String> = emptySet()

        private fun markConnected(address: String) {
            connectedAddresses = connectedAddresses + address
        }

        private fun markDisconnected(address: String) {
            connectedAddresses = connectedAddresses - address
        }

        /** Clears the debounce after a reboot so the first ACL connect is not ignored. */
        fun clearDebounce() {
            lastStopAtMs = null
        }

        /** Pure decision used by [onReceive]; a null result means "do nothing". */
        fun decideStart(
            autoConnect: Boolean,
            lastDeviceAddress: String?,
            address: String?,
            name: String?,
        ): String? = ObdDeviceMatcher.resolveConnected(autoConnect, lastDeviceAddress, address, name)

        fun hasBluetoothPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    }
}
