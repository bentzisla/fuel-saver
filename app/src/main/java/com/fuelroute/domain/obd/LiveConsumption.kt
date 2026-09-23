package com.fuelroute.domain.obd

/**
 * What the live displays (Stats dashboard, overlay, notification, Android Auto) show.
 *
 * @property litersPer100Km smoothed L/100 km, or `null` when the car is crawling/idle (below
 *   the moving threshold) or there is not yet enough distance in the window to be meaningful.
 *   Displays should show [litersPerHour] instead in that case, like a real trip computer.
 * @property litersPerHour smoothed fuel flow (L/h) over the same window, or `null` before the
 *   first usable fuel-rate sample.
 */
data class LiveConsumption(
    val litersPer100Km: Double?,
    val litersPerHour: Double?,
) {
    /** True when [litersPer100Km] is meaningful (moving); false = show L/h. */
    val isMoving: Boolean
        get() = litersPer100Km != null

    companion object {
        val EMPTY = LiveConsumption(null, null)
    }
}

/**
 * The single consumption number a compact display (overlay bubble, notification) shows:
 * L/100 km while moving, otherwise L/h — never a per-sample fuel/speed division.
 */
data class ConsumptionReadout(val value: Double, val perHour: Boolean) {
    companion object {
        /** Picks L/100 km when [litersPer100Km] is meaningful, else L/h; null when neither is known. */
        fun of(litersPer100Km: Double?, litersPerHour: Double?): ConsumptionReadout? = when {
            litersPer100Km != null && litersPer100Km.isFinite() -> ConsumptionReadout(litersPer100Km, perHour = false)
            litersPerHour != null && litersPerHour.isFinite() && litersPerHour >= 0.0 ->
                ConsumptionReadout(litersPerHour, perHour = true)
            else -> null
        }
    }
}

/**
 * Trip-computer style instantaneous consumption.
 *
 * Why not `fuelRate / speed * 100` per sample (the old behaviour): pulling away at 2 km/h with
 * 15-20 L/h gives 750-1000 L/100 km; speed is an integer km/h; and speed and fuel rate are
 * polled sequentially (up to ~9 ELM commands per loop), so they belong to different moments.
 *
 * Instead, like a real trip computer, this integrates fuel and distance over a sliding
 * time window ([windowMs], default 8 s) using the trapezoid rule between consecutive samples,
 * and reports `sum(fuel) / sum(distance)`. L/100 km is only reported while moving — entered
 * at [enterMovingKmh], left below [exitMovingKmh] (hysteresis, so it does not flicker around
 * the threshold) — and once the window holds at least [minWindowDistanceKm]. The displayed
 * value is capped at [maxDisplayL100] (the sums themselves are never capped).
 *
 * Pure Kotlin, not thread-safe (owned by the engine loop).
 */
class LiveConsumptionWindow(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val enterMovingKmh: Double = ENTER_MOVING_KMH,
    private val exitMovingKmh: Double = EXIT_MOVING_KMH,
    private val minWindowDistanceKm: Double = MIN_WINDOW_DISTANCE_KM,
    private val maxGapMs: Long = MAX_GAP_MS,
    private val maxDisplayL100: Double = MAX_DISPLAY_L100,
) {
    private class Segment(val endMs: Long, val seconds: Double, val distanceKm: Double, val fuelL: Double)

    private val segments = ArrayDeque<Segment>()
    private var lastMs: Long? = null
    private var lastSpeed: Double? = null
    private var lastRate: Double? = null
    private var moving = false

    /**
     * Adds one loop's sample. [speedKmh] and [fuelRateLph] may be null (PID missing/rejected):
     * the segment ending at this sample is then skipped (never guessed), but timing continues.
     */
    fun add(timestampMs: Long, speedKmh: Double?, fuelRateLph: Double?): LiveConsumption {
        val speed = speedKmh?.takeIf { it.isFinite() && it >= 0.0 }
        val rate = fuelRateLph?.takeIf { it.isFinite() && it >= 0.0 }

        val previousMs = lastMs
        if (previousMs != null) {
            val dtMs = timestampMs - previousMs
            if (dtMs <= 0L || dtMs > maxGapMs) {
                // Reconnect / stall: the old window no longer describes "now".
                segments.clear()
            } else {
                val prevSpeed = lastSpeed
                val prevRate = lastRate
                if (speed != null && rate != null && prevSpeed != null && prevRate != null) {
                    val hours = dtMs / 3_600_000.0
                    segments.addLast(
                        Segment(
                            endMs = timestampMs,
                            seconds = dtMs / 1000.0,
                            distanceKm = (prevSpeed + speed) / 2.0 * hours,
                            fuelL = (prevRate + rate) / 2.0 * hours,
                        ),
                    )
                }
            }
        }
        lastMs = timestampMs
        lastSpeed = speed
        lastRate = rate
        while (segments.isNotEmpty() && segments.first().endMs <= timestampMs - windowMs) {
            segments.removeFirst()
        }

        if (speed != null) {
            moving = if (moving) speed >= exitMovingKmh else speed >= enterMovingKmh
        }
        return current(rate)
    }

    private fun current(instantRate: Double?): LiveConsumption {
        val seconds = segments.sumOf { it.seconds }
        val fuel = segments.sumOf { it.fuelL }
        val distance = segments.sumOf { it.distanceKm }

        val lph = if (seconds >= MIN_WINDOW_SECONDS) fuel / (seconds / 3600.0) else instantRate
        val l100 = if (moving && distance >= minWindowDistanceKm && seconds >= MIN_WINDOW_SECONDS) {
            (fuel / distance * 100.0).coerceAtMost(maxDisplayL100)
        } else {
            null
        }
        return LiveConsumption(litersPer100Km = l100, litersPerHour = lph)
    }

    fun reset() {
        segments.clear()
        lastMs = null
        lastSpeed = null
        lastRate = null
        moving = false
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 8_000L
        const val ENTER_MOVING_KMH = 8.0
        const val EXIT_MOVING_KMH = 5.0

        /** 15 m: at 8 km/h that is ~7 s — a nearly full window right at the threshold. */
        const val MIN_WINDOW_DISTANCE_KM = 0.015
        const val MAX_GAP_MS = 5_000L
        const val MIN_WINDOW_SECONDS = 1.0

        /** Display ceiling, like the 30-99.9 cap of real trip computers. */
        const val MAX_DISPLAY_L100 = 99.9
    }
}
