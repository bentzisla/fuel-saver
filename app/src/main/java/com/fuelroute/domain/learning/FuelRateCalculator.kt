package com.fuelroute.domain.learning

import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.ObdSample
import com.fuelroute.domain.obd.SampleSanitizer

/** Which OBD path produced a fuel-rate estimate. */
enum class FuelRateSource {
    /** PID 5E, engine fuel rate reported by the ECU. Most trustworthy. */
    DIRECT,

    /** PID 10 MAF / stoichiometric AFR — accurate for gasoline (closed-loop lambda ≈ 1). */
    MAF,

    /** MAP + RPM + IAT speed-density estimate — needs displacement, low accuracy. */
    SPEED_DENSITY,
}

/**
 * A fuel-rate estimate plus whether it may feed the learned curve.
 *
 * [learnable] is false for diesel estimates derived from air mass (MAF or speed-density):
 * a diesel runs lean (lambda ≈ 1.2 at full load up to 3-6 at light load/idle), so dividing the
 * air mass by the stoichiometric AFR (14.5) overestimates consumption by roughly that factor —
 * i.e. several-fold in normal driving. Without a lambda/equivalence-ratio signal (PID 44 is
 * rarely supported on diesels, and not polled) there is no verifiable correction, so these
 * values are still shown live (marked by [source]) but never learned into the curve.
 */
data class FuelRateEstimate(
    val litersPerHour: Double,
    val source: FuelRateSource,
    val learnable: Boolean,
)

/**
 * Derives fuel rate (L/h) from whatever the vehicle exposes, in order of
 * preference:
 *
 *  1. PID 5E  - direct fuel rate.
 *  2. PID 10  - MAF, via the air/fuel ratio and fuel density.
 *  3. MAP + RPM + IAT - speed-density estimate (needs engine displacement).
 *
 * Every result is bounded by [SampleSanitizer.maxFuelRateLph]: an estimate above the
 * plausible maximum for the engine (e.g. from a garbage MAF) is rejected (`null`), never
 * clamped.
 *
 * Diesel caveat: see [FuelRateEstimate.learnable]. The MAF/speed-density formulas below use
 * the stoichiometric AFR for diesel too, which is an *upper bound* on real diesel
 * consumption, not an estimate of it.
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
    ): Double? = estimate(sample, fuelType, engineDisplacementL)?.litersPerHour

    fun estimate(
        sample: ObdSample,
        fuelType: FuelType,
        engineDisplacementL: Double?,
    ): FuelRateEstimate? {
        // Defensive: a displacement typed in cc (1800) or out of range must never reach the
        // speed-density formula — it multiplies the fuel rate by 1000.
        val displacement = EngineDisplacement.normalizeLiters(engineDisplacementL)
        val max = SampleSanitizer.maxFuelRateLph(displacement)
        val diesel = fuelType == FuelType.DIESEL

        sample.fuelRateLph?.let {
            if (it.isFinite() && it >= 0.0) {
                return if (it <= max) FuelRateEstimate(it, FuelRateSource.DIRECT, learnable = true) else null
            }
        }

        val maf = sample.mafGps
        if (maf != null && maf.isFinite() && maf > 0.0) {
            val lph = mafToLph(maf, fuelType)
            return if (lph <= max) FuelRateEstimate(lph, FuelRateSource.MAF, learnable = !diesel) else null
        }

        val map = sample.mapKpa
        val rpm = sample.rpm
        if (map != null && rpm != null && rpm > 0.0 && displacement != null) {
            val lph = speedDensityLph(map, rpm, sample.intakeTempC ?: 20.0, displacement, fuelType)
            return if (lph.isFinite() && lph >= 0.0 && lph <= max) {
                FuelRateEstimate(lph, FuelRateSource.SPEED_DENSITY, learnable = !diesel)
            } else {
                null
            }
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
