package com.fuelroute.domain.fuel

import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.SpeedPoint

/**
 * Built-in shape of the consumption curve, expressed as multipliers over the
 * vehicle's rated combined consumption. The minimum sits around 70 km/h.
 */
object DefaultCurve {

    private val gasolineFactors: List<SpeedPoint> = listOf(
        SpeedPoint(10.0, 2.20),
        SpeedPoint(20.0, 1.65),
        SpeedPoint(30.0, 1.35),
        SpeedPoint(40.0, 1.15),
        SpeedPoint(50.0, 1.02),
        SpeedPoint(60.0, 0.94),
        SpeedPoint(70.0, 0.91),
        SpeedPoint(80.0, 0.93),
        SpeedPoint(90.0, 0.98),
        SpeedPoint(100.0, 1.06),
        SpeedPoint(110.0, 1.16),
        SpeedPoint(120.0, 1.28),
        SpeedPoint(130.0, 1.42),
    )

    private val hybridFactors: List<SpeedPoint> = listOf(
        SpeedPoint(10.0, 1.30),
        SpeedPoint(20.0, 1.15),
        SpeedPoint(30.0, 1.05),
        SpeedPoint(40.0, 0.98),
        SpeedPoint(50.0, 0.92),
        SpeedPoint(60.0, 0.90),
        SpeedPoint(70.0, 0.92),
        SpeedPoint(80.0, 0.97),
        SpeedPoint(90.0, 1.04),
        SpeedPoint(100.0, 1.12),
        SpeedPoint(110.0, 1.22),
        SpeedPoint(120.0, 1.34),
        SpeedPoint(130.0, 1.48),
    )

    /**
     * Plausibility band for a vehicle's rated combined consumption. Covers everything from a
     * tiny hybrid to a large SUV/van; a value outside it (corrupted storage, a mistyped profile,
     * a units mixup) is clamped rather than trusted, since it is the base every curve factor -
     * and therefore every route's fuel liters - multiplies.
     */
    const val MIN_RATED_L100 = 2.0
    const val MAX_RATED_L100 = 25.0

    fun forVehicle(
        ratedCombinedL100: Double,
        fuelType: FuelType = FuelType.GASOLINE,
    ): ConsumptionCurve {
        val rated = ratedCombinedL100
            .takeIf { it.isFinite() }
            ?.coerceIn(MIN_RATED_L100, MAX_RATED_L100)
            ?: MIN_RATED_L100
        val factors = if (fuelType == FuelType.HYBRID) hybridFactors else gasolineFactors
        return ConsumptionCurve(
            factors.map { SpeedPoint(it.speedKmh, rated * it.litersPer100Km) },
        )
    }
}