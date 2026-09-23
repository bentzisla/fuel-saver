package com.fuelroute.domain.learning

import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.ObdSample
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.obd.LiveConsumption
import com.fuelroute.domain.obd.LiveConsumptionWindow
import com.fuelroute.domain.obd.SampleSanitizer

/**
 * Per-sample computation of the OBD loop, shared by the live engine and the offline rebuild of
 * learned data from stored samples (`LearnedDataRepair`), so both use exactly the same rules:
 *
 *  1. [SampleSanitizer] nulls physically impossible fields (sentinels, desync garbage).
 *  2. [FuelRateCalculator] estimates L/h (bounded by the engine maximum), then the vehicle's
 *     refuel [fuelRateCorrection] is applied.
 *  3. Estimator stickiness: once a better source (5E > MAF > speed-density) has answered in this
 *     session, a sample where it is missing does *not* silently switch estimator (the real
 *     phone DB showed MAF missing in 2-60% of samples mid-drive, and the speed-density fallback
 *     is far less accurate). The last good value is held for up to [holdMs]; after that the
 *     lower-priority estimate is used for trip totals/live display but is *not learned*, so a
 *     bin never mixes two estimators.
 *  4. [SpeedBinAggregator] adds learnable samples to the caller's *delta* bins.
 *  5. [LiveConsumptionWindow] produces the smoothed live L/100 km and L/h.
 *
 * Pure Kotlin, not thread-safe (owned by one loop).
 */
class ObdSampleProcessor(
    private val fuelType: FuelType,
    engineDisplacementL: Double?,
    fuelRateCorrection: Double = 1.0,
    private val learningEnabled: Boolean = true,
    private val holdMs: Long = DEFAULT_HOLD_MS,
    private val sessionGapMs: Long = SESSION_GAP_MS,
    private val consumption: LiveConsumptionWindow = LiveConsumptionWindow(),
) {
    private val displacementL: Double? = EngineDisplacement.normalizeLiters(engineDisplacementL)
    private val correction: Double = fuelRateCorrection.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val maxFuelRateLph: Double = SampleSanitizer.maxFuelRateLph(displacementL)
    private val aggregator = SpeedBinAggregator(maxFuelRateLph = maxFuelRateLph * correction)

    private var lastTimestampMs: Long? = null
    private var bestSource: FuelRateSource? = null
    private var lastBest: FuelRateEstimate? = null
    private var lastBestMs: Long = 0L

    data class Result(
        /** The sanitized sample (implausible fields nulled). */
        val sample: ObdSample,
        /** Corrected fuel rate in L/h used for trips/learning, or null when unknown. */
        val fuelRateLph: Double?,
        val source: FuelRateSource?,
        /** Wall-clock seconds since the previous processed sample (0 for the first one). */
        val dtSec: Double,
        /** True when this sample was added to the delta bins. */
        val learned: Boolean,
        val live: LiveConsumption,
    )

    fun process(
        raw: ObdSample,
        deltaBins: MutableMap<Int, SpeedBinStats>,
        vehicleId: String,
    ): Result {
        val sample = SampleSanitizer.sanitize(raw, displacementL)
        val now = sample.timestampMs
        val dtMs = lastTimestampMs?.let { now - it } ?: 0L
        val dtSec = (dtMs.coerceAtLeast(0L)) / 1000.0
        lastTimestampMs = now
        if (dtMs > sessionGapMs) {
            // A new drive (or a long outage): the car may answer differently now.
            bestSource = null
            lastBest = null
        }

        val (estimate, learnable) = chooseEstimate(sample, now)
        val rate = if (sample.rpm == 0.0) 0.0 else estimate?.litersPerHour?.times(correction)

        val learned = learningEnabled && learnable && rate != null &&
            aggregator.accumulate(deltaBins, sample, dtSec, rate, vehicleId)
        val live = consumption.add(now, sample.speedKmh, rate)
        return Result(sample, rate, estimate?.source, dtSec, learned, live)
    }

    private fun chooseEstimate(sample: ObdSample, now: Long): Pair<FuelRateEstimate?, Boolean> {
        val fresh = FuelRateCalculator.estimate(sample, fuelType, displacementL)
        val best = bestSource
        if (fresh != null && (best == null || fresh.source.ordinal <= best.ordinal)) {
            bestSource = fresh.source
            lastBest = fresh
            lastBestMs = now
            return fresh to fresh.learnable
        }
        val held = lastBest
        if (held != null && now - lastBestMs <= holdMs) {
            return held to held.learnable
        }
        return fresh to false
    }

    /** Call after a reconnect: timing and the live window restart, estimator memory is kept. */
    fun resetTiming() {
        lastTimestampMs = null
        consumption.reset()
    }

    companion object {
        /** How long the last value of the preferred estimator may stand in for a missing reply. */
        const val DEFAULT_HOLD_MS = 3_000L

        /** A gap this long starts a new session for estimator stickiness. */
        const val SESSION_GAP_MS = 60_000L
    }
}
