package com.fuelroute.domain.learning

import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.binIndexToSpeedKmh
import com.fuelroute.domain.obd.SampleSanitizer

/**
 * Read-time guards for *already stored* aggregates (speed bins, trips). New data is filtered
 * at sample level ([SampleSanitizer], [FuelRateCalculator]); these rules make sure rows that
 * were written before those filters existed (or by a future bug) can never produce an absurd
 * learned curve or trip figure. They never modify data — see `LearnedDataRepair` for the
 * conservative, archived repair.
 */
object LearnedDataPlausibility {

    /** A moving bin needs at least this much distance before it becomes a curve point. */
    const val MIN_BIN_DISTANCE_KM = 0.5

    /** Ceiling for a moving bin's average L/100 km (at >= ~10 km/h). */
    const val MAX_BIN_L100 = 60.0

    /**
     * At crawl speeds L/100 km legitimately explodes (fuel/tiny distance); the bound there is
     * "average fuel rate of [MAX_CRAWL_LPH] at the bin speed", e.g. 80 L/100 km at 7.5 km/h.
     */
    const val MAX_CRAWL_LPH = 6.0

    /** Idle bin: no passenger car idles above this (A/C, cold engine included). */
    const val MAX_IDLE_LPH = 6.0

    /** A trip shorter than this has no meaningful L/100 km. */
    const val MIN_TRIP_DISTANCE_KM = 0.5

    /** Ceiling for a trip's average L/100 km (idle-heavy short trips included). */
    const val MAX_TRIP_L100 = 60.0

    /** Upper bound for a bin's L/100 km at [binIndex]. */
    fun maxBinL100(binIndex: Int): Double {
        val speed = binIndexToSpeedKmh(binIndex)
        if (speed <= 0.0) return Double.POSITIVE_INFINITY
        return maxOf(MAX_BIN_L100, MAX_CRAWL_LPH / speed * 100.0)
    }

    /**
     * False when [bin] cannot be real data: negative/non-finite sums, an average fuel rate above
     * the engine maximum, an idle rate above [MAX_IDLE_LPH], or (with enough distance to judge)
     * an average L/100 km above [maxBinL100].
     */
    fun isBinPlausible(bin: SpeedBinStats, maxFuelRateLph: Double = SampleSanitizer.MAX_FUEL_RATE_LPH_DEFAULT): Boolean {
        val sums = listOf(bin.distanceKm, bin.fuelL, bin.seconds)
        if (sums.any { !it.isFinite() || it < 0.0 } || bin.samples < 0) return false
        if (bin.seconds > 0.0) {
            val avgLph = bin.fuelL / (bin.seconds / 3600.0)
            val bound = if (bin.isIdleBin) MAX_IDLE_LPH else maxFuelRateLph
            if (avgLph > bound) return false
        } else if (bin.fuelL > 0.0) {
            return false
        }
        if (!bin.isIdleBin && bin.distanceKm >= MIN_BIN_DISTANCE_KM) {
            val l100 = bin.fuelL / bin.distanceKm * 100.0
            if (l100 > maxBinL100(bin.binIndex)) return false
        }
        return true
    }

    /** True when a moving bin is plausible *and* has enough distance to be a curve point. */
    fun isCurvePoint(bin: SpeedBinStats, maxFuelRateLph: Double = SampleSanitizer.MAX_FUEL_RATE_LPH_DEFAULT): Boolean =
        !bin.isIdleBin && bin.distanceKm >= MIN_BIN_DISTANCE_KM && isBinPlausible(bin, maxFuelRateLph)

    /**
     * False when a trip's fuel cannot be real: negative/non-finite, an average fuel rate over the
     * trip duration above [maxFuelRateLph], or (for trips of at least [MIN_TRIP_DISTANCE_KM]) an
     * average above [MAX_TRIP_L100].
     */
    fun isTripPlausible(
        distanceKm: Double,
        fuelL: Double,
        durationSeconds: Double,
        maxFuelRateLph: Double = SampleSanitizer.MAX_FUEL_RATE_LPH_DEFAULT,
    ): Boolean {
        if (!distanceKm.isFinite() || !fuelL.isFinite() || distanceKm < 0.0 || fuelL < 0.0) return false
        if (durationSeconds > 0.0 && fuelL / (durationSeconds / 3600.0) > maxFuelRateLph) return false
        if (distanceKm >= MIN_TRIP_DISTANCE_KM && fuelL / distanceKm * 100.0 > MAX_TRIP_L100) return false
        return true
    }

    /** Displayable trip L/100 km, or null when too short or implausible. */
    fun tripLitersPer100Km(distanceKm: Double, fuelL: Double, durationSeconds: Double): Double? {
        if (distanceKm < MIN_TRIP_DISTANCE_KM) return null
        if (!isTripPlausible(distanceKm, fuelL, durationSeconds)) return null
        return fuelL / distanceKm * 100.0
    }
}
