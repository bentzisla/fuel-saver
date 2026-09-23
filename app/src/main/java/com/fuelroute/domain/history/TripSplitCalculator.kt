package com.fuelroute.domain.history

/** One trip's measured totals, the pure input to the merge/split math. */
data class TripTotals(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceKm: Double,
    val fuelL: Double,
    val idleSeconds: Double,
    val maxSpeedKmh: Double = 0.0,
)

/**
 * Pure merge/split math for drives. No Android dependencies, so it is unit-testable on the JVM.
 *
 * **Approximation:** OBD samples are not stored per-trip, so a split apportions distance, fuel and
 * idle time proportionally to *elapsed time*. That assumes a roughly constant speed/fuel rate
 * across the drive; it is best-effort, not a true spatial split.
 */
object TripSplitCalculator {

    /** Combines [trips] into one long drive: min start, max end, summed distance/fuel/idle. */
    fun merge(trips: List<TripTotals>): TripTotals? {
        if (trips.isEmpty()) return null
        return TripTotals(
            startedAtMs = trips.minOf { it.startedAtMs },
            endedAtMs = trips.maxOf { it.endedAtMs },
            distanceKm = trips.sumOf { it.distanceKm },
            fuelL = trips.sumOf { it.fuelL },
            idleSeconds = trips.sumOf { it.idleSeconds },
            maxSpeedKmh = trips.maxOf { it.maxSpeedKmh },
        )
    }

    /** Average speed from [distanceKm] over the [startedAtMs, endedAtMs] window. */
    fun avgSpeedKmh(distanceKm: Double, startedAtMs: Long, endedAtMs: Long): Double {
        val hours = (endedAtMs - startedAtMs) / 3_600_000.0
        return if (hours <= 0.0) 0.0 else distanceKm / hours
    }

    /**
     * Splits [trip] at [splitAtMs] into (first, second), apportioning distance/fuel/idle by elapsed
     * time. Returns null when [splitAtMs] is not strictly inside the drive (or the drive has no
     * duration), so callers can reject an invalid picker position.
     */
    fun split(trip: TripTotals, splitAtMs: Long): Pair<TripTotals, TripTotals>? {
        val totalMs = trip.endedAtMs - trip.startedAtMs
        if (totalMs <= 0L) return null
        if (splitAtMs <= trip.startedAtMs || splitAtMs >= trip.endedAtMs) return null
        val firstFraction = (splitAtMs - trip.startedAtMs).toDouble() / totalMs
        val secondFraction = 1.0 - firstFraction
        return part(trip, trip.startedAtMs, splitAtMs, firstFraction) to
            part(trip, splitAtMs, trip.endedAtMs, secondFraction)
    }

    private fun part(trip: TripTotals, startMs: Long, endMs: Long, fraction: Double) = TripTotals(
        startedAtMs = startMs,
        endedAtMs = endMs,
        distanceKm = trip.distanceKm * fraction,
        fuelL = trip.fuelL * fraction,
        idleSeconds = trip.idleSeconds * fraction,
        maxSpeedKmh = trip.maxSpeedKmh,
    )
}
