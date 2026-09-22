package com.fuelroute.domain.obd

/**
 * Pure decision rules for the whole OBD connect lifecycle, shared by the Bluetooth transport,
 * the engine, and the foreground logging service. Kept free of Android/coroutine types so it
 * is unit-testable on the JVM.
 *
 * (This merges the former `ConnectPolicy`, which had grown as a second, overlapping object
 * during parallel remediation work.)
 */
object ObdConnectionPolicy {

    // --- Connect lifecycle -----------------------------------------------------------------

    /** Hard upper bound on a single Bluetooth connect attempt. */
    const val CONNECT_TIMEOUT_MS = 15_000L

    /**
     * Short read timeout applied to each ELM initialization command. A powered-off or silent
     * dongle never answers, so aborting an init read after this window surfaces a specific error
     * in well under a second instead of dragging through every command at the full socket
     * read-timeout (1.5 s each). The run-loop reads keep their normal timeout.
     */
    const val INIT_READ_TIMEOUT_MS = 600L

    /**
     * A freshly started logging service never stops itself during this window, so the engine
     * state flow's initial `Disconnected` value cannot kill it before the first `Connecting`
     * emission (the `Disconnected -> Connecting` transient).
     */
    const val STARTUP_GRACE_MS = 2_000L

    /** Engine status names, mirrored from `ObdStatus` for the pure policy. */
    const val STATUS_DISCONNECTED = "Disconnected"
    const val STATUS_CONNECTING = "Connecting"
    const val STATUS_CONNECTED = "Connected"
    const val STATUS_ERROR = "Error"

    // --- Connect retry / workaround chain (card 35) ----------------------------------------

    /** How many times the full connect workaround chain is tried before giving up. */
    const val CONNECT_ATTEMPTS = 3

    /** Short pause between full connect-chain retries (not the long reconnect backoff). */
    const val CONNECT_RETRY_BACKOFF_MS = 750L

    /**
     * Ordered Bluetooth SPP socket strategies. Cheap ELM327 dongles often reject the secure
     * service-record lookup but accept the insecure variant, or only the raw channel 1.
     */
    enum class ConnectVariant { SECURE_RFCOMM, INSECURE_RFCOMM, CHANNEL_1 }

    /** The workaround order: secure -> insecure -> reflection channel 1. */
    fun connectVariants(): List<ConnectVariant> =
        listOf(ConnectVariant.SECURE_RFCOMM, ConnectVariant.INSECURE_RFCOMM, ConnectVariant.CHANNEL_1)

    /** Number of attempts the transport makes for a single `connect()` call. */
    fun connectAttempts(): Int = CONNECT_ATTEMPTS

    /** True while another full-chain connect attempt is allowed ([attempt] is 1-based). */
    fun shouldRetryConnect(attempt: Int): Boolean = attempt < CONNECT_ATTEMPTS

    // --- Reconnect / backoff ---------------------------------------------------------------

    /** Consecutive empty/failed speed replies that trigger a reconnect. */
    const val RECONNECT_AFTER_FAILURES = 5

    /** Give up reconnecting after this total wait; status becomes `Error`. */
    const val MAX_RECONNECT_WINDOW_MS = 180_000L

    /** Backoff never grows beyond this. */
    const val BACKOFF_CAP_MS = 60_000L

    /** Reconnect attempts after a dropped link before the status becomes `Error`. */
    const val MAX_RECONNECT_ATTEMPTS = 3

