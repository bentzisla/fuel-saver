package com.fuelroute.domain.obd

import com.fuelroute.domain.fuel.ModelConstants

/**
 * Pure math behind the Android Auto live dashboard (card 15). Kept free of Android so it is
 * fully unit-testable on the JVM.
 *
 * Design choices:
 *  - Idle (speed < [MIN_MOVING_SPEED_KMH]) has no meaningful L/100 km or ₪/km, so those return
 *    `null`; idle is shown as ₪/hour and L/hour instead.
 *  - Any missing/non-finite input collapses to 0.0 for the ₪/hour headline rather than `NaN`,
 *    so the car screen can never render "NaN".
 */
object LiveCostCalculator {

    /** Below this speed we treat the car as stationary (idle). */
    const val MIN_MOVING_SPEED_KMH = 1.0

    /** ₪/hour = L/h × ₪/L. This is the headline number the user asked for. */
    fun costPerHour(fuelRateLph: Double?, pricePerLiter: Double): Double {
        val rate = fuelRateLph?.takeIf { it.isFinite() && it > 0.0 } ?: return 0.0
        val price = pricePerLiter.takeIf { it.isFinite() } ?: return 0.0
        return rate * price
    }

    /** ₪/km while moving; `null` at idle or whenever rate/speed are unusable. */
    fun costPerKm(fuelRateLph: Double?, speedKmh: Double?, pricePerLiter: Double): Double? {
        val rate = litersPerHour(fuelRateLph) ?: return null
        val speed = speedKmh?.takeIf { it.isFinite() && it >= MIN_MOVING_SPEED_KMH } ?: return null
        val price = pricePerLiter.takeIf { it.isFinite() } ?: return null
        return rate / speed * price
    }

    /** L/100 km while moving; `null` at idle or when there is no usable rate/speed. */
    fun litersPer100Km(fuelRateLph: Double?, speedKmh: Double?): Double? {
        val rate = litersPerHour(fuelRateLph) ?: return null
        val speed = speedKmh?.takeIf { it.isFinite() && it >= MIN_MOVING_SPEED_KMH } ?: return null
        return rate / speed * 100.0
    }

    /** ₪ spent so far this trip = liters burned × price. Never NaN. */
    fun tripCost(fuelL: Double, pricePerLiter: Double): Double {
        if (!fuelL.isFinite() || !pricePerLiter.isFinite()) return 0.0
        return fuelL.coerceAtLeast(0.0) * pricePerLiter
    }

    private fun litersPerHour(fuelRateLph: Double?): Double? =
        fuelRateLph?.takeIf { it.isFinite() && it >= 0.0 }

    /**
     * True when [next] differs from [previous], i.e. the car host needs an `invalidate()`.
     * The screen builds [LiveDashboardValues] from display-rounded numbers, so equal values
     * produce no refresh and the ~1 Hz throttle stays stable.
     */
    fun shouldInvalidate(previous: LiveDashboardValues?, next: LiveDashboardValues): Boolean =
        previous != next
}

/**
 * Exactly the values rendered on the car screen, already rounded to display precision.
 * Equality of two snapshots is the "has anything visible changed?" predicate.
 */
data class LiveDashboardValues(
    val costPerHour: Double,
    val consumption: Double?,
    val moving: Boolean,
    val tripCost: Double,
    val speedKmh: Double?,
    val tripDistanceKm: Double,
)

/**
 * Exponential moving average smoother so the 1 Hz car display does not flicker.
 * A null/non-finite sample keeps the previous value; the first valid sample initializes it.
 */
class EmaSmoother(
    private val alpha: Double = ModelConstants.LIVE_EMA_ALPHA,
) {
    private var value: Double? = null

    /** The last smoothed value, or `null` before the first valid sample. */
    val current: Double?
        get() = value

    fun update(sample: Double?): Double? {
        if (sample == null || !sample.isFinite()) return value
        val previous = value
        value = if (previous == null) sample else previous + alpha * (sample - previous)
        return value
    }

    fun reset() {
        value = null
    }
}