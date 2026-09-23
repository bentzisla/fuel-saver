package com.fuelroute.domain.obd

import com.fuelroute.domain.model.ObdSample

/**
 * Rejects physically impossible OBD values before they reach the fuel model, the trip totals
 * or the learned curve. A rejected field is set to `null` (as if the PID had answered
 * `NO DATA`) instead of being clamped: a clamped garbage value still looks like a measurement.
 *
 * The bounds are for passenger cars and deliberately generous — they catch sentinel values
 * (`FF`/`FF FF` from a failing ECU or a desynchronised reply, e.g. speed 255 km/h, MAF
 * 655.35 g/s, fuel rate 3276.75 L/h) and gross decoding errors, not merely unusual driving.
 *
 * Pure Kotlin, no Android dependencies.
 */
object SampleSanitizer {

    /** Top of the plausible speed range; `0xFF` (255) is a common "invalid" sentinel. */
    const val MAX_SPEED_KMH = 250.0

    /** `0xFFFF / 4` = 16383.75 is the RPM sentinel; no passenger car idles above this. */
    const val MAX_RPM = 10_000.0

    /** MAF bound when the displacement is unknown (a large V8 at WOT is ~400 g/s). */
    const val MAX_MAF_GPS_DEFAULT = 450.0

    /** MAF bound per litre of displacement: ~3x a naturally-aspirated engine at WOT (covers boost). */
    const val MAX_MAF_GPS_PER_LITER = 150.0

    /** Fuel-rate bound when displacement is unknown (≈ a 250 kW engine at full load). */
    const val MAX_FUEL_RATE_LPH_DEFAULT = 80.0

    /** Fuel-rate bound per litre of displacement (full load, boosted, generous). */
    const val MAX_FUEL_RATE_LPH_PER_LITER = 40.0

    /** Lower floor for the displacement-derived fuel-rate bound (small engines). */
    const val MIN_FUEL_RATE_BOUND_LPH = 40.0

    const val MIN_TEMP_C = -40.0
    const val MAX_COOLANT_C = 150.0
    const val MAX_INTAKE_C = 120.0

    /** Upper plausible fuel rate (L/h) for a vehicle with [engineDisplacementL] (null = unknown). */
    fun maxFuelRateLph(engineDisplacementL: Double?): Double {
        val displacement = engineDisplacementL?.takeIf { it.isFinite() && it > 0.0 }
            ?: return MAX_FUEL_RATE_LPH_DEFAULT
        return maxOf(MIN_FUEL_RATE_BOUND_LPH, displacement * MAX_FUEL_RATE_LPH_PER_LITER)
    }

    /** Upper plausible MAF (g/s) for a vehicle with [engineDisplacementL] (null = unknown). */
    fun maxMafGps(engineDisplacementL: Double?): Double {
        val displacement = engineDisplacementL?.takeIf { it.isFinite() && it > 0.0 }
            ?: return MAX_MAF_GPS_DEFAULT
        return maxOf(100.0, displacement * MAX_MAF_GPS_PER_LITER)
    }

    /** This value when it is a finite number inside [min]..[max], otherwise null. */
    private fun Double?.inRange(min: Double, max: Double): Double? =
        this?.takeIf { it.isFinite() && it >= min && it <= max }

    /**
     * Returns [sample] with every implausible field nulled out.
     *
     *  - speed outside 0..[MAX_SPEED_KMH] (catches the 255 sentinel), rpm outside 0..[MAX_RPM];
     *  - MAF / direct fuel rate that are negative, non-finite or above the vehicle bound;
     *  - MAF / direct fuel rate reported while RPM is exactly 0 (engine off: there is no airflow
     *    and no fuel; a non-zero value is stale or garbage and would otherwise leak into trips);
     *  - temperatures, load, MAP and fuel level outside their encodable/physical range.
     */
    fun sanitize(sample: ObdSample, engineDisplacementL: Double?): ObdSample {
        val rpm = sample.rpm.inRange(0.0, MAX_RPM)
        val engineOff = rpm != null && rpm == 0.0
        val maf = sample.mafGps.inRange(0.0, maxMafGps(engineDisplacementL))
            ?.takeUnless { engineOff }
        val fuelRate = sample.fuelRateLph.inRange(0.0, maxFuelRateLph(engineDisplacementL))
            ?.takeUnless { engineOff }
        return sample.copy(
            speedKmh = sample.speedKmh.inRange(0.0, MAX_SPEED_KMH),
            rpm = rpm,
            mafGps = maf,
            fuelRateLph = fuelRate,
            mapKpa = sample.mapKpa.inRange(0.0, 255.0),
            intakeTempC = sample.intakeTempC.inRange(MIN_TEMP_C, MAX_INTAKE_C),
            coolantTempC = sample.coolantTempC.inRange(MIN_TEMP_C, MAX_COOLANT_C),
            engineLoadPct = sample.engineLoadPct.inRange(0.0, 100.0),
            fuelLevelPct = sample.fuelLevelPct.inRange(0.0, 100.0),
        )
    }
}
