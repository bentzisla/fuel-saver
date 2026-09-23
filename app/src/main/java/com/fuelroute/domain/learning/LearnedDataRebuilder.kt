package com.fuelroute.domain.learning

import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.ObdSample
import com.fuelroute.domain.model.SpeedBinStats

/**
 * Recomputes learned data from the *raw* samples kept in `obd_sample` (speed, rpm, MAF, MAP,
 * IAT, coolant, 5E are stored as parsed, before any fuel model), using exactly the live rules
 * ([ObdSampleProcessor]). Used by the conservative repair of rows that were computed with a
 * wrong fuel model (e.g. an engine displacement typed in cc) or before the sanity filters.
 *
 * Feed samples in time order with [add]; read [bins] and [tripFuel] at the end. Pure Kotlin.
 *
 * @param excludedWindows time ranges whose samples must be ignored (e.g. demo drives recorded
 *   under the real vehicle id before simulated samples stopped being persisted).
 * @param tripWindows trips whose fuel should be re-integrated from the samples.
 */
class LearnedDataRebuilder(
    fuelType: FuelType,
    engineDisplacementL: Double?,
    fuelRateCorrection: Double,
    private val vehicleId: String,
    private val excludedWindows: List<LongRange> = emptyList(),
    tripWindows: List<TripWindow> = emptyList(),
) {
    data class TripWindow(val tripId: Long, val startedAtMs: Long, val endedAtMs: Long)

    /**
     * Fuel re-integrated over a trip window, how many seconds of samples backed it, and how many
     * of its samples had neither 5E nor MAF (i.e. were computed with the speed-density formula,
     * the path that used the wrong displacement).
     */
    data class TripFuel(val fuelL: Double, val coveredSeconds: Double, val speedDensitySamples: Int = 0)

    private val processor = ObdSampleProcessor(
        fuelType = fuelType,
        engineDisplacementL = engineDisplacementL,
        fuelRateCorrection = fuelRateCorrection,
        learningEnabled = true,
    )
    private val windows = tripWindows.sortedBy { it.startedAtMs }
    private val binMap = mutableMapOf<Int, SpeedBinStats>()
    private val tripSums = mutableMapOf<Long, TripFuel>()
    private var samplesUsed = 0

    val maxFuelRateLph: Double
        get() = processor.maxFuelRateLph

    val bins: Map<Int, SpeedBinStats>
        get() = binMap

    val tripFuel: Map<Long, TripFuel>
        get() = tripSums

    val sampleCount: Int
        get() = samplesUsed

    fun add(raw: ObdSample) {
        val t = raw.timestampMs
        if (excludedWindows.any { t in it }) return
        samplesUsed++
        val result = processor.process(raw, binMap, vehicleId)
        val dt = result.dtSec
        if (dt <= 0.0 || dt > MAX_TRIP_GAP_SECONDS) return
        val window = windows.firstOrNull { t in it.startedAtMs..it.endedAtMs } ?: return
        val rate = if (result.sample.engineRunning) result.fuelRateLph ?: return else 0.0
        val previous = tripSums[window.tripId] ?: TripFuel(0.0, 0.0)
        tripSums[window.tripId] = TripFuel(
            fuelL = previous.fuelL + rate * dt / 3600.0,
            coveredSeconds = previous.coveredSeconds + dt,
            speedDensitySamples = previous.speedDensitySamples + if (usedSpeedDensity(raw)) 1 else 0,
        )
    }

    /** True when the pre-fix engine would have used speed-density for [raw] (no 5E, no MAF). */
    private fun usedSpeedDensity(raw: ObdSample): Boolean =
        raw.fuelRateLph == null && (raw.mafGps ?: 0.0) <= 0.0 && raw.mapKpa != null && (raw.rpm ?: 0.0) > 0.0

    companion object {
        /** Stored samples are ~1 Hz; a longer gap is a disconnect and is not integrated. */
        const val MAX_TRIP_GAP_SECONDS = 5.0

        /** Minimum share of a trip's duration that samples must cover to re-estimate its fuel. */
        const val MIN_TRIP_COVERAGE = 0.5

        /**
         * Repaired fuel for a trip of [durationSeconds] from its re-integrated [fuel], scaled up
         * to the full duration (the uncovered gaps are assumed to burn at the covered average),
         * or null when fewer than [MIN_TRIP_COVERAGE] of the trip is covered.
         */
        fun repairedTripFuel(fuel: TripFuel?, durationSeconds: Double): Double? {
            if (fuel == null || durationSeconds <= 0.0) return null
            val coverage = fuel.coveredSeconds / durationSeconds
            if (coverage < MIN_TRIP_COVERAGE) return null
            return fuel.fuelL * (1.0 / coverage.coerceAtMost(1.0))
        }
    }
}

