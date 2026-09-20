package com.fuelroute.domain.history

/**
 * The fuel cost of a closed trip, snapshotted once from the trip's own fuel and the
 * price that was current at close. [actualCost] is a stored `val`, so a later fuel-price
 * change can never retroactively move history.
 */
data class TripCost(
    val fuelL: Double,
    val pricePerLiterAtTrip: Double,
) {
    val actualCost: Double = fuelL * pricePerLiterAtTrip
}