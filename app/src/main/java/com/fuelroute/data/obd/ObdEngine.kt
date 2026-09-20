package com.fuelroute.data.obd

import android.util.Log
import com.fuelroute.data.db.ObdSampleDao
import com.fuelroute.data.db.ObdSampleEntity
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.data.db.SpeedBinStatsEntity
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.history.TripLinker
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.domain.learning.FuelRateCalculator
import com.fuelroute.domain.learning.SpeedBinAggregator
import com.fuelroute.domain.learning.TripDetector
import com.fuelroute.domain.model.ObdSample
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.VehicleProfile
import com.fuelroute.domain.obd.ElmProtocol
import com.fuelroute.domain.obd.ObdConnectionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

enum class ObdStatus { Disconnected, Connecting, Connected, Error }

data class LiveObdState(
    val status: ObdStatus = ObdStatus.Disconnected,
    val deviceName: String? = null,
    val speedKmh: Double? = null,
    val rpm: Double? = null,
    val coolantTempC: Double? = null,
    val fuelRateLph: Double? = null,
    val fuelLevelPct: Double? = null,
    val instantL100: Double? = null,
    val tripDistanceKm: Double = 0.0,
    val tripFuelL: Double = 0.0,
    val tripSeconds: Double = 0.0,
    val bins: List<SpeedBinStats> = emptyList(),
    val totalDistanceKm: Double = 0.0,
    val sampleCount: Int = 0,
    val lastRawReply: String? = null,
    val lastError: String? = null,
    val supportedPids: Set<Int> = emptySet(),
    val sampleRateHz: Double = 0.0,
    val batteryVoltage: Double? = null,
    val vin: String? = null,
)