/**
 * Decides, conservatively, what a repair may change. Pure; the data layer applies the plan
 * in one transaction after archiving the old rows.
 *
 *  - Only bins that fail [LearnedDataPlausibility.isBinPlausible] are touched; plausible rows are
 *    never modified. A bad bin is replaced by its rebuilt value when the rebuild has a plausible
 *    one, otherwise it is deleted (its content is physically impossible, and it is archived).
 *  - Only trips that fail [LearnedDataPlausibility.isTripPlausible] are touched, and only when the
 *    samples cover enough of the trip ([LearnedDataRebuilder.repairedTripFuel]) *and* the result
 *    is plausible. Otherwise the trip is left as-is (the UI hides its L/100 km).
 */
object LearnedDataRepairPlanner {

    data class TripRow(
        val id: Long,
        val distanceKm: Double,
        val fuelL: Double,
        val durationSeconds: Double,
    )

    data class Plan(
        val replaceBins: List<SpeedBinStats>,
        val deleteBins: List<Int>,
        val tripFuel: Map<Long, Double>,
    ) {
        val isEmpty: Boolean
            get() = replaceBins.isEmpty() && deleteBins.isEmpty() && tripFuel.isEmpty()
    }

    fun badBins(stored: List<SpeedBinStats>, maxFuelRateLph: Double): List<SpeedBinStats> =
        stored.filter { !LearnedDataPlausibility.isBinPlausible(it, maxFuelRateLph) }

    fun badTrips(trips: List<TripRow>, maxFuelRateLph: Double): List<TripRow> =
        trips.filter { !LearnedDataPlausibility.isTripPlausible(it.distanceKm, it.fuelL, it.durationSeconds, maxFuelRateLph) }

    /**
     * @param displacementWasWrong the vehicle's stored displacement had to be normalized (e.g.
     *   1800 -> 1.8). Every speed-density sample it produced was then off by the same factor, so
     *   even bins/trips that still *look* plausible may be inflated (a few 1000x samples mixed
     *   into many good ones). In that case, additionally:
     *   - every stored bin that the rebuild covers is replaced by its rebuilt value;
     *   - every trip whose samples include speed-density samples is re-integrated.
     */
    fun plan(
        stored: List<SpeedBinStats>,
        rebuilt: Map<Int, SpeedBinStats>,
        trips: List<TripRow>,
        tripFuel: Map<Long, LearnedDataRebuilder.TripFuel>,
        maxFuelRateLph: Double,
        displacementWasWrong: Boolean = false,
    ): Plan {
        val replace = mutableListOf<SpeedBinStats>()
        val delete = mutableListOf<Int>()
        for (bin in stored) {
            val bad = !LearnedDataPlausibility.isBinPlausible(bin, maxFuelRateLph)
            val candidate = rebuilt[bin.binIndex]
                ?.takeIf { it.samples > 0 && LearnedDataPlausibility.isBinPlausible(it, maxFuelRateLph) }
            when {
                bad && candidate != null -> replace += candidate.copy(vehicleId = bin.vehicleId)
                bad -> delete += bin.binIndex
                displacementWasWrong && candidate != null -> replace += candidate.copy(vehicleId = bin.vehicleId)
            }
        }
        val fixedTrips = mutableMapOf<Long, Double>()
        for (trip in trips) {
            val sums = tripFuel[trip.id]
            val bad = !LearnedDataPlausibility.isTripPlausible(trip.distanceKm, trip.fuelL, trip.durationSeconds, maxFuelRateLph)
            val suspect = displacementWasWrong && (sums?.speedDensitySamples ?: 0) > 0
            if (!bad && !suspect) continue
            val fuel = LearnedDataRebuilder.repairedTripFuel(sums, trip.durationSeconds) ?: continue
            if (LearnedDataPlausibility.isTripPlausible(trip.distanceKm, fuel, trip.durationSeconds, maxFuelRateLph)) {
                fixedTrips[trip.id] = fuel
            }
        }
        return Plan(replace, delete, fixedTrips)
    }
}
