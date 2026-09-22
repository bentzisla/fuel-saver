package com.fuelroute.domain.fuel

/**
 * Pure helpers for turning an OBD fuel-level percentage into a rough driving range.
 * Kept in `domain/` so it is unit-testable on the JVM.
 */
object RangeEstimator {

    /**
     * Estimated remaining range in km:
     * `tankCapacityL * levelPct / 100` is the fuel left, divided by consumption
     * (L/100km) and multiplied back by 100. Returns null when the inputs cannot
     * produce a meaningful estimate (missing/invalid tank or consumption).
     */
    fun remainingRangeKm(
        tankCapacityL: Double?,
        levelPct: Double?,
        litersPer100Km: Double?,
    ): Double? {
        if (tankCapacityL == null || levelPct == null || litersPer100Km == null) return null
        if (!tankCapacityL.isFinite() || !levelPct.isFinite() || !litersPer100Km.isFinite()) return null
        if (tankCapacityL <= 0.0 || litersPer100Km <= 0.0) return null
        val level = levelPct.coerceIn(0.0, 100.0)
        return tankCapacityL * level / 100.0 / litersPer100Km * 100.0
    }

    /**
     * True when a pumped amount plausibly exceeds the vehicle's tank. A null/zero
     * capacity means "unknown", so no warning is produced.
     */
    fun exceedsTankCapacity(liters: Double?, tankCapacityL: Double?): Boolean {
        if (liters == null || tankCapacityL == null || tankCapacityL <= 0.0) return false
        if (!liters.isFinite() || !tankCapacityL.isFinite()) return false
        return liters > tankCapacityL
    }
}