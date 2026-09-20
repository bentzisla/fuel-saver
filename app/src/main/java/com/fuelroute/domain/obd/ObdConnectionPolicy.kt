package com.fuelroute.domain.obd

/**
 * Pure decision rules for keeping a flaky ELM327 link alive: when to reconnect,
 * how long to back off, and when the engine has clearly been switched off.
 * Kept free of Android/coroutine dependencies so it is unit-testable.
 */
object ObdConnectionPolicy {

    /** Consecutive empty/failed speed replies that trigger a reconnect. */
    const val RECONNECT_AFTER_FAILURES = 5

    /** Give up reconnecting after this total wait; status becomes `Error`. */
    const val MAX_RECONNECT_WINDOW_MS = 180_000L

    /** Backoff never grows beyond this. */
    const val BACKOFF_CAP_MS = 60_000L

    /** RPM must stay unavailable this long before we treat the ignition as off. */
    const val IGNITION_OFF_RPM_TIMEOUT_MS = 60_000L

    /** Below this voltage the engine cannot be running. */
    const val LOW_BATTERY_VOLTS = 11.5

    /** How many times the full connect workaround chain is tried before giving up (card 35). */
    const val CONNECT_ATTEMPTS = 3

    /** Short pause between full connect-chain retries (not the long reconnect backoff). */
    const val CONNECT_RETRY_BACKOFF_MS = 750L

    /** Reconnect attempts after a dropped link before the status becomes `Error` (card 35). */
    const val MAX_RECONNECT_ATTEMPTS = 3

    /** Machine error codes surfaced through `LiveObdState.lastError`. */
    const val ERROR_CONNECT = "CONNECT"
    const val ERROR_CONNECT_TIMEOUT = "CONNECT TIMEOUT"
    const val ERROR_SOCKET_CLOSED = "SOCKET CLOSED"
    const val ERROR_SECURITY = "SECURITY"
    const val ERROR_SEARCHING = "SEARCHING"

    /**
     * Ordered Bluetooth SPP socket strategies. Cheap ELM327 dongles often reject the secure
     * service-record lookup but accept the insecure variant, or only the raw channel 1.
     */
    enum class ConnectVariant { SECURE_RFCOMM, INSECURE_RFCOMM, CHANNEL_1 }

    /** The workaround order: secure → insecure → reflection channel 1. */
    fun connectVariants(): List<ConnectVariant> =
        listOf(ConnectVariant.SECURE_RFCOMM, ConnectVariant.INSECURE_RFCOMM, ConnectVariant.CHANNEL_1)

    /** Number of attempts the transport makes for a single `connect()` call. */
    fun connectAttempts(): Int = CONNECT_ATTEMPTS

    /** True while another full-chain connect attempt is allowed ([attempt] is 1-based). */
    fun shouldRetryConnect(attempt: Int): Boolean = attempt < CONNECT_ATTEMPTS

    /**
     * True while a dropped link should still be re-established: reconnect attempts remain
     * AND the dongle is still ACL-connected. Hammering an absent dongle is pointless.
     *
     * @param attempt number of reconnect attempts already made (0-based).
     * @param deviceConnected whether the adapter still reports the dongle as connected.
     */
    fun shouldRetryReconnect(attempt: Int, deviceConnected: Boolean): Boolean =
        attempt < MAX_RECONNECT_ATTEMPTS && deviceConnected

    fun shouldReconnect(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= RECONNECT_AFTER_FAILURES

    /**
     * Exponential backoff 2, 4, 8, 16, 32 s then a 60 s plateau (attempt >= 5).
     */
    fun backoffDelayMs(attempt: Int): Long {
        val safeAttempt = attempt.coerceIn(0, 5)
        return (2_000L shl safeAttempt).coerceAtMost(BACKOFF_CAP_MS)
    }

    /**
     * True when the sample stream says the engine is off: either the battery has
     * dropped below [LOW_BATTERY_VOLTS], or RPM has been missing for at least
     * [IGNITION_OFF_RPM_TIMEOUT_MS].
     */
    fun shouldStopForIgnitionOff(
        rpmNullSinceMs: Long?,
        nowMs: Long,
        batteryVoltage: Double?,
    ): Boolean {
        if (batteryVoltage != null && batteryVoltage < LOW_BATTERY_VOLTS) return true
        return rpmNullSinceMs != null && nowMs - rpmNullSinceMs >= IGNITION_OFF_RPM_TIMEOUT_MS
    }
}
