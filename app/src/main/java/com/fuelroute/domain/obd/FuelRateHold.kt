package com.fuelroute.domain.obd

/**
 * Fuel rate used for trip totals while the engine runs. A single missing reply (a fuel-rate PID
 * that did not answer this poll) used to count as 0 L/h, silently under-counting trip fuel on
 * clones that drop replies. The last known rate is held for up to [maxHoldMs]; beyond that the
 * rate is genuinely unknown and 0 is used, as before.
 *
 * Pure Kotlin, not thread-safe (owned by one loop).
 */
class FuelRateHold(private val maxHoldMs: Long = DEFAULT_MAX_HOLD_MS) {
    private var lastRate: Double? = null
    private var lastRateMs: Long = 0L

    /** The rate (L/h) to integrate at [nowMs], given this poll's [rate] (null = unknown). */
    fun resolve(nowMs: Long, rate: Double?): Double {
        if (rate != null && rate.isFinite() && rate >= 0.0) {
            lastRate = rate
            lastRateMs = nowMs
            return rate
        }
        val held = lastRate ?: return 0.0
        return if (nowMs - lastRateMs <= maxHoldMs) held else 0.0
    }

    /** Forgets the held value (e.g. after the engine stopped). */
    fun reset() {
        lastRate = null
    }

    companion object {
        const val DEFAULT_MAX_HOLD_MS = 5_000L
    }
}
