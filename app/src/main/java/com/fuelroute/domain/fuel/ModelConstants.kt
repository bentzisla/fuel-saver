package com.fuelroute.domain.fuel

import kotlinx.serialization.Serializable

/**
 * Named constants for the fuel model (see PLAN.md §4). Centralized so ranking and
 * cost math never repeat magic numbers.
 */
object ModelConstants {

    // Provenance: PLAN.md §4 congestion speed factors.
    const val NORMAL_FACTOR = 1.0
    const val SLOW_FACTOR = 0.55 // TODO(calibrate)
    const val JAM_FACTOR = 0.25 // TODO(calibrate)

    // Provenance: PLAN.md §4 stop-go idle weighting.
    const val STOP_GO_WEIGHT = 0.5 // TODO(calibrate)

    // Provenance: mirrors CurveBlender.CONFIDENCE_K_KM (km-in-bin confidence).
    const val CONFIDENCE_K_KM = 20.0

    // Provenance: mirrors LearnedCurve.MAX_EXTRAPOLATION_KMH.
    const val MAX_EXTRAPOLATION_KMH = 12.5

    // Provenance: PLAN.md §4 cold-start excess fuel.
    const val COLD_START_DEFAULT_L = 0.15 // TODO(calibrate)

    // Provenance: default idle fuel rate when OBD learning has no idle bin yet.
    const val IDLE_LPH_DEFAULT = 0.8 // TODO(calibrate)

    // Provenance: default price per liter and value-of-time defaults.
    const val DEFAULT_FUEL_PRICE = 7.0
    const val DEFAULT_VALUE_PER_MINUTE = 0.5

    /**
     * Plausibility band for a stored/manually entered fuel price per liter (regression: a
     * corrupted or mistyped price - e.g. off by a factor of 10, or a stray near-zero value -
     * would otherwise silently make every route look absurdly cheap regardless of the fuel
     * model). A price outside this band is treated as unusable and [DEFAULT_FUEL_PRICE] is used
     * instead, rather than trusting it.
     */
    const val MIN_FUEL_PRICE = 3.0
    const val MAX_FUEL_PRICE = 20.0

    /** [price] if it is finite and within [MIN_FUEL_PRICE]..[MAX_FUEL_PRICE], else [DEFAULT_FUEL_PRICE]. */
    fun plausibleFuelPrice(price: Double): Double =
        price.takeIf { it.isFinite() && it in MIN_FUEL_PRICE..MAX_FUEL_PRICE } ?: DEFAULT_FUEL_PRICE

    // Provenance: card 15 — 1 Hz car-screen display. EMA weight for a new sample;
    // lower = smoother/less flicker, higher = more responsive. ~0.35 settles in a
    // few seconds at 1 Hz without hiding real acceleration changes.
    const val LIVE_EMA_ALPHA = 0.35 // TODO(calibrate)
}

/**
 * Runtime calibration overrides for the fuel model. Every field is nullable: null means
 * "use [ModelConstants]". This is the value persisted by the debug calibration screen and
 * threaded into [FuelModel]; [ModelConstants] stays the single source of defaults.
 *
 * Scope note: this carries only the global/segment scalars the cost math reads. The
 * segment-level stop-go re-fit is intentionally not attempted (see card 18).
 */
@Serializable
data class FuelModelOverrides(
    val slowFactor: Double? = null,
    val jamFactor: Double? = null,
    val stopGoWeight: Double? = null,
    val coldStartDefaultL: Double? = null,
    val idleLphDefault: Double? = null,
    /**
     * Global multiplier applied to every predicted liter, fitted by `CalibrationFitter` over
     * linked drives. Null = 1.0 (no correction). This is the one value the "fit" action writes.
     */
    val fuelCorrection: Double? = null,
) {
    val effectiveSlowFactor: Double get() = slowFactor ?: ModelConstants.SLOW_FACTOR
    val effectiveJamFactor: Double get() = jamFactor ?: ModelConstants.JAM_FACTOR
    val effectiveStopGoWeight: Double get() = stopGoWeight ?: ModelConstants.STOP_GO_WEIGHT
    val effectiveColdStartDefaultL: Double
        get() = coldStartDefaultL ?: ModelConstants.COLD_START_DEFAULT_L
    val effectiveIdleLphDefault: Double get() = idleLphDefault ?: ModelConstants.IDLE_LPH_DEFAULT

    /**
     * Clamped to [CalibrationFitter.MIN_CORRECTION]..[CalibrationFitter.MAX_CORRECTION] as a
     * defense-in-depth guard: a fitted factor is already clamped by [CalibrationFitter], but a
     * manually typed override (debug calibration screen) is not, and an unclamped value far
     * below 1.0 can silently halve every predicted route cost.
     */
    val effectiveFuelCorrection: Double
        get() = (fuelCorrection ?: 1.0)
            .takeIf { it.isFinite() }
            ?.coerceIn(CalibrationFitter.MIN_CORRECTION, CalibrationFitter.MAX_CORRECTION)
            ?: 1.0

    companion object {
        val DEFAULT = FuelModelOverrides()
    }
}