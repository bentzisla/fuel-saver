package com.fuelroute.domain.fuel

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

    // Provenance: card 15 — 1 Hz car-screen display. EMA weight for a new sample;
    // lower = smoother/less flicker, higher = more responsive. ~0.35 settles in a
    // few seconds at 1 Hz without hiding real acceleration changes.
    const val LIVE_EMA_ALPHA = 0.35 // TODO(calibrate)
}