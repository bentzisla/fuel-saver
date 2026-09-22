package com.fuelroute.domain.history

/**
 * A manual post-drive cost entry the user records without OBD. Two modes are supported:
 * - **direct**: only [cost] is known;
 * - **consumption**: [distanceKm] + [litersPer100Km] are known and [cost] is derived from the
 *   current fuel price (so it is stored alongside the inputs).
 *
 * A null [distanceKm]/[litersPer100Km] means "direct mode".
 */
data class ManualCostInput(
    val cost: Double,
    val distanceKm: Double? = null,
    val litersPer100Km: Double? = null,
)

/**
 * Pure math for [ManualCostInput]. No Android dependencies, so it is unit-testable on the JVM.
 * All validators reject non-finite and non-positive values, which keeps a bad keystroke from
 * ever reaching the database.
 */
object ManualCostCalculator {

    /** Liters + cost derived from a distance and an average consumption at a given price. */
    data class Estimate(
        val liters: Double,
        val cost: Double,
    )

    fun isValidCost(cost: Double): Boolean = cost.isFinite() && cost > 0.0

    fun isValidDistance(distanceKm: Double): Boolean = distanceKm.isFinite() && distanceKm > 0.0

    fun isValidConsumption(litersPer100Km: Double): Boolean =
        litersPer100Km.isFinite() && litersPer100Km > 0.0

    fun isValidPrice(pricePerLiter: Double): Boolean =
        pricePerLiter.isFinite() && pricePerLiter > 0.0

    /**
     * `liters = distanceKm * litersPer100Km / 100`, `cost = liters * pricePerLiter`.
     * Returns null when any input is not usable.
     */
    fun estimate(distanceKm: Double, litersPer100Km: Double, pricePerLiter: Double): Estimate? {
        if (!isValidDistance(distanceKm) ||
            !isValidConsumption(litersPer100Km) ||
            !isValidPrice(pricePerLiter)
        ) {
            return null
        }
        val liters = distanceKm * litersPer100Km / 100.0
        return Estimate(liters = liters, cost = liters * pricePerLiter)
    }

    /** Liters implied by a direct [cost] at [pricePerLiter]. Null when either is not usable. */
    fun litersFromCost(cost: Double, pricePerLiter: Double): Double? =
        if (!isValidCost(cost) || !isValidPrice(pricePerLiter)) null else cost / pricePerLiter

    /** Builds a direct-mode input, or null when [cost] is not usable. */
    fun fromCost(cost: Double): ManualCostInput? =
        if (isValidCost(cost)) ManualCostInput(cost = cost) else null

    /** Builds a consumption-mode input, or null when the inputs/price are not usable. */
    fun fromConsumption(
        distanceKm: Double,
        litersPer100Km: Double,
        pricePerLiter: Double,
    ): ManualCostInput? {
        val estimate = estimate(distanceKm, litersPer100Km, pricePerLiter) ?: return null
        return ManualCostInput(
            cost = estimate.cost,
            distanceKm = distanceKm,
            litersPer100Km = litersPer100Km,
        )
    }
}
