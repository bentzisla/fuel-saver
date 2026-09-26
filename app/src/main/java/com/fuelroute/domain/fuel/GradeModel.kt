package com.fuelroute.domain.fuel

/**
 * Physics-based fuel cost of net elevation change along a route segment (PLAN.md §4.4).
 *
 * The speed-vs-consumption curve alone has no idea a route climbs 500 m (e.g. Beit Shemesh,
 * ~300 m ASL, to Jerusalem, ~800 m ASL): potential energy for a ~1350 kg car over a 500 m climb
 * is `m*g*h ≈ 1350 * 9.80665 * 500 ≈ 6.62 MJ`, which at a typical ~25% tank-to-wheel efficiency
 * and gasoline's ~32 MJ/L needs `6.62 / (32 * 0.25) ≈ 0.83 L` of fuel — on top of whatever the
 * flat-road speed curve already charges. Leaving this out is why a real uphill drive can price
 * out far below what is physically possible.
 *
 * Charges the full potential-energy cost for a net climb. A net descent can only ever give
 * *partial* credit — engine braking and fuel cut-off recover some of the energy, but never all
 * of it, and never enough to make the segment cheaper than free — so the credit is capped at
 * [DESCENT_RECOVERY_FRACTION] of what climbing the same drop would have cost. Combining the
 * result with a segment's other liters and clamping the segment total at 0 (done by the caller,
 * [FuelModel]) is what keeps a downhill segment from going negative.
 */
object GradeModel {

    /** Standard gravity, m/s^2. */
    private const val G = 9.80665

    /** Gasoline energy density (LHV), MJ per liter. */
    const val GASOLINE_MJ_PER_L = 32.0

    /** Diesel is denser and slightly more energy-dense per liter. */
    const val DIESEL_MJ_PER_L = 35.8

    /** Rough tank-to-wheel efficiency for a spark-ignition engine at typical road load. */
    const val DEFAULT_ENGINE_EFFICIENCY = 0.25

    /** Sane default curb weight when a vehicle profile carries no mass (PLAN.md §4.4). */
    const val DEFAULT_VEHICLE_MASS_KG = 1350.0

    /**
     * Fraction of a descent's potential energy recovered as reduced fuel burn (engine braking /
     * fuel cut-off), never regeneration: bounded well under 1 so downhill is discounted, not free.
     */
    const val DESCENT_RECOVERY_FRACTION = 0.15

    /**
     * Extra liters of fuel for a net elevation change of [elevationDeltaM] meters (positive =
     * climb, negative = descent) at [massKg] kg, for a fuel with [energyDensityMjPerL] MJ/L
     * burned at [engineEfficiency] tank-to-wheel efficiency.
     *
     * A climb always returns a positive number of liters. A descent returns a *negative* number
     * (a credit), whose magnitude is capped at [DESCENT_RECOVERY_FRACTION] of the equivalent
     * climb — it is up to the caller to clamp the segment's total liters (base + grade) at 0 so
     * this credit can reduce a segment's cost but never make the trip cheaper than free.
     *
     * Non-finite or non-positive [massKg]/[energyDensityMjPerL]/[engineEfficiency], or a
     * non-finite [elevationDeltaM], degrade to 0 (no grade term) rather than throwing or
     * producing NaN/Infinity.
     */
    fun extraLiters(
        elevationDeltaM: Double,
        massKg: Double = DEFAULT_VEHICLE_MASS_KG,
        energyDensityMjPerL: Double = GASOLINE_MJ_PER_L,
        engineEfficiency: Double = DEFAULT_ENGINE_EFFICIENCY,
    ): Double {
        if (!elevationDeltaM.isFinite() || elevationDeltaM == 0.0) return 0.0
        if (!massKg.isFinite() || massKg <= 0.0) return 0.0
        if (!energyDensityMjPerL.isFinite() || energyDensityMjPerL <= 0.0) return 0.0
        if (!engineEfficiency.isFinite() || engineEfficiency <= 0.0) return 0.0

        val fuelEnergyPerLiterMj = energyDensityMjPerL * engineEfficiency
        val potentialEnergyMj = massKg * G * elevationDeltaM / 1_000_000.0
        val liters = potentialEnergyMj / fuelEnergyPerLiterMj

        return if (liters >= 0.0) liters else liters * DESCENT_RECOVERY_FRACTION
    }
}
