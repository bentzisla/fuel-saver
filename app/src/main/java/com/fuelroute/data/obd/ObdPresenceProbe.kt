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
 * connect, then [ObdProbeConversation]. The whole check runs inside [ObdDongleSession.runProbe], so
 * it never overlaps the logging engine and gives the dongle up at once when the engine claims it
 * ([Outcome.YIELDED]). The socket is always closed again; the probe never keeps the dongle.
 */
@Singleton
class ObdPresenceProbe @Inject constructor() {

    /**
     * @param knownProtocol the bus protocol this dongle last locked (`ATDPN`), or null to search.
     * @param offStreak consecutive engine-off results so far; after
     *   [ObdProbePolicy.TRUST_PROTOCOL_AFTER_OFF] of them a failure under [knownProtocol] is
     *   trusted without an automatic search.
     */
    @SuppressLint("MissingPermission")
    suspend fun probe(address: String, knownProtocol: Int?, offStreak: Int): Outcome = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withContext Outcome.ABSENT
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return@withContext Outcome.ABSENT
        val transport = BluetoothClassicTransport(device, probe = true)
        val outcome = ObdDongleSession.runProbe(abort = { transport.abort("engine claimed the dongle") }) {
            ObdProbeGate.begin(address)
            try {
                if (transport.connect().isFailure) {
                    Log.i(TAG, "probe $address: not reachable")
                    Outcome.ABSENT
                } else {
                    val result = ObdProbeConversation { Log.i(TAG, it) }.run(transport, knownProtocol, offStreak)
                    ObdProtocolMemory.remember(address, result.lockedProtocol)
                    result.outcome
                }
            } finally {
                withContext(NonCancellable) { transport.disconnect() }
                ObdProbeGate.end()
            }
        }
        (outcome ?: Outcome.YIELDED).also { Log.i(TAG, "probe $address: $it") }
    }

    private companion object {
        const val TAG = "FuelRoute"
    }
}

/**
 * The probe's ELM conversation on an open [ObdTransport] (pure JVM, unit-tested with a scripted
 * transport): line clear, the reset ladder, `ATRV` (diagnostic only, see
 * [ObdProbePolicy.classifyVoltage]), then `010C` under the remembered bus protocol (`ATSP<n>`,
 * no 5-20 s search) or the automatic search (`ATSP0`).
 *
 * Voltage never ends the check by itself: a smart alternator keeps a running car at 12.4-12.8 V,
 * which used to read as "engine off" so the drive was never logged. RPM decides. When the
 * remembered protocol gets no RPM, the automatic search is tried once, unless the car has been
 * seen off [ObdProbePolicy.TRUST_PROTOCOL_AFTER_OFF] times in a row (searching would then only
 * wake the parked car's bus again).
 */
class ObdProbeConversation(private val log: (String) -> Unit = {}) {

    /** What the conversation found, plus the protocol the bus locked on (for [ObdProtocolMemory]). */
    data class Result(val outcome: Outcome, val lockedProtocol: Int? = null, val volts: Double? = null)

    suspend fun run(transport: ObdTransport, knownProtocol: Int?, offStreak: Int): Result {
        transport.sendSoft("", ObdConnectionPolicy.LINE_CLEAR_TIMEOUT_MS)
        delay(ObdConnectionPolicy.LINE_CLEAR_SETTLE_MS)
        if (!reset(transport)) return Result(Outcome.UNRESPONSIVE)

        transport.sendCommand("ATE0", ObdConnectionPolicy.INIT_READ_TIMEOUT_MS)
        val voltsRaw = transport.sendCommand(ElmProtocol.CMD_BATTERY_VOLTAGE, ObdConnectionPolicy.INIT_READ_TIMEOUT_MS)
        if (!transport.isConnected) return Result(Outcome.UNRESPONSIVE)
        val volts = ElmProtocol.batteryVoltage(voltsRaw)
        val voltage = ObdProbePolicy.classifyVoltage(volts)

        val protocolCommand = ElmProtocol.setProtocolCommand(knownProtocol)
        var reading = readRpm(transport, protocolCommand)
        if (protocolCommand != ElmProtocol.CMD_AUTO_PROTOCOL && reading.rpm == null && reading.engine == Engine.OFF) {
            if (ObdProbePolicy.fallbackSearchAllowed(offStreak)) {
                log("probe: no RPM under protocol $knownProtocol, falling back to the automatic search")
                reading = readRpm(transport, ElmProtocol.CMD_AUTO_PROTOCOL)
            } else {
                log("probe: no RPM under protocol $knownProtocol after $offStreak engine-off probes, not searching")
            }
        }
        log("probe: battery ${volts}V ($voltage), 010C -> ${ElmLink.printable(reading.raw)} (rpm=${reading.rpm})")

        // A parsed RPM means the bus locked: remember its protocol for the next probe/session.
        val locked = if (reading.rpm != null && transport.isConnected) {
            ElmProtocol.parseProtocolNumber(
                transport.sendCommand(ElmProtocol.CMD_DESCRIBE_PROTOCOL_NUMBER, ObdConnectionPolicy.INIT_READ_TIMEOUT_MS),
            )
        } else {
            null
        }
        val outcome = when (reading.engine) {
            Engine.RUNNING -> Outcome.ENGINE_RUNNING
            Engine.OFF -> Outcome.ENGINE_OFF
            Engine.UNKNOWN -> Outcome.UNRESPONSIVE
        }
        return Result(outcome, locked, volts)
    }

    private class RpmReading(val raw: String, val rpm: Double?, val engine: Engine)

    private suspend fun readRpm(transport: ObdTransport, protocolCommand: String): RpmReading {
        transport.sendCommand(protocolCommand, ObdConnectionPolicy.INIT_READ_TIMEOUT_MS)
        // The first data request may run the protocol search / bus init, hence the long deadline.
        val raw = transport.sendCommand(
            ElmProtocol.command(ElmProtocol.PID_RPM),
            ObdConnectionPolicy.PROTOCOL_SEARCH_TIMEOUT_MS,
        )
        val rpm = ElmProtocol.rpm(raw)
        return RpmReading(raw, rpm, ObdProbePolicy.engineFromRpm(rpm, replied = raw.isNotBlank()))
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
}