@Singleton
class ObdEngine @Inject constructor(
    private val sampleDao: ObdSampleDao,
    private val speedBinDao: SpeedBinDao,
    private val tripDao: TripDao,
    private val fuelPriceRepository: FuelPriceRepository,
    private val tripLinker: TripLinker,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableLive = MutableStateFlow(LiveObdState())
    val live: StateFlow<LiveObdState> = mutableLive.asStateFlow()

    private var job: Job? = null

    /**
     * Set by [stop]/[disconnect]/[reset] to interrupt a reconnect backoff loop and the run
     * loop promptly, even while a blocking `connect()` is in flight. Volatile because a
     * stop may be requested from the UI thread while the engine loop runs on `Default`.
     */
    @Volatile
    private var stopRequested = false

    val isRunning: Boolean
        get() = job?.isActive == true

    fun start(transport: ObdTransport, vehicle: VehicleProfile) {
        if (job?.isActive == true) return
        stopRequested = false
        val vehicleId = vehicle.id
        job = scope.launch {
            // Every fresh attempt starts from a clean slate: a stale error/VIN from the
            // previous run must not leak into the new status line.
            mutableLive.update {
                it.copy(
                    status = ObdStatus.Connecting,
                    lastError = null,
                    deviceName = null,
                    vin = null,
                    supportedPids = emptySet(),
                )
            }
            val connected = transport.connect()
            if (stopRequested) return@launch
            if (connected.isFailure) {
                // Terminal: the run loop is never entered, so the logging service can stop
                // instead of lingering on the foreground notification.
                val cause = connected.exceptionOrNull()
                val reason = (cause as? ObdConnectException)?.reason
                    ?: if (cause is SocketTimeoutException) ObdConnectionPolicy.ERROR_CONNECT_TIMEOUT
                    else ObdConnectionPolicy.ERROR_CONNECT
                Log.w(TAG, "OBD connect failed ($reason)", cause)
                mutableLive.update { it.copy(status = ObdStatus.Error, lastError = reason) }
                return@launch
            }

            if (!initializeAdapter(transport)) {
                transport.disconnect()
                // Preserve the detailed `bad ATZ: …` reason set by initializeAdapter so the
                // UI can tell the user the dongle did not answer like an ELM327.
                mutableLive.update {
                    it.copy(status = ObdStatus.Error, lastError = it.lastError ?: "INIT")
                }
                return@launch
            }

            mutableLive.update {
                it.copy(status = ObdStatus.Connected, deviceName = transport.deviceName)
            }
            settleProtocol(transport)
            runLoop(transport, vehicle, vehicleId)
        }
    }

    fun stop() {
        stopRequested = true
        job?.cancel()
        job = null
        mutableLive.update { it.copy(status = ObdStatus.Disconnected) }
    }

    /**
     * Stops the run loop and clears the visible failure/device identity so the status line
     * reads a clean "disconnected" instead of a stale error. Learned totals already shown
     * on the dashboard are kept.
     */
    fun disconnect() {
        stop()
        mutableLive.update { it.copy(lastError = null, deviceName = null, vin = null) }
    }

    /**
     * Full reset: stops the run loop and wipes [LiveObdState] (error, VIN, PIDs, device)
     * so a subsequent [start] begins from a clean slate and can never be rejected by
     * leftover state from a previous attempt.
     */
    fun reset() {
        stop()
        mutableLive.value = LiveObdState()
    }

    /** Sends the ELM init sequence and validates the `ATZ` banner. */
    private suspend fun initializeAdapter(transport: ObdTransport): Boolean {
        val replies = mutableMapOf<String, String>()
        for (command in ElmProtocol.initializationCommands) {
            val reply = transport.sendCommand(command)
            replies[command] = reply
            Log.d(TAG, "ELM init $command -> ${reply.trim()}")
        }

        val atz = replies["ATZ"].orEmpty()
        if (!atz.contains("ELM327", ignoreCase = true)) {
            Log.e(TAG, "ATZ did not return ELM327 banner: \"$atz\"")
            mutableLive.update { it.copy(lastError = "bad ATZ: ${atz.trim()}") }
            return false
        }
        return true
    }

    /**
     * After `ATSP0` some adapters keep answering `SEARCHING...` while they auto-detect the
     * bus protocol. Give them up to [PROTOCOL_LOCK_TIMEOUT_MS] to settle (first real data
     * PID that is no longer SEARCHING wins). If it never settles we keep going — the run
     * loop surfaces SEARCHING via `lastError` instead of crashing.
     */
    private suspend fun settleProtocol(transport: ObdTransport) {
        val deadline = System.currentTimeMillis() + PROTOCOL_LOCK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val raw = transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_SPEED))
            if (!raw.contains("SEARCHING", ignoreCase = true)) return
            delay(PROTOCOL_LOCK_RETRY_MS)
        }
        Log.w(TAG, "protocol still SEARCHING after ${PROTOCOL_LOCK_TIMEOUT_MS}ms")
    }

    /** Probes `0100`/`0120`/`0140`/`0160` and merges the bitmaps. */
    private suspend fun negotiatePids(transport: ObdTransport): Set<Int> {
        val replies = mutableMapOf<Int, String>()
        for (base in ElmProtocol.supportedPidBlocks) {
            replies[base] = transport.sendCommand(ElmProtocol.command(base))
        }
        val supported = ElmProtocol.supportedPids(replies)
        Log.d(TAG, "supported PIDs: ${supported.sorted().joinToString { "%02X".format(it) }}")
        return supported
    }

    /** Reads the VIN once (Mode 09 PID 02). */
    private suspend fun readVin(transport: ObdTransport): String? =
        ElmProtocol.vin(transport.sendCommand(ElmProtocol.command09(ElmProtocol.PID_VIN)))

    private suspend fun runLoop(transport: ObdTransport, vehicle: VehicleProfile, vehicleId: String) {
        val bins = mutableMapOf<Int, SpeedBinStats>()
        speedBinDao.getForVehicle(vehicleId).forEach {
            bins[it.binIndex] = SpeedBinStats(
                vehicleId = it.vehicleId,
                binIndex = it.binIndex,
                distanceKm = it.distanceKm,
                fuelL = it.fuelL,
                seconds = it.seconds,
                samples = it.samples,
            )
        }

        val aggregator = SpeedBinAggregator()
        val tripDetector = TripDetector()
        val tripRecorder = TripRecorder(tripDao)
        tripRecorder.closeLeftovers(System.currentTimeMillis())

        var lastSample: ObdSample? = null
        var sampleCount = 0
        var tripDistance = 0.0
        var tripFuel = 0.0
        var tripSeconds = 0.0
        var tripIdleSeconds = 0.0
        var tripMaxSpeed = 0.0
        // Price snapshot used for actualCost at trip close; refreshed with the 30 s bin flush.
        var pricePerLiter = try {
            fuelPriceRepository.current(vehicle.grade).pricePerLiter
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            0.0
        }
        var lastBinPersistMs = System.currentTimeMillis()
        var lastSamplePersistMs = 0L
        var lastVoltageMs = 0L
        var batteryVoltage: Double? = null
        var rpmNullSinceMs: Long? = null
        var consecutiveBad = 0
        var supportedPids: Set<Int> = emptySet()
        val loopStartMs = System.currentTimeMillis()

        try {
            supportedPids = negotiatePids(transport)
            val vin = readVin(transport)
            mutableLive.update { it.copy(supportedPids = supportedPids, vin = vin) }

            while (true) {
                // A manual stop between polls must unwind immediately instead of waiting for
                // the next cancellation point.
                if (stopRequested) return
                val now = System.currentTimeMillis()

                val rawSpeed = transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_SPEED))
                val rawRpm = transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_RPM))
                // Coolant is always polled: the cold-engine exclusion in the aggregator
                // depends on it and it is not part of the optional negotiation set.
                val rawCoolant = transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_COOLANT_TEMP))
                val rawMaf = pollIf(transport, ElmProtocol.PID_MAF, supportedPids)
                val rawFuelRate = pollIf(transport, ElmProtocol.PID_FUEL_RATE, supportedPids)
                val rawMap = pollIf(transport, ElmProtocol.PID_MAP, supportedPids)
                val rawIat = pollIf(transport, ElmProtocol.PID_INTAKE_TEMP, supportedPids)
                val rawLoad = pollIf(transport, ElmProtocol.PID_ENGINE_LOAD, supportedPids)
                val rawFuelLevel = pollIf(transport, ElmProtocol.PID_FUEL_LEVEL, supportedPids)

                val speed = ElmProtocol.speed(rawSpeed)
                val rpm = ElmProtocol.rpm(rawRpm)
                val coolant = ElmProtocol.coolantTempC(rawCoolant)
                val maf = ElmProtocol.mafGps(rawMaf)
                val fuelRateRaw = ElmProtocol.fuelRateLph(rawFuelRate)

                val sample = ObdSample(
                    timestampMs = now,
                    speedKmh = speed,
                    rpm = rpm,
                    mafGps = maf,
                    fuelRateLph = fuelRateRaw,
                    mapKpa = ElmProtocol.mapKpa(rawMap),
                    intakeTempC = ElmProtocol.intakeTempC(rawIat),
                    coolantTempC = coolant,
                    engineLoadPct = ElmProtocol.engineLoadPct(rawLoad),
                    fuelLevelPct = ElmProtocol.fuelLevelPct(rawFuelLevel),
                )

                val fuelRate = FuelRateCalculator.fuelRateLph(sample, vehicle.fuelType, vehicle.engineDisplacementL)
                    ?.times(vehicle.fuelRateCorrection)
                // No clamp here: the aggregator rejects gaps > 2 s itself, while trip
                // totals need the true wall-clock delta.
                val dtSec = lastSample?.let { (now - it.timestampMs) / 1000.0 } ?: 0.0

                aggregator.accumulate(bins, sample, dtSec, fuelRate, vehicleId)

                if (sample.engineRunning) {
                    val rate = fuelRate ?: 0.0
                    tripFuel += rate * dtSec / 3600.0
                    tripDistance += (speed ?: 0.0) * dtSec / 3600.0
                    tripSeconds += dtSec
                    if ((speed ?: 0.0) < 1.0) tripIdleSeconds += dtSec
                    tripMaxSpeed = maxOf(tripMaxSpeed, speed ?: 0.0)
                }

                when (val transition = tripDetector.onSample(sample)) {
                    is TripDetector.TripTransition.Started -> {
                        tripRecorder.start(vehicleId, tripDetector.startedAtMs ?: sample.timestampMs)
                    }
                    is TripDetector.TripTransition.Ended -> {
                        // Anchor the link on the start the recorder actually wrote, not the
                        // detector transition, so a divergence can never mis-window the match.
                        val startedAtMs = tripRecorder.tripStartedAtMs
                        val closedTripId = tripRecorder.end(
                            vehicleId = vehicleId,
                            endedAtMs = transition.endedAtMs,
                            distanceKm = tripDistance,
                            fuelL = tripFuel,
                            maxSpeedKmh = tripMaxSpeed,
                            idleSeconds = tripIdleSeconds,
                            pricePerLiter = pricePerLiter,
                        )
                        closedTripId?.let {
                            tripLinker.autoLink(it, startedAtMs, transition.endedAtMs)
                        }
                        tripDistance = 0.0
                        tripFuel = 0.0
                        tripSeconds = 0.0
                        tripIdleSeconds = 0.0
                        tripMaxSpeed = 0.0
                    }
                    else -> Unit
                }

                sampleCount++
                if (now - lastSamplePersistMs >= SAMPLE_PERSIST_INTERVAL_MS) {
                    sampleDao.insert(sample.toEntity(vehicleId))
                    lastSamplePersistMs = now
                }

                if (now - lastBinPersistMs >= SPEED_BIN_PERSIST_INTERVAL_MS) {
                    speedBinDao.upsertAll(bins.values.map { it.toEntity() })
                    pricePerLiter = try {
                        fuelPriceRepository.current(vehicle.grade).pricePerLiter
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        pricePerLiter
                    }
                    if (tripRecorder.isOpen) {
                        tripRecorder.checkpoint(
                            vehicleId = vehicleId,
                            nowMs = now,
                            distanceKm = tripDistance,
                            fuelL = tripFuel,
                            maxSpeedKmh = tripMaxSpeed,
                            idleSeconds = tripIdleSeconds,
                        )
                    }
                    lastBinPersistMs = now
                }

                if (now - lastVoltageMs >= BATTERY_POLL_INTERVAL_MS) {
                    batteryVoltage = ElmProtocol.batteryVoltage(
                        transport.sendCommand(ElmProtocol.CMD_BATTERY_VOLTAGE),
                    )
                    lastVoltageMs = now
                }

                if (rpm == null) {
                    if (rpmNullSinceMs == null) rpmNullSinceMs = now
                } else {
                    rpmNullSinceMs = null
                }

                val speedKmh = speed ?: 0.0
                val instantL100 = if (speedKmh > 1.0 && fuelRate != null) fuelRate / speedKmh * 100.0 else null

                val badReason = when {
                    rawSpeed.contains("SEARCHING", ignoreCase = true) -> ObdConnectionPolicy.ERROR_SEARCHING
                    rawSpeed.contains("NO DATA", ignoreCase = true) ||
                        rawSpeed.contains("NODATA", ignoreCase = true) -> "NO DATA"
                    speed == null && rawSpeed.isNotBlank() -> "PARSE"
                    rawSpeed.isBlank() -> "TIMEOUT"
                    else -> null
                }
                if (badReason != null) consecutiveBad++ else consecutiveBad = 0
                val diagError = if (consecutiveBad >= CONSECUTIVE_ERROR_THRESHOLD) badReason else null

                val elapsedSec = ((now - loopStartMs) / 1000.0).coerceAtLeast(0.001)
                val sampleRateHz = sampleCount / elapsedSec

                mutableLive.update {
                    it.copy(
                        speedKmh = speed,
                        rpm = rpm,
                        coolantTempC = coolant,
                        fuelRateLph = fuelRate,
                        fuelLevelPct = sample.fuelLevelPct,
                        instantL100 = instantL100,
                        tripDistanceKm = tripDistance,
                        tripFuelL = tripFuel,
                        tripSeconds = tripSeconds,
                        bins = bins.values.sortedBy { bin -> bin.binIndex },
                        totalDistanceKm = bins.values.sumOf { bin -> bin.distanceKm },
                        sampleCount = sampleCount,
                        sampleRateHz = sampleRateHz,
                        batteryVoltage = batteryVoltage,
                        supportedPids = supportedPids,
                        lastRawReply = rawSpeed.take(MAX_RAW_REPLY_CHARS),
                        lastError = diagError,
                    )
                }

                lastSample = sample

                if (ObdConnectionPolicy.shouldReconnect(consecutiveBad)) {
                    Log.w(TAG, "reconnecting after $consecutiveBad consecutive bad speed replies")
                    transport.disconnect()
                    mutableLive.update { it.copy(status = ObdStatus.Connecting, lastError = "RECONNECT") }
                    if (!reconnectWithBackoff(transport)) {
                        if (stopRequested) return
                        mutableLive.update { it.copy(status = ObdStatus.Error, lastError = "RECONNECT FAILED") }
                        return
                    }
                    // A fresh socket must be re-initialized exactly like a first connect;
                    // an adapter that no longer answers ATZ is a real failure, not a retry.
                    if (!initializeAdapter(transport)) {
                        transport.disconnect()
                        mutableLive.update {
                            it.copy(status = ObdStatus.Error, lastError = it.lastError ?: "INIT")
                        }
                        return
                    }
                    settleProtocol(transport)
                    supportedPids = negotiatePids(transport)
                    mutableLive.update { it.copy(status = ObdStatus.Connected, supportedPids = supportedPids) }
                    consecutiveBad = 0
                    rpmNullSinceMs = null
                    lastSample = null
                }

                if (ObdConnectionPolicy.shouldStopForIgnitionOff(rpmNullSinceMs, now, batteryVoltage)) {
                    Log.i(TAG, "ignition off detected — stopping OBD loop")
                    return
                }

                delay(POLL_INTERVAL_MS)
            }
        } finally {
            speedBinDao.upsertAll(bins.values.map { it.toEntity() })
            val end = tripDetector.forceEnd(lastSample?.timestampMs ?: System.currentTimeMillis())
            if (end is TripDetector.TripTransition.Ended) {
                val startedAtMs = tripRecorder.tripStartedAtMs
                val closedTripId = tripRecorder.end(
                    vehicleId = vehicleId,
                    endedAtMs = end.endedAtMs,
                    distanceKm = tripDistance,
                    fuelL = tripFuel,
                    maxSpeedKmh = tripMaxSpeed,
                    idleSeconds = tripIdleSeconds,
                    pricePerLiter = pricePerLiter,
                )
                closedTripId?.let {
                    tripLinker.autoLink(it, startedAtMs, end.endedAtMs)
                }
            }
            transport.disconnect()
            mutableLive.update {
                it.copy(status = if (it.status == ObdStatus.Error) it.status else ObdStatus.Disconnected)
            }
        }
    }

    /** Sends a PID only when negotiation says it is supported (or negotiation failed). */
    private suspend fun pollIf(transport: ObdTransport, pid: Int, supported: Set<Int>): String =
        if (supported.isEmpty() || supported.contains(pid)) {
            transport.sendCommand(ElmProtocol.command(pid))
        } else {
            ""
        }

    /**
     * Retries `connect()` with 2, 4, 8 s backoff, capped at
     * [ObdConnectionPolicy.MAX_RECONNECT_ATTEMPTS] attempts (and the
     * [ObdConnectionPolicy.MAX_RECONNECT_WINDOW_MS] window). Stops early once a stop is
     * requested or the dongle is no longer ACL-connected, so an absent adapter is not
     * hammered. Returns true as soon as a connect succeeds.
     */
    private suspend fun reconnectWithBackoff(transport: ObdTransport): Boolean {
        var waitedMs = 0L
        var attempt = 0
        while (ObdConnectionPolicy.shouldRetryReconnect(attempt, isDeviceConnected(transport)) &&
            waitedMs < ObdConnectionPolicy.MAX_RECONNECT_WINDOW_MS
        ) {
            // A stop requested while `connect()` was blocking must abort the loop instead
            // of exhausting the remaining attempts.
            if (!ObdConnectionPolicy.shouldContinueReconnect(stopRequested)) return false
            val backoffMs = ObdConnectionPolicy.backoffDelayMs(attempt)
            delay(backoffMs)
            waitedMs += backoffMs
            if (!ObdConnectionPolicy.shouldContinueReconnect(stopRequested)) return false
            if (transport.connect().isSuccess) {
                Log.i(TAG, "reconnected on attempt ${attempt + 1} after ${waitedMs}ms")
                return true
            }
            attempt++
        }
        Log.e(TAG, "reconnect gave up after $attempt attempt(s) / ${waitedMs}ms")
        return false
    }

    /**
     * The simulated transport has no ACL concept; only a real Bluetooth transport can report
     * the dongle as gone, in which case retrying is pointless.
     */
    private fun isDeviceConnected(transport: ObdTransport): Boolean =
        (transport as? BluetoothClassicTransport)?.isDeviceAclConnected ?: true

    private fun ObdSample.toEntity(vehicleId: String) = ObdSampleEntity(
        vehicleId = vehicleId,
        timestampMs = timestampMs,
        speedKmh = speedKmh,
        rpm = rpm,
        mafGps = mafGps,
        fuelRateLph = fuelRateLph,
        mapKpa = mapKpa,
        intakeTempC = intakeTempC,
        coolantTempC = coolantTempC,
        engineLoadPct = engineLoadPct,
        fuelLevelPct = fuelLevelPct,
    )

    private fun SpeedBinStats.toEntity() = SpeedBinStatsEntity(
        vehicleId = vehicleId,
        binIndex = binIndex,
        distanceKm = distanceKm,
        fuelL = fuelL,
        seconds = seconds,
        samples = samples,
    )

    companion object {
        const val POLL_INTERVAL_MS = 250L
        const val SAMPLE_PERSIST_INTERVAL_MS = 1_000L
        const val SPEED_BIN_PERSIST_INTERVAL_MS = 30_000L
        const val BATTERY_POLL_INTERVAL_MS = 10_000L
        private const val TAG = "FuelRoute"
        private const val MAX_RAW_REPLY_CHARS = 160
        private const val CONSECUTIVE_ERROR_THRESHOLD = 10
        private const val PROTOCOL_LOCK_TIMEOUT_MS = 3_000L
        private const val PROTOCOL_LOCK_RETRY_MS = 250L
    }
}
