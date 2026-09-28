package com.fuelroute.domain.fuel

import kotlin.math.min

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
 * of it, and never enough to make the segment cheaper than free — so the credit is a
 * grade-dependent fraction ([descentRecoveryFraction]) of what climbing the same drop would have
 * cost: most of it on a shallow descent, little on a steep one where the brakes take the rest. Combining the
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
     * Recovery fraction used when a descent's grade is unknown (no distance given): the
     * conservative steep-descent value, so an unknown grade is never over-credited.
     */
    const val DESCENT_RECOVERY_FRACTION = 0.15

    /**
     * Highest fraction of a descent's potential energy that shows up as saved fuel. On a shallow
     * descent gravity only offsets part of the road load, so the engine simply works less and
     * most of the energy is recovered; bounded under 1 for part-load inefficiency and drivers
     * who keep their speed rather than coast.
     */
    const val MAX_DESCENT_RECOVERY = 0.8

    /**
     * Grade whose gravity force roughly equals a car's rolling + aero road load at typical
     * speeds (~1.5% at 50 km/h, ~3% at 90 km/h). Down a steeper slope the engine is already at
     * fuel cut-off; the excess energy goes into the brakes, so the fuel saved is capped at about
     * the flat-road work over the distance, i.e. a fraction `ROAD_LOAD_GRADE / grade`.
     */
    const val ROAD_LOAD_GRADE = 0.02

    /**
     * Fraction of a descent's potential energy recovered as reduced fuel burn at [grade]
     * (|rise| / run): [MAX_DESCENT_RECOVERY] on shallow descents, falling as
     * `ROAD_LOAD_GRADE / grade` once the slope exceeds the road load. [DESCENT_RECOVERY_FRACTION]
     * when the grade is unknown.
     */
    fun descentRecoveryFraction(grade: Double): Double {
        if (!grade.isFinite() || grade <= 0.0) return DESCENT_RECOVERY_FRACTION
        return min(MAX_DESCENT_RECOVERY, ROAD_LOAD_GRADE / grade)
    }

    /**
     * Extra liters of fuel for a net elevation change of [elevationDeltaM] meters (positive =
     * climb, negative = descent) over [distanceMeters] at [massKg] kg, for a fuel with
     * [energyDensityMjPerL] MJ/L burned at [engineEfficiency] tank-to-wheel efficiency.
     *
     * A climb always returns a positive number of liters. A descent returns a *negative* number
     * (a credit) of [descentRecoveryFraction] of the equivalent climb at the segment's grade
     * (the conservative [DESCENT_RECOVERY_FRACTION] when [distanceMeters] is unknown). It is up
     * to the caller to clamp the segment's total liters (base + grade) at 0 so this credit can
     * reduce a segment's cost but never make it cheaper than free.
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
        distanceMeters: Double = Double.NaN,
    ): Double {
        if (!elevationDeltaM.isFinite() || elevationDeltaM == 0.0) return 0.0
        if (!massKg.isFinite() || massKg <= 0.0) return 0.0
        if (!energyDensityMjPerL.isFinite() || energyDensityMjPerL <= 0.0) return 0.0
        if (!engineEfficiency.isFinite() || engineEfficiency <= 0.0) return 0.0

        val fuelEnergyPerLiterMj = energyDensityMjPerL * engineEfficiency
        val potentialEnergyMj = massKg * G * elevationDeltaM / 1_000_000.0
        val liters = potentialEnergyMj / fuelEnergyPerLiterMj
        if (liters >= 0.0) return liters

        val grade = if (distanceMeters.isFinite() && distanceMeters > 0.0) {
            -elevationDeltaM / distanceMeters
        } else {
            Double.NaN
        }
        return liters * descentRecoveryFraction(grade)
    }
}
