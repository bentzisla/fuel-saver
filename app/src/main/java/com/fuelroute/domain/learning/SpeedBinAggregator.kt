package com.fuelroute.domain.learning

import com.fuelroute.domain.model.ObdSample
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.speedToBinIndex

/**
 * Accumulates OBD samples into 5 km/h speed bins. The sums are enough to derive
 * both liters/100 km per bin and idle liters/hour, incrementally, without ever
 * scanning raw samples again.
 */
class SpeedBinAggregator(
    private val minCoolantTempC: Double = 60.0,
    private val maxSampleGapSeconds: Double = 2.0,
) {

    fun accumulate(
        bins: MutableMap<Int, SpeedBinStats>,
        sample: ObdSample,
        dtSec: Double,
        fuelRateLph: Double?,
        vehicleId: String,
    ): Boolean {
        val speed = sample.speedKmh ?: return false
        if (speed < 0.0 || speed > MAX_SPEED_KMH) return false
        if (dtSec <= 0.0 || dtSec > maxSampleGapSeconds) return false
        if (!sample.engineRunning) return false

        val coolant = sample.coolantTempC
        if (coolant != null && coolant < minCoolantTempC) return false

        val rate = fuelRateLph ?: return false
        if (rate < 0.0) return false

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
        const val MAX_SPEED_KMH = 250.0
    }
}