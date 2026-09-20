package com.fuelroute.domain.obd

/**
 * Pure decision rules for the OBD connect lifecycle, shared by the Bluetooth transport,
 * the engine and the foreground logging service. Kept free of Android/coroutine types so
 * it is unit-testable on the JVM.
 */
object ConnectPolicy {

    /** Hard upper bound on a single Bluetooth connect attempt. */
    const val CONNECT_TIMEOUT_MS = 15_000L

    /**
     * A freshly started logging service never stops itself during this window, so the
     * engine state flow's initial `Disconnected` value cannot kill it before the first
     * `Connecting` emission (the `Disconnected → Connecting` transient).
     */
    const val STARTUP_GRACE_MS = 2_000L

    const val STATUS_DISCONNECTED = "Disconnected"
    const val STATUS_CONNECTING = "Connecting"
    const val STATUS_CONNECTED = "Connected"
    const val STATUS_ERROR = "Error"

    /**
     * True when the logging service should stop itself because the engine reached a
     * terminal state.
     *
     * @param status engine status name (`Disconnected`, `Connecting`, `Connected`, `Error`).
     * @param elapsedMs time since the service started logging.
     * @param sawData whether the engine ever reached `Connecting`/`Connected`, i.e. the
     *   connect attempt was actually entered. Once true a terminal state is unambiguous
     *   and stops immediately; when false we still wait out [STARTUP_GRACE_MS] so a slow
     *   start does not kill the service on the initial `Disconnected` emission.
     */
    fun shouldAutoStop(status: String, elapsedMs: Long, sawData: Boolean): Boolean {
        val terminal = status == STATUS_ERROR || status == STATUS_DISCONNECTED
        if (!terminal) return false
        if (sawData) return true
        return elapsedMs >= STARTUP_GRACE_MS
    }

    /**
     * True when an adapter `STATE_ON` broadcast should auto-start for [target].
     *
     * A resolved bonded/last-used target is not enough: the adapter must also be
     * ACL-connected, so merely turning Bluetooth on with a paired-but-absent dongle does
     * not summon the foreground notification.
     */
    fun shouldAutoStartOnAdapterOn(target: String?, connectedAddresses: Collection<String>): Boolean {
        val address = target?.takeIf { it.isNotBlank() } ?: return false
        return connectedAddresses.any { it.equals(address, ignoreCase = true) }
    }

    /**
     * True when every auto-connect entry point must be suppressed because the user
     * explicitly disconnected (card 34). The latch is sticky and is only cleared by an
     * explicit reconnect, so this returns the latch verbatim.
     */
    fun shouldSuppressAutoConnect(manualDisconnect: Boolean): Boolean = manualDisconnect

    /**
     * True while the engine's reconnect backoff loop should keep trying. Once a stop is
     * requested (the engine's `stopRequested` flag set by `stop()`/`disconnect()`/`reset()`)
     * the loop must return without further attempts, even if a blocking `connect()` just
     * returned.
     */
    fun shouldContinueReconnect(stopRequested: Boolean): Boolean = !stopRequested
}
