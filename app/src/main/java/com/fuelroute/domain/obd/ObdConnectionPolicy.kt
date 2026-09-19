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
