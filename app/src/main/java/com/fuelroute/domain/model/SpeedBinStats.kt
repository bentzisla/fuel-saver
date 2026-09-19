package com.fuelroute.domain.model

const val SPEED_BIN_WIDTH_KMH: Double = 5.0

fun speedToBinIndex(speedKmh: Double): Int {
    if (speedKmh <= 0.0) return 0
    return (speedKmh / SPEED_BIN_WIDTH_KMH).toInt()
}

fun binIndexToSpeedKmh(binIndex: Int): Double =
    binIndex * SPEED_BIN_WIDTH_KMH + SPEED_BIN_WIDTH_KMH / 2.0

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