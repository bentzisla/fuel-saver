package com.fuelroute.domain.obd

/**
 * Monotonic generation token for the OBD engine's start/stop lifecycle.
 *
 * A fast `stop()`+`start()` used to let two run loops exist at once: the old loop's `finally`
 * then published `Disconnected` over the new loop's `Connecting` state and disconnected the
 * new transport. Each [next] invalidates every earlier token, so a stale coroutine can ask
 * [isCurrent] before touching shared state.
 *
 * Pure Kotlin (no Android/coroutine types) so it is unit-testable on the JVM.
 */
class ObdRunGeneration {

    private var current = 0L

    /** Starts a new generation and returns its token. */
    @Synchronized
    fun next(): Long {
        current += 1
        return current
    }

    /** The latest issued token. */
    @Synchronized
    fun current(): Long = current

    /** True when [token] still belongs to the latest generation. */
    @Synchronized
    fun isCurrent(token: Long): Boolean = token == current
}