    fun shouldReconnect(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= RECONNECT_AFTER_FAILURES

    /**
     * True while a dropped link should still be re-established: reconnect attempts remain
     * AND the dongle is still ACL-connected. Hammering an absent dongle is pointless.
     *
     * @param attempt number of reconnect attempts already made (0-based).
     * @param deviceConnected whether the adapter still reports the dongle as connected.
     */
    fun shouldRetryReconnect(attempt: Int, deviceConnected: Boolean): Boolean =
        attempt < MAX_RECONNECT_ATTEMPTS && deviceConnected

    /** Exponential backoff 2, 4, 8, 16, 32 s then a 60 s plateau (attempt >= 5). */
    fun backoffDelayMs(attempt: Int): Long {
        val safeAttempt = attempt.coerceIn(0, 5)
        return (2_000L shl safeAttempt).coerceAtMost(BACKOFF_CAP_MS)
    }

    /**
     * True while the engine's reconnect backoff loop should keep trying. Once a stop is
     * requested (the engine's `stopRequested` flag set by `stop()`/`disconnect()`/`reset()`)
     * the loop must return without further attempts, even if a blocking `connect()` just
     * returned.
     */
    fun shouldContinueReconnect(stopRequested: Boolean): Boolean = !stopRequested

    // --- Ignition-off detection ------------------------------------------------------------

    /** RPM must stay unavailable this long before we treat the ignition as off. */
    const val IGNITION_OFF_RPM_TIMEOUT_MS = 60_000L

    /** Below this voltage the engine cannot be running. */
    const val LOW_BATTERY_VOLTS = 11.5

    /**
     * Plausible automotive battery voltage window. Readings outside it are adapter noise:
     * cheap ELM327 clones answer `ATRV` with their internal logic rail (0 V / 3.3 V / 5 V)
     * instead of the car's battery, so they must not be mistaken for a dead battery.
     */
    const val MIN_PLAUSIBLE_BATTERY_VOLTS = 8.0
    const val MAX_PLAUSIBLE_BATTERY_VOLTS = 16.0

    /**
     * True when the sample stream says the engine is off. RPM is the authoritative signal: it
     * must be missing for at least [IGNITION_OFF_RPM_TIMEOUT_MS]. Voltage is only a secondary
     * signal, and only when the reading lies inside [MIN_PLAUSIBLE_BATTERY_VOLTS]..
     * [MAX_PLAUSIBLE_BATTERY_VOLTS] and is below [LOW_BATTERY_VOLTS] — a clone reporting 0 V
     * or 3.3 V is ignored rather than read as a dead battery.
     */
    fun shouldStopForIgnitionOff(
        rpmNullSinceMs: Long?,
        nowMs: Long,
        batteryVoltage: Double?,
    ): Boolean {
        val rpmMissingLongEnough =
            rpmNullSinceMs != null && nowMs - rpmNullSinceMs >= IGNITION_OFF_RPM_TIMEOUT_MS
        val plausibleLowVoltage = batteryVoltage != null &&
            batteryVoltage in MIN_PLAUSIBLE_BATTERY_VOLTS..MAX_PLAUSIBLE_BATTERY_VOLTS &&
            batteryVoltage < LOW_BATTERY_VOLTS
        return rpmMissingLongEnough || plausibleLowVoltage
    }

    // --- Logging-service lifecycle ---------------------------------------------------------

    /**
     * True when the logging service should stop itself because the engine reached a terminal
     * state.
     *
     * @param status engine status name (`Disconnected`, `Connecting`, `Connected`, `Error`).
     * @param elapsedMs time since the service started logging.
     * @param sawData whether the engine ever reached `Connecting`/`Connected`, i.e. the connect
     *   attempt was actually entered. Once true a terminal state is unambiguous and stops
     *   immediately; when false we still wait out [STARTUP_GRACE_MS] so a slow start does not
     *   kill the service on the initial `Disconnected` emission.
     */
    fun shouldAutoStop(status: String, elapsedMs: Long, sawData: Boolean): Boolean {
        val terminal = status == STATUS_ERROR || status == STATUS_DISCONNECTED
        if (!terminal) return false
        if (sawData) return true
        return elapsedMs >= STARTUP_GRACE_MS
    }

    // --- Auto-connect gating ---------------------------------------------------------------

    /**
     * True when an adapter `STATE_ON` broadcast should auto-start for [target].
     *
     * A resolved bonded/last-used target is not enough: the adapter must also be ACL-connected,
     * so merely turning Bluetooth on with a paired-but-absent dongle does not summon the
     * foreground notification.
     */
    fun shouldAutoStartOnAdapterOn(target: String?, connectedAddresses: Collection<String>): Boolean {
        val address = target?.takeIf { it.isNotBlank() } ?: return false
        return connectedAddresses.any { it.equals(address, ignoreCase = true) }
    }

    /**
     * True when every auto-connect entry point must be suppressed because the user explicitly
     * disconnected (card 34). The latch is sticky and is only cleared by an explicit reconnect,
     * so this returns the latch verbatim.
     */
    fun shouldSuppressAutoConnect(manualDisconnect: Boolean): Boolean = manualDisconnect

    // --- Mid-session auto-reconnect --------------------------------------------------------

    /**
     * Upper bound on consecutive service-level reconnect re-arms after a terminal drop. Bounded
     * so a genuinely absent/failing dongle (or a parked car with the ignition off) cannot spin
     * the foreground service forever.
     */
    const val MAX_AUTO_RECONNECT_ATTEMPTS = 5

    /**
     * True when the logging service should re-arm the engine after a terminal drop instead of
     * stopping itself. Requires all of:
     *
     *  - the user enabled auto-connect,
     *  - the user did not explicitly disconnect (the sticky [shouldSuppressAutoConnect] latch),
     *  - the dongle is still reachable (ACL-connected / a known last device address), and
     *  - the bounded attempt budget has not been exhausted ([attempt] is 0-based).
     *
     * @param attempt number of automatic re-arms already made in this logging session.
     */
    fun shouldAutoReconnect(
        attempt: Int,
        autoConnect: Boolean,
        manualDisconnect: Boolean,
        deviceConnected: Boolean,
    ): Boolean = autoConnect &&
        !manualDisconnect &&
        deviceConnected &&
        attempt < MAX_AUTO_RECONNECT_ATTEMPTS

    // --- Machine error codes ---------------------------------------------------------------

    /** Machine error codes surfaced through `LiveObdState.lastError`. */
    const val ERROR_CONNECT = "CONNECT"
    const val ERROR_CONNECT_TIMEOUT = "CONNECT TIMEOUT"
    const val ERROR_SOCKET_CLOSED = "SOCKET CLOSED"
    const val ERROR_SECURITY = "SECURITY"
    const val ERROR_SEARCHING = "SEARCHING"

    /** An ELM init command produced no reply within [INIT_READ_TIMEOUT_MS]. */
    const val ERROR_INIT_TIMEOUT = "INIT TIMEOUT"
}
