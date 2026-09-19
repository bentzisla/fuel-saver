package com.fuelroute.domain.learning

import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.ObdSample

/**
 * Derives fuel rate (L/h) from whatever the vehicle exposes, in order of
 * preference:
 *
 *  1. PID 5E  - direct fuel rate.
 *  2. PID 10  - MAF, via the air/fuel ratio and fuel density.
 *  3. MAP + RPM + IAT - speed-density estimate (needs engine displacement).
 */
object FuelRateCalculator {

    const val AFR_GASOLINE = 14.7
    const val AFR_DIESEL = 14.5
    const val DENSITY_GASOLINE_G_PER_L = 745.0
    const val DENSITY_DIESEL_G_PER_L = 832.0

    private const val GAS_CONSTANT = 287.05 // J / (kg * K)
    private const val VOLUMETRIC_EFFICIENCY = 0.85

    fun fuelRateLph(
        sample: ObdSample,
        fuelType: FuelType,
        engineDisplacementL: Double?,
    ): Double? {
        sample.fuelRateLph?.let { if (it >= 0.0) return it }

        val maf = sample.mafGps
        if (maf != null && maf > 0.0) return mafToLph(maf, fuelType)

        val map = sample.mapKpa
        val rpm = sample.rpm
        if (map != null && rpm != null && rpm > 0.0 && engineDisplacementL != null) {
            return speedDensityLph(map, rpm, sample.intakeTempC ?: 20.0, engineDisplacementL, fuelType)
        }

        return null
    }

    fun mafToLph(mafGps: Double, fuelType: FuelType): Double {
        val afr = if (fuelType == FuelType.DIESEL) AFR_DIESEL else AFR_GASOLINE
        val density = if (fuelType == FuelType.DIESEL) DENSITY_DIESEL_G_PER_L else DENSITY_GASOLINE_G_PER_L
        return (mafGps / afr) * 3600.0 / density
    }

    fun speedDensityLph(
        mapKpa: Double,
        rpm: Double,
        intakeTempC: Double,
        engineDisplacementL: Double,
        fuelType: FuelType,
    ): Double {
        val pressurePa = mapKpa * 1000.0
        val tempKelvin = intakeTempC + 273.15
        val displacementM3 = engineDisplacementL / 1000.0
        val intakeStrokesPerSecond = rpm / 60.0 / 2.0

        val volumeFlowM3PerSecond = displacementM3 * intakeStrokesPerSecond * VOLUMETRIC_EFFICIENCY
        val airKgPerSecond = pressurePa * volumeFlowM3PerSecond / (GAS_CONSTANT * tempKelvin)

        val afr = if (fuelType == FuelType.DIESEL) AFR_DIESEL else AFR_GASOLINE
        val densityKgPerL = (if (fuelType == FuelType.DIESEL) DENSITY_DIESEL_G_PER_L else DENSITY_GASOLINE_G_PER_L) / 1000.0

        val fuelKgPerSecond = airKgPerSecond / afr
        return fuelKgPerSecond * 3600.0 / densityKgPerL
    }
}