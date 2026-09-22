package com.fuelroute.data.obd

import android.util.Log
import com.fuelroute.data.db.ObdSampleDao
import com.fuelroute.data.db.ObdSampleEntity
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.data.db.SpeedBinStatsEntity
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.TripSource
import com.fuelroute.data.history.TripLinker
import com.fuelroute.data.learning.ColdStartRepository
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.domain.fuel.DefaultCurve
import com.fuelroute.domain.learning.ColdStartLearner
import com.fuelroute.domain.learning.FuelRateCalculator
import com.fuelroute.domain.learning.SpeedBinAggregator
import com.fuelroute.domain.learning.TripDetector
import com.fuelroute.domain.model.ObdSample
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.VehicleProfile
import com.fuelroute.domain.obd.ElmProtocol
import com.fuelroute.domain.obd.ObdConnectionPolicy
import com.fuelroute.domain.obd.ObdRunGeneration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

enum class ObdStatus { Disconnected, Connecting, Connected, Error }

/**
 * Ordered stages of the connect pipeline, surfaced through [LiveObdState.connectionStage] while
 * [ObdStatus.Connecting] is active so the UI can show real progress instead of a bare spinner.
 */
enum class ObdConnectStage { ConnectingSocket, InitializingElm, SettlingProtocol, NegotiatingPids, ReadingVin }

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
    /** Current connect pipeline stage, or `null` when not connecting. */
    val connectionStage: ObdConnectStage? = null,
    /** Wall-clock start (epoch ms) of the current connect attempt, for the elapsed counter. */
    val connectingSinceMs: Long? = null,
)

