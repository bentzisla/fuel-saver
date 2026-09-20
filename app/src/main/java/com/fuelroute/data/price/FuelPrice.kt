package com.fuelroute.data.price

/** Fuel grades the app keeps a separate price for. */
object FuelGrades {
    const val GASOLINE_95 = "95"
    const val GASOLINE_98 = "98"
    const val DIESEL = "diesel"

    val ALL = listOf(GASOLINE_95, GASOLINE_98, DIESEL)
}

/**
 * A price per liter for one fuel [grade]. [manuallyPinned] means the value was
 * locked by the user and must not be overwritten by a full refuel.
 */
data class FuelPrice(
    val pricePerLiter: Double,
    val grade: String,
    val manuallyPinned: Boolean,
)

/**
 * Pure price rule: a full refuel may update the observed price only while it is
 * not manually pinned. Extracted from the repository so it can be unit-tested.
 */
object PricePinning {
    fun applyFullRefuel(current: FuelPrice, observedPricePerLiter: Double): FuelPrice =
        if (current.manuallyPinned) current
        else current.copy(pricePerLiter = observedPricePerLiter)
}