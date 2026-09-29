package com.fuelroute.data.obd

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide ownership of the (single-connection) ELM327 dongle between the background presence
 * probe and the logging engine.
 *
 * The per-address connect lock in [BluetoothClassicTransport] only covers `connect()`, while the
 * probe keeps its socket through `ATZ`/`ATRV`/`010C` for several seconds. An engine connect in
 * that window raced the probe for the clone's only RFCOMM channel. Now:
 *  - the probe runs entirely inside [runProbe] and never starts while the engine holds a claim;
 *  - the engine [claimForEngine]s the dongle before connecting: a running probe is aborted at once
 *    (its socket is closed, so any pending read returns) and the engine waits, bounded, until the
 *    probe has let go. The probe yields; the engine never waits for a probe to finish its checks.
 *
 * Pure JVM so the hand-over is unit-tested.
 */
object ObdDongleSession {

    /** Longest the engine waits for an aborted probe to release the dongle. */
    const val PROBE_YIELD_WAIT_MS = 3_000L

    private val probeMutex = Mutex()
    private val engineClaims: MutableSet<Any> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var probeAbort: (() -> Unit)? = null

    @Volatile
    private var probeYielded = false

    /** True while an engine transport holds (or is acquiring) the dongle. */
    val isEngineActive: Boolean
        get() = engineClaims.isNotEmpty()

    /** True while a probe holds the dongle. */
    val isProbeActive: Boolean
        get() = probeMutex.isLocked

    /**
     * Runs a probe [block] holding the dongle. Returns null — without running [block], or with its
     * result discarded — when the engine holds the dongle or claimed it meanwhile; [abort] is then
     * called (from the engine's thread) and must make [block] return promptly, e.g. by closing the
     * probe's socket. Also null when another probe is already running.
     */
    suspend fun <T> runProbe(abort: () -> Unit, block: suspend () -> T): T? {
        if (isEngineActive) return null
        if (!probeMutex.tryLock()) return null
        try {
            probeYielded = false
            probeAbort = abort
            // The engine may have claimed between the first check and publishing [abort].
            if (isEngineActive) return null
            val result = block()
            return if (probeYielded || isEngineActive) null else result
        } finally {
            probeAbort = null
            probeMutex.unlock()
        }
    }

    /**
     * The engine takes the dongle for [owner] (idempotent): aborts a running probe and waits up to
     * [waitMs] for it to release. Held until [releaseEngine].
     */
    suspend fun claimForEngine(owner: Any, waitMs: Long = PROBE_YIELD_WAIT_MS) {
        engineClaims.add(owner)
        val abort = probeAbort
        if (abort != null) {
            probeYielded = true
            runCatching { abort() }
        }
        if (probeMutex.isLocked) {
            withTimeoutOrNull(waitMs) {
                probeMutex.lock()
                probeMutex.unlock()
            }
        }
    }

    /** The engine let go of the dongle for [owner]. */
    fun releaseEngine(owner: Any) {
        engineClaims.remove(owner)
    }
}
