package com.fuelroute.domain.learning

import com.fuelroute.domain.model.ObdSample
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.speedToBinIndex
import com.fuelroute.domain.obd.SampleSanitizer

/**
 * Accumulates OBD samples into 5 km/h speed bins. The sums are enough to derive
 * both liters/100 km per bin and idle liters/hour, incrementally, without ever
 * scanning raw samples again.
 *
 * The map passed to [accumulate] holds *increments* (deltas since the last flush), not
 * absolute totals: the database is the source of truth and deltas are added to it with an
 * additive upsert (see `SpeedBinDao.addDeltas`), so a concurrent reset/import is never
 * overwritten by a stale in-memory snapshot.
 *
 * Samples are excluded (return false) when they cannot describe steady-state consumption:
 * missing/implausible speed, a gap > [maxSampleGapSeconds], engine off, cold engine, or a
 * missing/negative/non-finite fuel rate or one above [maxFuelRateLph].
 */
class SpeedBinAggregator(
    private val minCoolantTempC: Double = 60.0,
    private val maxSampleGapSeconds: Double = 2.0,
    private val maxFuelRateLph: Double = SampleSanitizer.MAX_FUEL_RATE_LPH_DEFAULT,
) {

    fun accumulate(
        bins: MutableMap<Int, SpeedBinStats>,
        sample: ObdSample,
        dtSec: Double,
        fuelRateLph: Double?,
        vehicleId: String,
    ): Boolean {
        val speed = sample.speedKmh ?: return false
        if (!speed.isFinite() || speed < 0.0 || speed > MAX_SPEED_KMH) return false
        if (!dtSec.isFinite() || dtSec <= 0.0 || dtSec > maxSampleGapSeconds) return false
        if (!sample.engineRunning) return false

        val coolant = sample.coolantTempC
        if (coolant != null && coolant < minCoolantTempC) return false

        val rate = fuelRateLph ?: return false
        if (!rate.isFinite() || rate < 0.0 || rate > maxFuelRateLph) return false

        val dtHours = dtSec / 3600.0
        val distanceKm = speed * dtHours
        val fuelL = rate * dtHours
        val binIndex = speedToBinIndex(speed)

        val previous = bins[binIndex] ?: SpeedBinStats(vehicleId = vehicleId, binIndex = binIndex)
        bins[binIndex] = previous.plus(
            distanceKm = distanceKm,
            fuelL = fuelL,
            seconds = dtSec,
            samples = 1,
        )
        return true
    }

    companion object {
        const val MAX_SPEED_KMH = SampleSanitizer.MAX_SPEED_KMH
    }
}
