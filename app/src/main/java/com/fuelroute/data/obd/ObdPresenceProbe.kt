package com.fuelroute.data.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.os.SystemClock
import android.util.Log
import com.fuelroute.domain.obd.ElmProtocol
import com.fuelroute.domain.obd.ObdConnectionPolicy
import com.fuelroute.domain.obd.ObdProbePolicy
import com.fuelroute.domain.obd.ObdProbePolicy.Engine
import com.fuelroute.domain.obd.ObdProbePolicy.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide marker of the probe's own Bluetooth traffic. Opening and closing the probe socket
 * makes the dongle's ACL link go up and down; [com.fuelroute.service.BluetoothAclReceiver] asks
 * [isSuppressed] so those broadcasts neither start logging for a parked car nor stop the logging
 * the probe itself just started.
 */
object ObdProbeGate {

    @Volatile
    private var activeAddress: String? = null

    @Volatile
    private var lastAddress: String? = null

    @Volatile
    private var suppressUntilMs = 0L

    fun begin(address: String) {
        activeAddress = address.uppercase()
        lastAddress = activeAddress
    }

    fun end(nowMs: Long = SystemClock.elapsedRealtime()) {
        activeAddress = null
        suppressUntilMs = nowMs + ObdProbePolicy.ACL_SUPPRESS_GRACE_MS
    }

    fun isSuppressed(address: String, nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (lastAddress != address.uppercase()) return false
        return ObdProbePolicy.isAclSuppressed(activeAddress != null, suppressUntilMs, nowMs)
    }
}

/**
 * One quiet presence check of the saved dongle (see [ObdProbePolicy]): a single short RFCOMM
 * connect, an ELM reset, then `ATRV` (a resting battery ends the check without waking the car's
 * bus) and, only when the voltage cannot rule a running engine out, `010C`. The socket is always
 * closed again; the probe never keeps the dongle.
 */
@Singleton
class ObdPresenceProbe @Inject constructor() {

    @SuppressLint("MissingPermission")
    suspend fun probe(address: String): Outcome = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withContext Outcome.ABSENT
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return@withContext Outcome.ABSENT
        val transport = BluetoothClassicTransport(device, probe = true)
        ObdProbeGate.begin(address)
        try {
            if (transport.connect().isFailure) {
                Log.i(TAG, "probe $address: not reachable")
                return@withContext Outcome.ABSENT
            }
            engineState(transport).also { Log.i(TAG, "probe $address: $it") }
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
            ObdProbeGate.end()
        }
    }

    private suspend fun engineState(transport: ObdTransport): Outcome {
        transport.sendSoft("", ObdConnectionPolicy.LINE_CLEAR_TIMEOUT_MS)
        delay(ObdConnectionPolicy.LINE_CLEAR_SETTLE_MS)
        if (!reset(transport)) return Outcome.UNRESPONSIVE

        transport.sendCommand("ATE0", ObdConnectionPolicy.INIT_READ_TIMEOUT_MS)
        val voltsRaw = transport.sendCommand(ElmProtocol.CMD_BATTERY_VOLTAGE, ObdConnectionPolicy.INIT_READ_TIMEOUT_MS)
        if (!transport.isConnected) return Outcome.UNRESPONSIVE
        val volts = ElmProtocol.batteryVoltage(voltsRaw)
        if (ObdProbePolicy.engineFromVoltage(volts) == Engine.OFF) {
            Log.i(TAG, "probe: battery at ${volts}V, engine off")
            return Outcome.ENGINE_OFF
        }

        transport.sendCommand("ATSP0", ObdConnectionPolicy.INIT_READ_TIMEOUT_MS)
        // The first data request runs the automatic protocol search, hence the long deadline.
        val rpmRaw = transport.sendCommand(
            ElmProtocol.command(ElmProtocol.PID_RPM),
            ObdConnectionPolicy.PROTOCOL_SEARCH_TIMEOUT_MS,
        )
        val rpm = ElmProtocol.rpm(rpmRaw)
        Log.i(TAG, "probe: battery ${volts}V, 010C -> ${ElmLink.printable(rpmRaw)} (rpm=$rpm)")
        return when (ObdProbePolicy.engineFromRpm(rpm, replied = rpmRaw.isNotBlank())) {
            Engine.RUNNING -> Outcome.ENGINE_RUNNING
            Engine.OFF -> Outcome.ENGINE_OFF
            Engine.UNKNOWN -> Outcome.UNRESPONSIVE
        }
    }

    /** The engine's reset ladder in miniature: true once the adapter answered with a banner. */
    private suspend fun reset(transport: ObdTransport): Boolean {
        for (command in ElmProtocol.resetSequence) {
            val raw = transport.sendSoft(command, ObdConnectionPolicy.initReadTimeoutMs(command))
            if (!transport.isConnected) return false
            if (ElmProtocol.isAcceptedAdapterBanner(raw.removeSuffix(">"))) return true
            if (raw.isEmpty()) return false
            delay(ObdConnectionPolicy.RESET_RETRY_PAUSE_MS)
        }
        return false
    }

    private companion object {
        const val TAG = "FuelRoute"
    }
}
