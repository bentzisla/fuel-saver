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
import com.fuelroute.domain.fuel.ConsumptionCurve
import com.fuelroute.domain.fuel.DefaultCurve
import com.fuelroute.domain.learning.ColdStartLearner
import com.fuelroute.domain.learning.LearnedCurve
import com.fuelroute.domain.learning.ObdSampleProcessor
import com.fuelroute.domain.learning.TripDetector
import com.fuelroute.domain.model.ObdSample
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.VehicleProfile
import com.fuelroute.domain.model.mergeSpeedBins
import com.fuelroute.domain.obd.ElmProtocol
import com.fuelroute.domain.obd.EngineOffTracker
import com.fuelroute.domain.obd.FuelRateHold
import com.fuelroute.domain.obd.ObdConnectionPolicy
import com.fuelroute.domain.obd.ObdRunGeneration
import com.fuelroute.domain.obd.PidScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * Why a run ended by itself, published with the terminal [ObdStatus.Disconnected]. A run ended
 * for a stop reason is final for the logging service: it must not auto-reconnect.
 */
enum class ObdStopReason {
    /** No running engine (RPM missing/0 while stationary, or low battery voltage) for long enough. */
    IGNITION_OFF,
}

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
    /** Set on the terminal state of a run that ended by itself (e.g. ignition off); else null. */
    val stopReason: ObdStopReason? = null,
    /** Polls in the current run that returned a valid speed or RPM (real engine data). */
    val validSampleCount: Int = 0,
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

    /**
     * Last line of defence: an exception escaping a run (a DB error nobody guarded, a parser bug)
     * must surface as [ObdStatus.Error] instead of killing the process mid-drive. Only published
     * when no newer run owns the engine.
     */
    private val crashHandler = CoroutineExceptionHandler { context, error ->
        Log.e(TAG, "OBD engine coroutine crashed", error)
        val failed = context[Job]
        val current = job
        if (current == null || current === failed || !current.isActive) {
            mutableLive.update {
                it.copy(
                    status = ObdStatus.Error,
                    lastError = ObdConnectionPolicy.ERROR_ENGINE_CRASH,
                    connectionStage = null,
                    connectingSinceMs = null,
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashHandler)
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

    /**
     * Serializes [start] and [stop]: at most one run exists at a time, and a start can never
     * interleave with another start's teardown (which used to allow two loops, and two RFCOMM
     * connects, against a single-link dongle).
     */
    private val lifecycleMutex = Mutex()

    /** Transport of the latest [start], force-closed by [haltCurrentRun] when a run is stuck. */
    @Volatile
    private var activeTransport: ObdTransport? = null

    /** Run id currently using [activeTransport]; see [closeTransport]. */
    @Volatile
    private var transportOwnerRun = 0L

    val isRunning: Boolean
        get() = job?.isActive == true

    /**
     * Starts (or restarts) the engine for [transport]/[vehicle].
     *
     * Starts and stops are serialized by [lifecycleMutex]. Any prior run is halted (see
     * [haltCurrentRun]: bounded, never blocks a thread) before the new one launches, so its
     * [NonCancellable] cleanup (persist bins, close the open trip, flush buffered samples,
     * `ATPC` + close the socket) has normally finished before the new loop can touch shared
     * state or the same transport. The generation token further ensures a stale loop's
     * `finally` cannot publish its `Disconnected` state over the new one.
     */
    suspend fun start(transport: ObdTransport, vehicle: VehicleProfile) {
        val runId = runGeneration.next()
        lifecycleMutex.withLock {
            // Two starts racing (service re-arm vs. a fresh user start, ACL auto-start, …) are
            // serialized here; the older one simply yields so only ONE run ever talks to the
            // dongle — cheap clones accept a single RFCOMM link.
            if (!runGeneration.isCurrent(runId)) {
                Log.i(TAG, "OBD start #$runId superseded by a newer start before it began")
                return
            }
            haltCurrentRun("superseded by start #$runId")
            stopRequested = false
            activeTransport = transport

            val vehicleId = vehicle.id
            job = scope.launch {
                transportOwnerRun = runId
                try {
                    // Every fresh attempt starts from a clean slate: a stale error/VIN from the
                    // previous run must not leak into the new status line. The first connect
                    // stage and its start timestamp are set here so the UI can show live
                    // progress immediately.
                    publish(runId) {
                        it.copy(
                            status = ObdStatus.Connecting,
                            lastError = null,
                            deviceName = null,
                            vin = null,
                            supportedPids = emptySet(),
                            connectionStage = ObdConnectStage.ConnectingSocket,
                            connectingSinceMs = System.currentTimeMillis(),
                            stopReason = null,
                            validSampleCount = 0,
                        )
                    }
                    Log.i(TAG, "OBD run #$runId: connecting to ${transport.deviceName}")
                    if (!openSession(transport, runId)) return@launch

                    // Status stays `Connecting` through settle/negotiate/VIN so every pipeline
                    // stage is visible; `runLoop` flips to `Connected` once it finishes.
                    settleProtocol(transport, runId)
                    runLoop(transport, vehicle, vehicleId, runId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "OBD run #$runId failed unexpectedly", e)
                    publishError(runId, ObdConnectionPolicy.ERROR_ENGINE_CRASH)
                } finally {
                    // Covers every exit that bypasses runLoop's own cleanup (stop during
                    // connect/init/settle, init failure): the socket must never be left open,
                    // or a single-link clone refuses the next session until power-cycled.
                    withContext(NonCancellable) { closeTransport(transport, runId, "run #$runId ended") }
                }
            }
        }
    }

    /**
     * Stops the current run and waits (suspending, never blocking a thread) until its cleanup
     * finished: samples/bins/trip flushed, `ATPC` sent, socket closed. The wait is bounded — a
     * run stuck on a silent adapter is unblocked by force-closing the transport and, as a last
     * resort, abandoned — so this can never hang the caller. Does not affect a newer run that
     * an interleaved [start] may own.
     */
    suspend fun stop() {
        stopFor(runGeneration.current())
    }

    /**
     * Fire-and-forget [stop] for callers that must not suspend (e.g. `Service.onDestroy` on the
     * main thread). Runs on the engine's own application-lifetime scope, so it outlives the
     * caller. A [start] issued after this call is never stopped by it.
     */
    fun requestStop(): Job {
        val generation = runGeneration.current()
        return scope.launch { stopFor(generation) }
    }

    private suspend fun stopFor(generation: Long) = withContext(NonCancellable) {
        lifecycleMutex.withLock {
            if (!runGeneration.isCurrent(generation)) {
                Log.i(TAG, "OBD stop skipped: a newer start superseded it")
                return@withLock
            }
            haltCurrentRun("stop requested")
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
    }

    /**
     * Stops the run loop and clears the visible failure/device identity so the status line
     * reads a clean "disconnected" instead of a stale error. Learned totals already shown
     * on the dashboard are kept.
     */
    suspend fun disconnect() {
        stop()
        mutableLive.update { it.copy(lastError = null, deviceName = null, vin = null) }
    }

    /**
     * Full reset: stops the run loop and wipes [LiveObdState] (error, VIN, PIDs, device)
     * so a subsequent [start] begins from a clean slate and can never be rejected by
     * leftover state from a previous attempt.
     */
    suspend fun reset() {
        stop()
        mutableLive.value = LiveObdState()
    }

    /**
     * Non-suspending clean slate for the UI before a fresh connect: wipes [LiveObdState] only
     * when no run is active (there is nothing to stop then). Never touches a live run.
     */
    fun clearIfIdle() {
        if (!isRunning) mutableLive.value = LiveObdState()
    }

    /**
     * Ends the current run, bounded in time (caller holds [lifecycleMutex]):
     *  1. set [stopRequested] and give the loop [ObdConnectionPolicy.STOP_GRACE_MS] to exit on
     *     its own between polls (its cleanup then sends `ATPC` over a healthy link);
     *  2. cancel it — an in-flight [ElmLink] exchange closes its socket on cancellation;
     *  3. if it is STILL stuck (a transport whose read ignores cancellation), force-close the
     *     transport, which unblocks any read;
     *  4. give up waiting and leave it to finish in the background. The generation token and
     *     [transportOwnerRun] keep that stale run from touching the new run's state/socket.
     */
    private suspend fun haltCurrentRun(reason: String) = withContext(NonCancellable) {
        stopRequested = true
        val current = job ?: return@withContext
        if (current.isActive) {
            Log.i(TAG, "stopping OBD run ($reason)")
            if (withTimeoutOrNull(ObdConnectionPolicy.STOP_GRACE_MS) { current.join() } == null) {
                current.cancel()
                if (withTimeoutOrNull(ObdConnectionPolicy.STOP_CANCEL_JOIN_MS) { current.join() } == null) {
                    Log.w(TAG, "OBD run did not finish after cancel — force-closing the transport")
                    runCatching { activeTransport?.disconnect() }
                    if (withTimeoutOrNull(ObdConnectionPolicy.STOP_FORCE_JOIN_MS) { current.join() } == null) {
                        Log.e(TAG, "OBD run still stuck after force-close — abandoning it")
                    }
                }
            }
        }
        if (job === current) job = null
    }

    /**
     * Best-effort clean close: `ATPC` (so the adapter is idle, not mid-search, for the next
     * session) and then the socket. Skipped when a newer run has taken over this same
     * transport object, so a late, abandoned cleanup can never close the new run's socket.
     * Must be called from a [NonCancellable] context.
     */
    private suspend fun closeTransport(transport: ObdTransport, runId: Long, reason: String) {
        if (activeTransport === transport && transportOwnerRun != runId) {
            Log.i(TAG, "not closing transport for stale run #$runId ($reason): run #$transportOwnerRun owns it")
            return
        }
        if (transport.isConnected) {
            runCatching {
                transport.sendCommand(ElmProtocol.CMD_PROTOCOL_CLOSE, ObdConnectionPolicy.CLOSE_PROTOCOL_TIMEOUT_MS)
            }.onFailure { Log.w(TAG, "ATPC on close failed", it) }
        }
        runCatching { transport.disconnect() }
            .onFailure { Log.w(TAG, "disconnect ($reason) failed", it) }
        Log.i(TAG, "OBD transport closed ($reason)")
    }

    /** Publishes a state transform only while [runId] still owns the engine. */
    private inline fun publish(runId: Long, transform: (LiveObdState) -> LiveObdState) {
        if (runGeneration.isCurrent(runId)) mutableLive.update(transform)
    }

    private fun isStale(runId: Long): Boolean = stopRequested || !runGeneration.isCurrent(runId)

    /**
     * Connects and initializes the adapter. Publishes the terminal `Error` itself and returns
     * false on failure (or when a stop arrived meanwhile).
     */
    private suspend fun openSession(transport: ObdTransport, runId: Long): Boolean {
        val connected = transport.connect()
        if (isStale(runId)) return false
        if (connected.isFailure) {
            // Terminal: the run loop is never entered, so the logging service can stop
            // instead of lingering on the foreground notification.
            publishError(runId, connectFailureReason(connected.exceptionOrNull()))
            return false
        }
        val initError = initializeWithReopen(transport, runId) ?: return true
        if (!isStale(runId)) publishError(runId, initError)
        return false
    }

    private fun connectFailureReason(cause: Throwable?): String {
        val reason = (cause as? ObdConnectException)?.reason
            ?: if (cause is SocketTimeoutException) ObdConnectionPolicy.ERROR_CONNECT_TIMEOUT
            else ObdConnectionPolicy.ERROR_CONNECT
        Log.w(TAG, "OBD connect failed ($reason)", cause)
        return reason
    }

    private fun publishError(runId: Long, reason: String) {
        publish(runId) {
            it.copy(
                status = ObdStatus.Error,
                lastError = reason,
                connectionStage = null,
                connectingSinceMs = null,
            )
        }
    }

    /**
     * [initializeAdapter]; when it fails, fully close the socket, wait for the dongle to
     * release the RFCOMM channel and reconnect + re-init ONCE before giving up — the software
     * equivalent of the manual unplug/replug that used to be the only fix. Returns the error
     * code of the last failure, or null on success.
     */
    private suspend fun initializeWithReopen(transport: ObdTransport, runId: Long): String? {
        var attempt = 1
        while (true) {
            val error = initializeAdapter(transport, runId) ?: return null
            if (isStale(runId) || !ObdConnectionPolicy.shouldRetrySessionOpen(attempt)) {
                Log.e(TAG, "ELM init failed ($error) — giving up after $attempt attempt(s)")
                return error
            }
            Log.w(TAG, "ELM init failed ($error) — closing the socket and reconnecting once")
            runCatching { transport.disconnect() }
            delay(ObdConnectionPolicy.RFCOMM_RELEASE_MS)
            if (isStale(runId)) return error
            publish(runId) { it.copy(connectionStage = ObdConnectStage.ConnectingSocket) }
            val reconnected = transport.connect()
            if (isStale(runId)) return error
            if (reconnected.isFailure) return connectFailureReason(reconnected.exceptionOrNull())
            attempt++
        }
    }

    /**
     * ELM init, tolerant of the cheap clones:
     *  1. bare CR (soft) to terminate any half-received command / abort a search the previous
     *     session left running, then a short quiet time so stale bytes can be drained;
     *  2. reset: `ATZ`, `ATZ` again, then `ATWS` (all soft, so a clone that swallows its reset
     *     reply keeps the socket), accepting banners with garbage around them; `ATD` answering
     *     `OK` is the last-resort proof of life;
     *  3. configuration commands (hard deadline).
     *
     * Returns null on success, else a machine error code: `bad ATZ: …` for a garbage reply, or
     * an `INIT …` code that says whether the link timed out, failed to write, hit EOF, etc.
     */
    private suspend fun initializeAdapter(transport: ObdTransport, runId: Long): String? {
        publish(runId) { it.copy(connectionStage = ObdConnectStage.InitializingElm) }

        // `sendSoft` keeps the `>` when the prompt arrived: "" means total silence.
        val cleared = transport.sendSoft("", ObdConnectionPolicy.LINE_CLEAR_TIMEOUT_MS)
        Log.i(TAG, "ELM init: line clear (bare CR) -> ${ElmLink.printable(cleared)}")
        if (!transport.isConnected) return linkLost(transport, "line clear")
        var adapterSpoke = cleared.isNotEmpty()
        delay(ObdConnectionPolicy.LINE_CLEAR_SETTLE_MS)

        var banner: String? = null
        var lastResetReply = ""
        for ((index, command) in ElmProtocol.resetSequence.withIndex()) {
            if (isStale(runId)) return ObdConnectionPolicy.ERROR_INIT_LINK_CLOSED
            val raw = transport.sendSoft(command, ObdConnectionPolicy.initReadTimeoutMs(command))
            if (!transport.isConnected) return linkLost(transport, command)
            if (raw.isNotEmpty()) adapterSpoke = true
            val reply = raw.removeSuffix(">")
            lastResetReply = reply
            if (ElmProtocol.isAcceptedAdapterBanner(reply)) {
                banner = reply
                break
            }
            if (!adapterSpoke) {
                // Not a single byte for the CR nor the reset: the adapter is dead/hung on this
                // link. Walking the remaining soft resets would only add seconds; the caller
                // closes the socket and reopens once instead.
                Log.e(TAG, "ELM init: adapter silent to CR and $command")
                return ObdConnectionPolicy.ERROR_INIT_TIMEOUT
            }
            Log.w(
                TAG,
                "ELM init: $command (try ${index + 1}/${ElmProtocol.resetSequence.size}) gave no " +
                    "usable banner: ${ElmLink.printable(reply)}",
            )
            delay(ObdConnectionPolicy.RESET_RETRY_PAUSE_MS)
            transport.sendSoft("", ObdConnectionPolicy.LINE_CLEAR_TIMEOUT_MS)
            if (!transport.isConnected) return linkLost(transport, "line clear")
        }

        if (banner != null) {
            Log.i(TAG, "ELM init: adapter banner ${ElmLink.printable(banner.trim())}")
        } else {
            val defaults = transport.sendSoft(
                ElmProtocol.CMD_SET_DEFAULTS,
                ObdConnectionPolicy.INIT_READ_TIMEOUT_MS,
            ).removeSuffix(">")
            if (!transport.isConnected) return linkLost(transport, ElmProtocol.CMD_SET_DEFAULTS)
            if (defaults.contains("OK", ignoreCase = true)) {
                Log.w(TAG, "ELM init: no reset banner, but ATD answered OK — continuing")
            } else if (lastResetReply.isBlank() && defaults.isBlank()) {
                Log.e(TAG, "ELM init: adapter never answered a reset (ATZ/ATWS/ATD)")
                return ObdConnectionPolicy.ERROR_INIT_TIMEOUT
            } else {
                Log.e(TAG, "ELM init: no acceptable banner: ${ElmLink.printable(lastResetReply)}")
                return "bad ATZ: ${lastResetReply.trim()}"
            }
        }

        for (command in ElmProtocol.configurationCommands(ObdProtocolMemory.get(transport.address))) {
            if (isStale(runId)) return ObdConnectionPolicy.ERROR_INIT_LINK_CLOSED
            val reply = transport.sendCommand(command, ObdConnectionPolicy.initReadTimeoutMs(command))
            if (reply.isBlank()) return linkLost(transport, command)
            if (!reply.contains("OK", ignoreCase = true)) {
                // `?` from a clone that lacks e.g. ATAT1 is not fatal.
                Log.w(TAG, "ELM init: $command answered ${ElmLink.printable(reply)} (not OK) — continuing")
            } else {
                Log.d(TAG, "ELM init: $command -> OK")
            }
        }
        return null
    }

    /** Maps "no reply" during init to a specific error code and logs the cause. */
    private fun linkLost(transport: ObdTransport, step: String): String {
        val code = ObdConnectionPolicy.initErrorCode(transport.lastFailure)
        Log.e(TAG, "ELM init: no reply to $step (${transport.lastFailure ?: "unknown cause"}) -> $code")
        return code
    }

    /**
     * After `ATSP0` the first data request triggers the automatic protocol search, printing
     * `SEARCHING...` and then (in the same reply) the data. Sends `0100` with the long
     * [ObdConnectionPolicy.PROTOCOL_SEARCH_TIMEOUT_MS] deadline; a failed search
     * (`UNABLE TO CONNECT`, `BUS INIT: ...ERROR`, …) gets `ATPC` + retry instead of silently
     * continuing. If it never locks we keep going — the run loop surfaces the state via
     * `lastError`.
     */
    private suspend fun settleProtocol(transport: ObdTransport, runId: Long) {
        publish(runId) { it.copy(connectionStage = ObdConnectStage.SettlingProtocol) }
        val fixedProtocol = ObdProtocolMemory.get(transport.address) != null // ATSP<n> sent in init
        for (attempt in 1..ObdConnectionPolicy.PROTOCOL_SETTLE_ATTEMPTS) {
            if (isStale(runId)) return
            val raw = transport.sendCommand(
                ElmProtocol.command(ElmProtocol.PID_SUPPORTED_01_20),
                ObdConnectionPolicy.PROTOCOL_SEARCH_TIMEOUT_MS,
            )
            val outcome = ElmProtocol.classifySearchReply(raw)
            Log.i(TAG, "protocol search $attempt: $outcome (${ElmLink.printable(raw)})")
            when (outcome) {
                ElmProtocol.SearchOutcome.LOCKED -> {
                    val dpn = transport.sendCommand(
                        ElmProtocol.CMD_DESCRIBE_PROTOCOL_NUMBER,
                        ObdConnectionPolicy.INIT_READ_TIMEOUT_MS,
                    )
                    Log.i(TAG, "protocol locked: ATDPN=${ElmLink.printable(dpn.trim())}")
                    ObdProtocolMemory.remember(transport.address, ElmProtocol.parseProtocolNumber(dpn))
                    return
                }
                ElmProtocol.SearchOutcome.NO_DATA,
                ElmProtocol.SearchOutcome.NO_REPLY,
                -> return
                ElmProtocol.SearchOutcome.BUS_ERROR -> {
                    if (!ObdConnectionPolicy.shouldRetryProtocolSearch(outcome, attempt)) break
                    // A remembered protocol that fails falls back to ATSP0 once, else ATPC.
                    transport.sendCommand(
                        ElmProtocol.recoveryCommandAfterBusError(fixedProtocol, attempt),
                        ObdConnectionPolicy.INIT_READ_TIMEOUT_MS,
                    )
                    delay(ObdConnectionPolicy.PROTOCOL_RETRY_PAUSE_MS)
                }
            }
        }
        Log.w(TAG, "protocol search did not lock — continuing; the run loop reports the bus state")
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

    /** Reads the vehicle's bin totals from the DB (the source of truth) for the live display. */
    private suspend fun loadBinSnapshot(vehicleId: String): List<SpeedBinStats> =
        speedBinDao.getForVehicle(vehicleId).map {
            SpeedBinStats(
                vehicleId = it.vehicleId,
                binIndex = it.binIndex,
                distanceKm = it.distanceKm,
                fuelL = it.fuelL,
                seconds = it.seconds,
                samples = it.samples,
            )
        }

    /**
     * Adds the bin increments collected since the last flush to the DB (additive, in one
     * transaction), then re-reads the totals so a reset/import done meanwhile is reflected in the
     * live display. The deltas are snapshotted and cleared before the write, which runs
     * non-cancellable ([PendingFlush]), so a stop can never make the final flush write them twice.
     * Returns the fresh snapshot, or null when the write failed (the deltas are merged back and
     * retried on the next flush).
     */
    private suspend fun flushBinDeltas(
        vehicleId: String,
        deltas: MutableMap<Int, SpeedBinStats>,
    ): List<SpeedBinStats>? {
        val written = PendingFlush.bins(
            deltas = deltas,
            write = { batch -> speedBinDao.addDeltas(batch.map { it.toEntity() }) },
            onFailure = { Log.w(TAG, "speed-bin delta flush failed; keeping ${deltas.size} pending bin(s)", it) },
        )
        if (!written) return null
        return guarded("reload speed bins") { loadBinSnapshot(vehicleId) }
    }

    /** Inserts the buffered raw samples in one batch ([PendingFlush]: never twice, retried on failure). */
    private suspend fun flushSamples(buffer: MutableList<ObdSampleEntity>) {
        PendingFlush.batch(
            buffer = buffer,
            write = { batch -> sampleDao.insertAll(batch) },
            onFailure = { Log.w(TAG, "raw sample flush failed; keeping ${buffer.size} buffered sample(s)", it) },
        )
    }

    /**
     * Runs one persistence step of the loop and logs instead of throwing: a SQLite error (disk
     * full, a locked DB during a backup import, …) must cost that one write, not the whole
     * drive. Cancellation still propagates.
     */
    private inline fun <T> guarded(what: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what failed", e)
        null
    }

    /**
     * The warm baseline for the cold-start learner: the same effective curve routing uses (see
     * [ColdStartLearner.warmBaseline]), falling back to the plain default if it cannot be built.
     */
    private fun warmBaseline(vehicle: VehicleProfile, bins: List<SpeedBinStats>): ConsumptionCurve {
        val default = DefaultCurve.forVehicle(vehicle.ratedCombinedL100, vehicle.fuelType)
        val manual = vehicle.manualCurve
            ?.takeIf { it.size >= 2 }
            ?.let { runCatching { ConsumptionCurve(it) }.getOrNull() }
        return runCatching { ColdStartLearner.warmBaseline(LearnedCurve(bins), manual, default) }
            .getOrElse {
                Log.w(TAG, "effective curve for the cold-start baseline failed; using the default", it)
                default
            }
    }

    /**
     * Factory for the run's ignition-off detector. A test hook (production always uses the
     * policy timeouts); the detector deliberately lives for the whole run, across reconnects.
     */
    @Volatile
    internal var engineOffTrackerFactory: () -> EngineOffTracker = { EngineOffTracker() }

    private suspend fun runLoop(
        transport: ObdTransport,
        vehicle: VehicleProfile,
        vehicleId: String,
        runId: Long,
    ) {
        // Bug B fix: the DB is the source of truth for bin totals. The loop only collects
        // *increments* (`binDeltas`) and adds them with an additive upsert, so a reset, an
        // "adopt learned" or a backup import done while logging is never overwritten by a stale
        // in-memory snapshot. `binSnapshot` is only a read-only view for the live display.
        var binSnapshot: List<SpeedBinStats> = guarded("load speed bins") { loadBinSnapshot(vehicleId) }.orEmpty()
        val binDeltas = mutableMapOf<Int, SpeedBinStats>()

        // A simulated drive must never teach the real vehicle's curve, its cold-start mean nor
        // pollute its raw samples.
        val simulated = transport.isSimulated
        val processor = ObdSampleProcessor(
            fuelType = vehicle.fuelType,
            engineDisplacementL = vehicle.engineDisplacementL,
            fuelRateCorrection = vehicle.fuelRateCorrection,
            learningEnabled = !simulated,
        )
        val tripDetector = TripDetector()
        val coldStartLearner: ColdStartLearner? =
            if (simulated) null else ColdStartLearner(warmCurve = warmBaseline(vehicle, binSnapshot))
        val tripRecorder = TripRecorder(tripDao)
        guarded("close leftover trips") { tripRecorder.closeLeftovers(System.currentTimeMillis()) }
        // Provenance for every trip this run records: the demo transport must never look real.
        val source = if (simulated) TripSource.DEMO else TripSource.REAL

        var lastSample: ObdSample? = null
        var sampleCount = 0
        var tripDistance = 0.0
        var tripFuel = 0.0
        var tripSeconds = 0.0
        var tripIdleSeconds = 0.0
        var tripMaxSpeed = 0.0
        // True while the open trip continues a row closed moments ago (PLAN.md 5.5).
        var tripContinued = false
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
        // Ignition-off clocks for the WHOLE run: an in-run reconnect must not reset them, or a
        // parked car whose link keeps dropping never reaches the timeout.
        val engineOff = engineOffTrackerFactory()
        val fuelRateHold = FuelRateHold()
        var validSamples = 0
        // `Connected` is only published once the ECU actually answered (a valid speed/RPM):
        // with the ignition off the adapter still completes init and answers NO DATA.
        var connectedPublished = false
        var reconnectCyclesWithoutData = 0
        var stopReason: ObdStopReason? = null
        var consecutiveBad = 0
        // NO DATA (ECU quiet, bus up) tracked separately — see the comment at its use below.
        var consecutiveNoData = 0
        // Mid-drive bad-reply escalation ladder (ObdConnectionPolicy.nextBadStreakAction): when
        // the current unbroken bad streak started, and whether its one-shot soft resync (drain
        // + ATPC, no socket teardown) has already been tried. Both reset the moment a good
        // reply — or a NO-DATA reply, which is not a link problem — arrives.
        var badStreakStartedMs: Long? = null
        var softResyncAttemptedForStreak = false
        var pidNegotiationFailed = false
        var supportedPids: Set<Int> = emptySet()
        // Samples buffered since the last flush; the run loop inserts them in one batch instead
        // of one row per ~250 ms poll.
        val sampleBuffer = mutableListOf<ObdSampleEntity>()
        val loopStartMs = System.currentTimeMillis()

        /** Closes the open trip with the current totals and auto-links a real one. */
        suspend fun closeOpenTrip(endedAtMs: Long) {
            // Anchor the link on the start the recorder actually wrote, not the detector
            // transition, so a divergence can never mis-window the match.
            val startedAtMs = tripRecorder.tripStartedAtMs
            val alreadyLinked = tripRecorder.continuedLinkedTrip
            val closedTripId = guarded("close trip") {
                tripRecorder.end(
                    vehicleId = vehicleId,
                    endedAtMs = endedAtMs,
                    distanceKm = tripDistance,
                    fuelL = tripFuel,
                    maxSpeedKmh = tripMaxSpeed,
                    idleSeconds = tripIdleSeconds,
                    pricePerLiter = pricePerLiter,
                )
            }
            // A continued trip that already owns a route search keeps it; re-running the matcher
            // would exclude that search as "taken" and could link a different one.
            if (closedTripId != null && source == TripSource.REAL && !alreadyLinked) {
                guarded("auto-link trip") { tripLinker.autoLink(closedTripId, startedAtMs, endedAtMs) }
            }
        }

        /** Hands the finished trip's cold-start extra to the repository (never for the demo). */
        suspend fun finishColdStart() {
            val learner = coldStartLearner ?: return
            // A continued trip is the same cold start as its first part, already recorded when
            // that part closed: counting the remainder again would add a bogus second start.
            val record = learner.hasColdSamples && !tripContinued
            val extra = learner.endTrip()
            if (record) guarded("record cold start") { coldStartRepository.record(vehicleId, extra) }
        }

        try {
            val negotiation = negotiatePids(transport, runId)
            supportedPids = negotiation.pids
            pidNegotiationFailed = negotiation.negotiationFailed
            var scheduler = PidScheduler(supportedPids, pidNegotiationFailed)
            val vin = readVin(transport, runId)
            // The connect pipeline (init -> settle -> negotiate -> VIN) is done, but the status
            // stays Connecting (negotiating stage) until the first valid speed/RPM reply below.
            publish(runId) {
                it.copy(
                    deviceName = transport.deviceName,
                    supportedPids = supportedPids,
                    vin = vin,
                    connectionStage = ObdConnectStage.NegotiatingPids,
                )
            }

            while (true) {
                // A manual stop between polls must unwind immediately instead of waiting for
                // the next cancellation point.
                if (stopRequested || !runGeneration.isCurrent(runId)) return
                val now = System.currentTimeMillis()

                // Rate-based polling: speed, RPM and the best fuel source every loop, the slow
                // PIDs on their own interval (held in between) — see PidScheduler.
                val polled = LinkedHashMap<Int, String>()
                for (pid in scheduler.pidsDue(now)) {
                    polled[pid] = transport.sendCommand(ElmProtocol.command(pid))
                }
                val rawSpeed = polled[ElmProtocol.PID_SPEED].orEmpty()
                val rawRpm = polled[ElmProtocol.PID_RPM].orEmpty()
                val parsedSpeed = ElmProtocol.speed(rawSpeed)
                val parsedRpm = ElmProtocol.rpm(rawRpm)
                val busAlive = parsedSpeed != null || parsedRpm != null
                for ((pid, reply) in polled) scheduler.record(pid, reply, now, busAlive)
                val rawCoolant = scheduler.reply(ElmProtocol.PID_COOLANT_TEMP, now)

                // Raw parsed values: persisted as-is so learned data can be rebuilt later.
                val rawSample = ObdSample(
                    timestampMs = now,
                    speedKmh = parsedSpeed,
                    rpm = parsedRpm,
                    mafGps = ElmProtocol.mafGps(scheduler.reply(ElmProtocol.PID_MAF, now)),
                    fuelRateLph = ElmProtocol.fuelRateLph(scheduler.reply(ElmProtocol.PID_FUEL_RATE, now)),
                    mapKpa = ElmProtocol.mapKpa(scheduler.reply(ElmProtocol.PID_MAP, now)),
                    intakeTempC = ElmProtocol.intakeTempC(scheduler.reply(ElmProtocol.PID_INTAKE_TEMP, now)),
                    coolantTempC = ElmProtocol.coolantTempC(rawCoolant),
                    engineLoadPct = ElmProtocol.engineLoadPct(scheduler.reply(ElmProtocol.PID_ENGINE_LOAD, now)),
                    fuelLevelPct = ElmProtocol.fuelLevelPct(scheduler.reply(ElmProtocol.PID_FUEL_LEVEL, now)),
                )

                if (busAlive) {
                    validSamples++
                    reconnectCyclesWithoutData = 0
                    if (!connectedPublished) {
                        connectedPublished = true
                        publish(runId) {
                            it.copy(
                                status = ObdStatus.Connected,
                                deviceName = transport.deviceName,
                                supportedPids = supportedPids,
                                connectionStage = null,
                                connectingSinceMs = null,
                            )
                        }
                    }
                }

                // Sanitize -> bounded fuel rate -> learnable deltas -> smoothed live consumption.
                // The same processor rebuilds learned data from stored samples (LearnedDataRepair).
                val processed = processor.process(rawSample, binDeltas, vehicleId)
                val sample = processed.sample
                val speed = sample.speedKmh
                val rpm = sample.rpm
                val coolant = sample.coolantTempC
                val fuelRate = processed.fuelRateLph
                // No clamp here: the aggregator rejects gaps > 2 s itself, while trip
                // totals need the true wall-clock delta.
                val dtSec = processed.dtSec

                coldStartLearner?.onSample(
                    speedKmh = sample.speedKmh,
                    fuelRateLph = fuelRate,
                    dtSec = dtSec,
                    coolantTempC = sample.coolantTempC,
                )

                if (sample.engineRunning) {
                    // A dropped fuel-rate reply holds the last rate for a few seconds instead
                    // of counting as 0 L/h.
                    val rate = fuelRateHold.resolve(now, fuelRate)
                    tripFuel += rate * dtSec / 3600.0
                    tripDistance += (speed ?: 0.0) * dtSec / 3600.0
                    tripSeconds += dtSec
                    if ((speed ?: 0.0) < 1.0) tripIdleSeconds += dtSec
                    tripMaxSpeed = maxOf(tripMaxSpeed, speed ?: 0.0)
                }

                when (val transition = tripDetector.onSample(sample)) {
                    is TripDetector.TripTransition.Started -> {
                        val continued = guarded("start trip") {
                            tripRecorder.startOrContinue(
                                vehicleId,
                                tripDetector.startedAtMs ?: sample.timestampMs,
                                source,
                            )
                        }
                        tripContinued = continued != null
                        if (continued != null) {
                            Log.i(TAG, "continuing the trip that closed moments ago (same drive)")
                            tripDistance += continued.distanceKm
                            tripFuel += continued.fuelL
                            tripSeconds += continued.seconds
                            tripIdleSeconds += continued.idleSeconds
                            tripMaxSpeed = maxOf(tripMaxSpeed, continued.maxSpeedKmh)
                        }
                    }
                    is TripDetector.TripTransition.Ended -> {
                        closeOpenTrip(transition.endedAtMs)
                        tripDistance = 0.0
                        tripFuel = 0.0
                        tripSeconds = 0.0
                        tripIdleSeconds = 0.0
                        tripMaxSpeed = 0.0
                        finishColdStart()
                        tripContinued = false
                    }
                    else -> Unit
                }

                sampleCount++
                if (!simulated) sampleBuffer += rawSample.toEntity(vehicleId)
                if (now - lastSamplePersistMs >= SAMPLE_PERSIST_INTERVAL_MS) {
                    flushSamples(sampleBuffer)
                    lastSamplePersistMs = now
                }

                if (now - lastBinPersistMs >= SPEED_BIN_PERSIST_INTERVAL_MS) {
                    flushBinDeltas(vehicleId, binDeltas)?.let { binSnapshot = it }
                    pricePerLiter = try {
                        fuelPriceRepository.current(vehicle.grade).pricePerLiter
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        pricePerLiter
                    }
                    if (tripRecorder.isOpen) {
                        guarded("trip checkpoint") {
                            tripRecorder.checkpoint(
                                vehicleId = vehicleId,
                                nowMs = now,
                                distanceKm = tripDistance,
                                fuelL = tripFuel,
                                maxSpeedKmh = tripMaxSpeed,
                                idleSeconds = tripIdleSeconds,
                            )
                        }
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
                // Connection logic keys on the raw parse, as before sanitizing existed.
                engineOff.onSample(now, rawSample.rpm, rawSample.speedKmh, rpmPidSupported)

                // Bug A fix: trip-computer style smoothing (fuel sum / distance sum over a ~8 s
                // window), L/100 km only while moving, L/h otherwise — see LiveConsumptionWindow.
                val instantL100 = processed.live.litersPer100Km
                val displayBins = mergeSpeedBins(binSnapshot, binDeltas.values)

                // "NO DATA" means the bus answered but the ECU stayed quiet — the classic
                // signature of the ignition being off while the dongle stays powered from the
                // OBD port. That is NOT a link failure: reconnecting cannot make a sleeping ECU
                // answer, and doing it anyway used to (a) tear down/rebuild the RFCOMM link
                // every ~1.25 s, draining the battery and hammering a single-link clone, and
                // (b) reset the RPM-absence clock on every such reconnect, so the 60 s
                // ignition-off timeout could never accumulate and the loop never stopped itself.
                // It is tracked with its own counter so it can still surface to the UI, just
                // without ever counting toward `consecutiveBad` / the [ObdConnectionPolicy.
                // nextBadStreakAction] escalation ladder below.
                val noDataNow = rawSpeed.contains("NO DATA", ignoreCase = true) ||
                    rawSpeed.contains("NODATA", ignoreCase = true) ||
                    rawSpeed.contains("UNABLE TO CONNECT", ignoreCase = true)
                // Read once and reused below: a HARD timeout on ANY of this iteration's polls —
                // not just the speed poll classified here — already closed the link via
                // ElmLink's watchdog by the time we get here (speed is only sent first; a later
                // PID, e.g. RPM or MAF, timing out is just as real a link death, and rawSpeed
                // alone would otherwise miss it for a whole extra 250ms poll). That case is
                // unambiguous and takes priority over anything the speed reply itself said.
                val linkOpen = transport.isConnected
                val badReason = when {
                    !linkOpen -> "TIMEOUT"
                    noDataNow -> "NO DATA"
                    rawSpeed.contains("SEARCHING", ignoreCase = true) -> ObdConnectionPolicy.ERROR_SEARCHING
                    rawSample.speedKmh == null && rawSpeed.isNotBlank() -> "PARSE"
                    else -> null
                }
                if (!linkOpen) {
                    consecutiveNoData = 0
                    consecutiveBad++
                    if (badStreakStartedMs == null) {
                        badStreakStartedMs = now
                        softResyncAttemptedForStreak = false
                        Log.w(
                            TAG,
                            "bad-reply streak started: link already closed this poll " +
                                "(last speed raw=${ElmLink.printable(rawSpeed)}, " +
                                "rpm raw=${ElmLink.printable(rawRpm)})",
                        )
                    }
                } else if (noDataNow) {
                    consecutiveBad = 0
                    consecutiveNoData++
                    if (badStreakStartedMs != null) {
                        Log.i(TAG, "bad-reply streak cleared by a NO DATA reply (not a link problem)")
                    }
                    badStreakStartedMs = null
                    softResyncAttemptedForStreak = false
                } else if (badReason != null) {
                    consecutiveNoData = 0
                    consecutiveBad++
                    if (badStreakStartedMs == null) {
                        badStreakStartedMs = now
                        softResyncAttemptedForStreak = false
                        Log.w(
                            TAG,
                            "bad-reply streak started: $badReason on speed poll " +
                                "(raw=${ElmLink.printable(rawSpeed)}, linkOpen=$linkOpen)",
                        )
                    }
                } else {
                    consecutiveNoData = 0
                    consecutiveBad = 0
                    if (badStreakStartedMs != null) {
                        Log.i(TAG, "bad-reply streak cleared after ${now - badStreakStartedMs!!}ms by a good reply")
                    }
                    badStreakStartedMs = null
                    softResyncAttemptedForStreak = false
                }
                val diagError = when {
                    consecutiveBad >= CONSECUTIVE_ERROR_THRESHOLD -> badReason
                    consecutiveNoData >= CONSECUTIVE_ERROR_THRESHOLD -> "NO DATA"
                    else -> null
                }

                val elapsedSec = ((now - loopStartMs) / 1000.0).coerceAtLeast(0.001)
                val sampleRateHz = sampleCount / elapsedSec

                publish(runId) {
                    it.copy(
                        speedKmh = speed,
                        rpm = rpm,
                        coolantTempC = coolant,
                        fuelRateLph = processed.live.litersPerHour,
                        fuelLevelPct = sample.fuelLevelPct,
                        instantL100 = instantL100,
                        tripDistanceKm = tripDistance,
                        tripFuelL = tripFuel,
                        tripSeconds = tripSeconds,
                        bins = displayBins,
                        totalDistanceKm = displayBins.sumOf { bin -> bin.distanceKm },
                        sampleCount = sampleCount,
                        sampleRateHz = sampleRateHz,
                        batteryVoltage = batteryVoltage,
                        supportedPids = supportedPids,
                        lastRawReply = rawSpeed.take(MAX_RAW_REPLY_CHARS),
                        lastError = diagError,
                        validSampleCount = validSamples,
                    )
                }

                lastSample = sample

                // Mid-drive bad-reply escalation ladder — see ObdConnectionPolicy.
                // nextBadStreakAction for the full rationale. A streak only exists while
                // `badStreakStartedMs != null` (set above whenever `badReason` is a real link
                // problem, i.e. never for NO DATA).
                badStreakStartedMs?.let { streakStartedMs ->
                    val badStreakMs = now - streakStartedMs
                    when (
                        ObdConnectionPolicy.nextBadStreakAction(
                            badStreakMs = badStreakMs,
                            linkOpen = linkOpen,
                            softResyncAttempted = softResyncAttemptedForStreak,
                        )
                    ) {
                        ObdConnectionPolicy.BadStreakAction.WAIT -> Unit

                        ObdConnectionPolicy.BadStreakAction.SOFT_RESYNC -> {
                            softResyncAttemptedForStreak = true
                            Log.w(
                                TAG,
                                "bad-reply streak ${badStreakMs}ms ($badReason, last raw=" +
                                    "${ElmLink.printable(rawSpeed)}) — soft resync (drain + ATPC), " +
                                    "socket kept open",
                            )
                            // Non-destructive: a bare CR resyncs to the next `>` prompt (exactly
                            // the line-clear used on init), then ATPC idles the adapter's
                            // protocol state — neither ever closes the RFCOMM socket, even if it
                            // times out itself (`sendSoft`, unlike `sendCommand`, never does).
                            transport.sendSoft("", ObdConnectionPolicy.SOFT_RESYNC_TIMEOUT_MS)
                            transport.sendSoft(
                                ElmProtocol.CMD_PROTOCOL_CLOSE,
                                ObdConnectionPolicy.SOFT_RESYNC_TIMEOUT_MS,
                            )
                        }

                        ObdConnectionPolicy.BadStreakAction.RECONNECT -> {
                            if (ObdConnectionPolicy.reconnectCyclesExhausted(reconnectCyclesWithoutData)) {
                                // The link keeps coming back at the socket level but never
                                // yields data: stop rebuilding it for this run.
                                Log.e(
                                    TAG,
                                    "giving up: $reconnectCyclesWithoutData reconnect(s) in a row without a " +
                                        "valid speed/RPM reply",
                                )
                                publishError(runId, ObdConnectionPolicy.ERROR_RECONNECT_LIMIT)
                                return
                            }
                            reconnectCyclesWithoutData++
                            Log.w(
                                TAG,
                                "reconnecting: bad-reply streak ${badStreakMs}ms, linkOpen=$linkOpen, " +
                                    "reason=$badReason, consecutiveBad=$consecutiveBad, " +
                                    "softResyncTried=$softResyncAttemptedForStreak, " +
                                    "last raw speed=${ElmLink.printable(rawSpeed)} rpm=" +
                                    "${ElmLink.printable(rawRpm)} coolant=${ElmLink.printable(rawCoolant)}",
                            )
                            transport.disconnect()
                            publish(runId) {
                                it.copy(
                                    status = ObdStatus.Connecting,
                                    lastError = "RECONNECT",
                                    connectionStage = ObdConnectStage.ConnectingSocket,
                                    connectingSinceMs = System.currentTimeMillis(),
                                )
                            }
                            val reconnectStartedMs = System.currentTimeMillis()
                            if (!reconnectWithBackoff(transport)) {
                                if (stopRequested || !runGeneration.isCurrent(runId)) return
                                Log.e(
                                    TAG,
                                    "reconnect failed after ${System.currentTimeMillis() - reconnectStartedMs}ms " +
                                        "of retries — giving up",
                                )
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
                            Log.i(
                                TAG,
                                "socket reconnected after ${System.currentTimeMillis() - reconnectStartedMs}ms " +
                                    "— re-initializing",
                            )
                            // A fresh socket must be re-initialized exactly like a first connect;
                            // an adapter that no longer answers ATZ is a real failure, not a retry.
                            val initError = initializeWithReopen(transport, runId)
                            if (initError != null) {
                                if (!isStale(runId)) publishError(runId, initError)
                                return
                            }
                            settleProtocol(transport, runId)
                            // Re-negotiate once on reconnect so a stale bitmap (or a failed initial
                            // negotiation) is refreshed against the live socket.
                            val renegotiation = negotiatePids(transport, runId)
                            supportedPids = renegotiation.pids
                            pidNegotiationFailed = renegotiation.negotiationFailed
                            scheduler = PidScheduler(supportedPids, pidNegotiationFailed)
                            // Back to Connected only once the ECU answers again.
                            connectedPublished = false
                            publish(runId) {
                                it.copy(
                                    status = ObdStatus.Connecting,
                                    supportedPids = supportedPids,
                                    connectionStage = ObdConnectStage.NegotiatingPids,
                                )
                            }
                            consecutiveBad = 0
                            consecutiveNoData = 0
                            badStreakStartedMs = null
                            softResyncAttemptedForStreak = false
                            // The ignition-off clocks (engineOff) are deliberately kept.
                            lastSample = null
                            processor.resetTiming()
                        }
                    }
                }

                if (engineOff.shouldStop(now, batteryVoltage)) {
                    Log.i(TAG, "ignition off detected — stopping OBD loop")
                    stopReason = ObdStopReason.IGNITION_OFF
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
                flushSamples(sampleBuffer)
                // Additive delta flush (never an absolute snapshot); logs and keeps going on failure.
                flushBinDeltas(vehicleId, binDeltas)
                guarded("close trip on stop") {
                    // End at the last moment the engine ran / the car moved, not after the
                    // ignition-off wait, so a continuing run can pick the same trip up.
                    val endAt = tripDetector.lastActiveMs ?: lastSample?.timestampMs ?: System.currentTimeMillis()
                    val end = tripDetector.forceEnd(endAt)
                    if (end is TripDetector.TripTransition.Ended) closeOpenTrip(end.endedAtMs)
                }
                guarded("record cold start on stop") { finishColdStart() }
                closeTransport(transport, runId, "run loop ended")
                publish(runId) {
                    it.copy(
                        status = if (it.status == ObdStatus.Error) it.status else ObdStatus.Disconnected,
                        stopReason = stopReason,
                        connectionStage = null,
                        connectingSinceMs = null,
                    )
                }
            }
        }
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
        while (ObdConnectionPolicy.shouldAttemptReconnect(attempt, isDeviceConnected(transport)) &&
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
    }
}