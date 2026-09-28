package com.fuelroute.data.history

import com.fuelroute.data.db.TripEntity
import com.fuelroute.domain.history.ManualCostCalculator
import com.fuelroute.domain.learning.LearnedDataPlausibility

/**
 * The one read-time view of a stored trip, shared by History and the Drive tab's trip list so the
 * two never disagree. The user's manual post-drive entry wins over the OBD measurement, but only
 * here: the raw OBD columns are never overwritten.
 */

/**
 * A trip whose stored OBD fuel is physically impossible (recorded before the 0.7 OBD-data fixes and
 * not repairable from raw samples) shows no actual liters/cost instead of an absurd one. Checked on
 * the raw OBD columns, not on the manual overrides.
 */
internal fun TripEntity.isObdFuelPlausible(): Boolean =
    LearnedDataPlausibility.isTripPlausible(distanceKm, fuelL, (endedAtMs - startedAtMs) / 1000.0)

internal fun TripEntity.effectiveDistanceKm(): Double = manualDistanceKm ?: distanceKm

/** Liters implied by the manual entry, from distance+consumption or from cost/price. */
internal fun TripEntity.manualLiters(): Double? {
    val distance = manualDistanceKm
    val consumption = manualLitersPer100Km
    if (distance != null && consumption != null) {
        // Liters do not depend on the price, so this also works when no price was recorded.
        if (ManualCostCalculator.isValidDistance(distance) && ManualCostCalculator.isValidConsumption(consumption)) {
            return distance * consumption / 100.0
        }
    }
    val cost = manualCost ?: return null
    return ManualCostCalculator.litersFromCost(cost, pricePerLiterAtTrip)
}

internal fun TripEntity.effectiveFuelL(): Double = manualLiters() ?: fuelL

/** Display cost: manual entry, else OBD cost when plausible, else null. */
internal fun TripEntity.displayActualCost(): Double? =
    manualCost ?: actualCost.takeIf { isObdFuelPlausible() }

/** Display liters: manual entry, else OBD fuel when plausible, else null. */
internal fun TripEntity.displayFuelL(): Double? =
    manualLiters() ?: fuelL.takeIf { isObdFuelPlausible() }

/**
 * Average speed: the recorded one, or distance over the drive window when the user corrected the
 * distance by hand (the recorded average was computed from the OBD distance).
 */
internal fun TripEntity.effectiveAvgSpeedKmh(): Double? {
    if (manualDistanceKm == null) return avgSpeedKmh.takeIf { it > 0.0 }
    val hours = (endedAtMs - startedAtMs) / 3_600_000.0
    if (hours <= 0.0) return null
    return effectiveDistanceKm() / hours
}
