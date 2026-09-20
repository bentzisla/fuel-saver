package com.fuelroute.domain.retention

/**
 * Pure retention-window math for raw OBD samples (card 16). Kept Android-free so the clamp and
 * cutoff computation can be unit-tested on the JVM; the worker/scheduler are thin glue around it.
 */
object RetentionPolicy {

    const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    /** Default window shown in the UI when the user has never chosen one. */
    const val DEFAULT_RETENTION_DAYS = 90

    /** Sanity clamp so a corrupt/bogus preference can never delete everything or keep forever. */
    const val MIN_RETENTION_DAYS = 7
    const val MAX_RETENTION_DAYS = 3650

    /** Clamps an arbitrary stored value into the supported [MIN_RETENTION_DAYS, MAX_RETENTION_DAYS] range. */
    fun applyRetentionDays(retentionDays: Int): Int =
        retentionDays.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)

    /** Rows with `timestampMs < cutoffMs` are pruned. */
    fun cutoffMs(nowMs: Long, retentionDays: Int): Long =
        nowMs - applyRetentionDays(retentionDays).toLong() * MILLIS_PER_DAY
}