@Singleton
class ObdEngine @Inject constructor(
    private val sampleDao: ObdSampleDao,
    private val speedBinDao: SpeedBinDao,
    private val tripDao: TripDao,
    private val fuelPriceRepository: FuelPriceRepository,
    private val tripLinker: TripLinker,
    private val coldStartRepository: ColdStartRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableLive = MutableStateFlow(LiveObdState())
    val live: StateFlow<LiveObdState> = mutableLive.asStateFlow()

    private var job: Job? = null

    /**
     * Monotonic start generation. A newer [start] invalidates every earlier run so a stale
     * coroutine's `finally` can detect it no longer owns the engine and skip its state updates.
     */
    private val runGeneration = ObdRunGeneration()

    /**
     * Set by [stop]/[disconnect]/[reset] to interrupt a reconnect backoff loop and the run
     * loop promptly, even while a blocking `connect()` is in flight. Volatile because a
     * stop may be requested from the UI thread while the engine loop runs on `Default`.
     */
    @Volatile
    private var stopRequested = false

    val isRunning: Boolean
        get() = job?.isActive == true

    /**
     * Starts (or restarts) the engine for [transport]/[vehicle].
     *
     * Any prior loop is cancelled and *joined* before the new one launches, so its
     * [NonCancellable] cleanup (persist bins, close the open trip, flush buffered samples,
     * close the socket) is guaranteed to have finished before the new loop can touch shared
     * state or the same transport. The generation token further ensures a stale loop's
     * `finally` cannot publish its `Disconnected` state over the new one.
     */
    suspend fun start(transport: ObdTransport, vehicle: VehicleProfile) {
        val runId = runGeneration.next()
        // Cancel + join the previous loop. Keep stopRequested set while it unwinds so it cannot
        // squeeze in another poll, then clear it for the new run.
        stopRequested = true
        job?.cancelAndJoin()
        job = null
        stopRequested = false

        val vehicleId = vehicle.id
        job = scope.launch {
            // Every fresh attempt starts from a clean slate: a stale error/VIN from the
            // previous run must not leak into the new status line. The first connect stage and
            // its start timestamp are set here so the UI can show live progress immediately.
            publish(runId) {
                it.copy(
                    status = ObdStatus.Connecting,
                    lastError = null,
                    deviceName = null,
                    vin = null,
                    supportedPids = emptySet(),
                    connectionStage = ObdConnectStage.ConnectingSocket,
                    connectingSinceMs = System.currentTimeMillis(),
                )
            }
            val connected = transport.connect()
            if (stopRequested || !runGeneration.isCurrent(runId)) return@launch
            if (connected.isFailure) {
                // Terminal: the run loop is never entered, so the logging service can stop
                // instead of lingering on the foreground notification.
                val cause = connected.exceptionOrNull()
                val reason = (cause as? ObdConnectException)?.reason
                    ?: if (cause is SocketTimeoutException) ObdConnectionPolicy.ERROR_CONNECT_TIMEOUT
                    else ObdConnectionPolicy.ERROR_CONNECT
                Log.w(TAG, "OBD connect failed ($reason)", cause)
                publish(runId) {
                    it.copy(
                        status = ObdStatus.Error,
                        lastError = reason,
                        connectionStage = null,
                        connectingSinceMs = null,
                    )
                }
                return@launch
            }

            if (!initializeAdapter(transport, runId)) {
                runCatching { transport.disconnect() }
                // Preserve the detailed `bad ATZ: …` reason set by initializeAdapter so the
                // UI can tell the user the dongle did not answer like an ELM327.
                publish(runId) {
                    it.copy(
                        status = ObdStatus.Error,
                        lastError = it.lastError ?: "INIT",
                        connectionStage = null,
                        connectingSinceMs = null,
                    )
                }
                return@launch
            }

            // Status stays `Connecting` through settle/negotiate/VIN so every pipeline stage is
            // visible; `runLoop` flips to `Connected` (and clears the stage) once it finishes.
            settleProtocol(transport, runId)
            runLoop(transport, vehicle, vehicleId, runId)
        }
    }

    /**
     * Requests a stop and waits for the run loop's cleanup to finish, so callers know the
     * socket is closed and the open trip / buffered samples are flushed. The wait is bounded by
     * that cleanup work and does not affect a newer run that an interleaved [start] may own.
     */
    fun stop() {
        val generation = runGeneration.current()
        stopRequested = true
        val current = job
        if (current != null) {
            runBlocking { current.cancelAndJoin() }
        }
        if (job === current) job = null
        // Only publish the disconnected status if no newer start has superseded this stop.
        if (runGeneration.isCurrent(generation)) {
            mutableLive.update {
                it.copy(
                    status = ObdStatus.Disconnected,
                    connectionStage = null,
                    connectingSinceMs = null,
                )
            }
        }
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

    /** Publishes a state transform only while [runId] still owns the engine. */
    private inline fun publish(runId: Long, transform: (LiveObdState) -> LiveObdState) {
        if (runGeneration.isCurrent(runId)) mutableLive.update(transform)
    }

    /** Sends the ELM init sequence and validates the `ATZ` banner. */
    private suspend fun initializeAdapter(transport: ObdTransport, runId: Long): Boolean {
        publish(runId) { it.copy(connectionStage = ObdConnectStage.InitializingElm) }
        val replies = mutableMapOf<String, String>()
        for (command in ElmProtocol.initializationCommands) {
            val reply = sendInitCommand(transport, command)
            if (reply.isNullOrBlank()) {
                // A silent/dead dongle never answers: fail fast with a specific reason instead of
                // waiting out the full socket read-timeout on every remaining init command.
                Log.e(
                    TAG,
                    "ELM init $command got no reply within " +
                        "${ObdConnectionPolicy.initReadTimeoutMs(command)}ms",
                )
                publish(runId) { it.copy(lastError = ObdConnectionPolicy.ERROR_INIT_TIMEOUT) }
                return false
            }
            replies[command] = reply
            Log.d(TAG, "ELM init $command -> ${reply.trim()}")
        }

        val atz = replies["ATZ"].orEmpty()
        if (!ElmProtocol.isAcceptedAdapterBanner(atz)) {
            Log.e(TAG, "ATZ did not return an acceptable banner: \"$atz\"")
            publish(runId) { it.copy(lastError = "bad ATZ: ${atz.trim()}") }
            return false
        }
        return true
    }

    /**
     * Sends one ELM init command with a per-command timeout ([ObdConnectionPolicy.initReadTimeoutMs]):
     * a longer window for `ATZ`, the short one for the rest, returning `null` when the adapter
     * never answered.
     *
     * A blocking socket `read()` cannot be interrupted by `withTimeoutOrNull`, so on timeout the
     * abandoned read is unblocked by closing the socket. This keeps the orphaned read from
     * surviving into the next command and desyncing the request/response stream; the init is
     * abandoned and the transport reconnects from a fresh socket.
     */
    private suspend fun sendInitCommand(transport: ObdTransport, command: String): String? {
        val reply = withTimeoutOrNull(ObdConnectionPolicy.initReadTimeoutMs(command)) {
            transport.sendCommand(command)
        }
        if (reply == null) {
            runCatching { transport.disconnect() }
        }
        return reply
    }

    /**
     * After `ATSP0` some adapters keep answering `SEARCHING...` while they auto-detect the
     * bus protocol. Give them up to [PROTOCOL_LOCK_TIMEOUT_MS] to settle (first real data
     * PID that is no longer SEARCHING wins). If it never settles we keep going — the run
     * loop surfaces SEARCHING via `lastError` instead of crashing.
     */
    private suspend fun settleProtocol(transport: ObdTransport, runId: Long) {
        publish(runId) { it.copy(connectionStage = ObdConnectStage.SettlingProtocol) }
        val deadline = System.currentTimeMillis() + PROTOCOL_LOCK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val raw = transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_SPEED))
            if (!raw.contains("SEARCHING", ignoreCase = true)) return
            delay(PROTOCOL_LOCK_RETRY_MS)
        }
        Log.w(TAG, "protocol still SEARCHING after ${PROTOCOL_LOCK_TIMEOUT_MS}ms")
    }

    /** Probes `0100`/`0120`/`0140`/`0160` and merges the bitmaps. */
    private suspend fun negotiatePids(transport: ObdTransport, runId: Long): ElmProtocol.PidSupport {
        publish(runId) { it.copy(connectionStage = ObdConnectStage.NegotiatingPids) }
        val replies = mutableMapOf<Int, String>()
        for (base in ElmProtocol.supportedPidBlocks) {
            replies[base] = transport.sendCommand(ElmProtocol.command(base))
        }
        val support = ElmProtocol.negotiateSupport(replies)
        Log.d(
            TAG,
            "supported PIDs (failed=${support.negotiationFailed}): " +
                support.pids.sorted().joinToString { "%02X".format(it) },
        )
        return support
    }

    /** Reads the VIN once (Mode 09 PID 02). */
    private suspend fun readVin(transport: ObdTransport, runId: Long): String? {
        publish(runId) { it.copy(connectionStage = ObdConnectStage.ReadingVin) }
        return ElmProtocol.vin(transport.sendCommand(ElmProtocol.command09(ElmProtocol.PID_VIN)))
    }

    private suspend fun runLoop(
        transport: ObdTransport,
        vehicle: VehicleProfile,
        vehicleId: String,
        runId: Long,
    ) {
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
        val coldStartLearner = ColdStartLearner(
            warmCurve = DefaultCurve.forVehicle(vehicle.ratedCombinedL100, vehicle.fuelType),
        )
        val tripRecorder = TripRecorder(tripDao)
        tripRecorder.closeLeftovers(System.currentTimeMillis())
        // Provenance for every trip this run records: the demo transport must never look real.
        val source = if (transport.isSimulated) TripSource.DEMO else TripSource.REAL

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
        var pidNegotiationFailed = false
        var supportedPids: Set<Int> = emptySet()
        // Samples buffered since the last flush; the run loop inserts them in one batch instead
        // of one row per ~250 ms poll.
        val sampleBuffer = mutableListOf<ObdSampleEntity>()
        val loopStartMs = System.currentTimeMillis()

        try {
            val negotiation = negotiatePids(transport, runId)
            supportedPids = negotiation.pids
            pidNegotiationFailed = negotiation.negotiationFailed
            val vin = readVin(transport, runId)
            // The whole connect pipeline (init -> settle -> negotiate -> VIN) is done: flip to
            // Connected and clear the progress stage in the same update.
            publish(runId) {
                it.copy(
                    status = ObdStatus.Connected,
                    deviceName = transport.deviceName,
                    supportedPids = supportedPids,
                    vin = vin,
                    connectionStage = null,
                    connectingSinceMs = null,
                )
            }

            while (true) {
                // A manual stop between polls must unwind immediately instead of waiting for
                // the next cancellation point.
                if (stopRequested || !runGeneration.isCurrent(runId)) return
                val now = System.currentTimeMillis()

                val rawSpeed = transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_SPEED))
                val rawRpm = transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_RPM))
                // Coolant is always polled: the cold-engine exclusion in the aggregator
                // depends on it and it is not part of the optional negotiation set.
                val rawCoolant = transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_COOLANT_TEMP))
                val rawMaf = pollIf(transport, ElmProtocol.PID_MAF, supportedPids, pidNegotiationFailed)
                val rawFuelRate = pollIf(transport, ElmProtocol.PID_FUEL_RATE, supportedPids, pidNegotiationFailed)
                val rawMap = pollIf(transport, ElmProtocol.PID_MAP, supportedPids, pidNegotiationFailed)
                val rawIat = pollIf(transport, ElmProtocol.PID_INTAKE_TEMP, supportedPids, pidNegotiationFailed)
                val rawLoad = pollIf(transport, ElmProtocol.PID_ENGINE_LOAD, supportedPids, pidNegotiationFailed)
                val rawFuelLevel = pollIf(transport, ElmProtocol.PID_FUEL_LEVEL, supportedPids, pidNegotiationFailed)

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
                coldStartLearner.onSample(
                    speedKmh = sample.speedKmh,
                    fuelRateLph = fuelRate,
                    dtSec = dtSec,
                    coolantTempC = sample.coolantTempC,
                )

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
                        tripRecorder.start(
                            vehicleId,
                            tripDetector.startedAtMs ?: sample.timestampMs,
                            source,
                        )
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
                            if (source == TripSource.REAL) {
                                tripLinker.autoLink(it, startedAtMs, transition.endedAtMs)
                            }
                        }
                        tripDistance = 0.0
                        tripFuel = 0.0
                        tripSeconds = 0.0
                        tripIdleSeconds = 0.0
                        tripMaxSpeed = 0.0
                        if (coldStartLearner.hasColdSamples) {
                            coldStartRepository.record(vehicleId, coldStartLearner.endTrip())
                        } else {
                            coldStartLearner.endTrip()
                        }
                    }
                    else -> Unit
                }

                sampleCount++
                sampleBuffer += sample.toEntity(vehicleId)
                if (now - lastSamplePersistMs >= SAMPLE_PERSIST_INTERVAL_MS) {
                    if (sampleBuffer.isNotEmpty()) {
                        sampleDao.insertAll(sampleBuffer.toList())
                        sampleBuffer.clear()
                    }
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

                // RPM absence is only ignition-off evidence when PID 0C is supported (or
                // negotiation failed and we fall back to the mandatory trio) or the car is
                // stationary; otherwise a clone that never answers 0C would stop the loop mid-drive.
                val rpmPidSupported = supportedPids.isEmpty() ||
                    supportedPids.contains(ElmProtocol.PID_RPM)
                if (rpm == null && ObdConnectionPolicy.shouldTrackRpmAbsence(rpmPidSupported, speed)) {
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

                publish(runId) {
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
                    publish(runId) {
                        it.copy(
                            status = ObdStatus.Connecting,
                            lastError = "RECONNECT",
                            connectionStage = ObdConnectStage.ConnectingSocket,
                            connectingSinceMs = System.currentTimeMillis(),
                        )
                    }
                    if (!reconnectWithBackoff(transport)) {
                        if (stopRequested || !runGeneration.isCurrent(runId)) return
                        publish(runId) {
                            it.copy(
                                status = ObdStatus.Error,
                                lastError = "RECONNECT FAILED",
                                connectionStage = null,
                                connectingSinceMs = null,
                            )
                        }
                        return
                    }
                    // A fresh socket must be re-initialized exactly like a first connect;
                    // an adapter that no longer answers ATZ is a real failure, not a retry.
                    if (!initializeAdapter(transport, runId)) {
                        runCatching { transport.disconnect() }
                        publish(runId) {
                            it.copy(
                                status = ObdStatus.Error,
                                lastError = it.lastError ?: "INIT",
                                connectionStage = null,
                                connectingSinceMs = null,
                            )
                        }
                        return
                    }
                    settleProtocol(transport, runId)
                    // Re-negotiate once on reconnect so a stale bitmap (or a failed initial
                    // negotiation) is refreshed against the live socket.
                    val renegotiation = negotiatePids(transport, runId)
                    supportedPids = renegotiation.pids
                    pidNegotiationFailed = renegotiation.negotiationFailed
                    publish(runId) {
                        it.copy(
                            status = ObdStatus.Connected,
                            supportedPids = supportedPids,
                            connectionStage = null,
                            connectingSinceMs = null,
                        )
                    }
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
            // The cleanup must survive cancellation: without NonCancellable the first suspend
            // call would throw CancellationException and skip the rest, leaking the socket,
            // leaving the trip open and dropping the buffered samples/bins. Each step is also
            // shielded so one failure cannot abort the remaining cleanup.
            withContext(NonCancellable) {
                if (sampleBuffer.isNotEmpty()) {
                    runCatching { sampleDao.insertAll(sampleBuffer.toList()) }
                        .onFailure { Log.w(TAG, "flush samples on stop failed", it) }
                }
                runCatching { speedBinDao.upsertAll(bins.values.map { it.toEntity() }) }
                    .onFailure { Log.w(TAG, "persist bins on stop failed", it) }
                runCatching {
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
                            if (source == TripSource.REAL) {
                                tripLinker.autoLink(it, startedAtMs, end.endedAtMs)
                            }
                        }
                    }
                }.onFailure { Log.w(TAG, "close trip on stop failed", it) }
                runCatching {
                    if (coldStartLearner.hasColdSamples) {
                        coldStartRepository.record(vehicleId, coldStartLearner.endTrip())
                    } else {
                        coldStartLearner.endTrip()
                    }
                }.onFailure { Log.w(TAG, "record cold start on stop failed", it) }
                runCatching { transport.disconnect() }
                    .onFailure { Log.w(TAG, "disconnect on stop failed", it) }
                publish(runId) {
                    it.copy(
                        status = if (it.status == ObdStatus.Error) it.status else ObdStatus.Disconnected,
                        connectionStage = null,
                        connectingSinceMs = null,
                    )
                }
            }
        }
    }

    /**
     * Sends an optional PID. When negotiation failed we fall back to the mandatory trio
     * (speed/RPM/coolant, always polled) only, instead of blasting 8 possibly-unsupported PIDs
     * that each wait the full socket timeout; when negotiation succeeded, poll only what the
     * bitmap advertises.
     */
    private suspend fun pollIf(
        transport: ObdTransport,
        pid: Int,
        supported: Set<Int>,
        negotiationFailed: Boolean,
    ): String =
        if (!negotiationFailed && supported.contains(pid)) {
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