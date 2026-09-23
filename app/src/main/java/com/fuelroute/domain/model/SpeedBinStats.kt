package com.fuelroute.domain.model

const val SPEED_BIN_WIDTH_KMH: Double = 5.0

/**
 * Maps a speed to its bin. Bin 0 is reserved for idle (< 1 km/h); every moving bin is
 * offset by one so that crawling at 1-5 km/h lands in bin 1 rather than being folded into
 * the idle measurement.
 */
fun speedToBinIndex(speedKmh: Double): Int {
    if (speedKmh < 1.0) return 0
    return (speedKmh / SPEED_BIN_WIDTH_KMH).toInt() + 1
}

/** Representative speed for a bin: 0 for idle, otherwise the center of the 5 km/h window. */
fun binIndexToSpeedKmh(binIndex: Int): Double =
    if (binIndex == 0) 0.0
    else (binIndex - 1) * SPEED_BIN_WIDTH_KMH + SPEED_BIN_WIDTH_KMH / 2.0

data class SpeedBinStats(
    val vehicleId: String,
    val binIndex: Int,
    val distanceKm: Double = 0.0,
    val fuelL: Double = 0.0,
    val seconds: Double = 0.0,
    val samples: Int = 0,
) {
    val isIdleBin: Boolean
        get() = binIndex == 0

    val litersPer100Km: Double?
        get() = if (distanceKm > MIN_DISTANCE_KM) fuelL / distanceKm * 100.0 else null

    val litersPerHour: Double?
        get() = if (seconds > MIN_SECONDS) fuelL / (seconds / 3600.0) else null

    fun plus(
        distanceKm: Double,
        fuelL: Double,
        seconds: Double,
        samples: Int,
    ): SpeedBinStats = copy(
        distanceKm = this.distanceKm + distanceKm,
        fuelL = this.fuelL + fuelL,
        seconds = this.seconds + seconds,
        samples = this.samples + samples,
    )

    companion object {
        const val MIN_DISTANCE_KM = 0.0001
        const val MIN_SECONDS = 0.01
    }
}

/**
 * Adds per-bin [deltas] onto [base] totals (keyed by bin index), e.g. the last DB snapshot plus
 * the increments not yet flushed. Sorted by bin index.
 */
fun mergeSpeedBins(base: Collection<SpeedBinStats>, deltas: Collection<SpeedBinStats>): List<SpeedBinStats> {
    val merged = base.associateByTo(mutableMapOf()) { it.binIndex }
    for (delta in deltas) {
        val previous = merged[delta.binIndex]
        merged[delta.binIndex] = previous?.plus(delta.distanceKm, delta.fuelL, delta.seconds, delta.samples) ?: delta
    }
    return merged.values.sortedBy { it.binIndex }
}