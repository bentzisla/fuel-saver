package com.fuelroute.domain.learning

import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.binIndexToSpeedKmh
import kotlin.math.abs

/**
 * Consumption curve produced from real OBD measurements, aggregated into
 * speed bins. Only bins that actually contain distance contribute points.
 */
class LearnedCurve(val bins: List<SpeedBinStats>) {

    data class BinPoint(
        val speedKmh: Double,
        val litersPer100Km: Double,
        val distanceKm: Double,
    )

    private val moving: List<BinPoint> = bins
        .mapNotNull { bin ->
            val l100 = bin.litersPer100Km ?: return@mapNotNull null
            if (bin.binIndex <= 0) return@mapNotNull null
            BinPoint(binIndexToSpeedKmh(bin.binIndex), l100, bin.distanceKm)
        }
        .sortedBy { it.speedKmh }

    val idleLitersPerHour: Double? =
        bins.firstOrNull { it.binIndex == 0 }?.litersPerHour

    val isEmpty: Boolean
        get() = moving.isEmpty()

    val totalDistanceKm: Double
        get() = bins.sumOf { it.distanceKm }

    fun litersPer100Km(speedKmh: Double): Double? {
        if (moving.isEmpty()) return null
        if (moving.size == 1) {
            val only = moving[0]
            return if (abs(only.speedKmh - speedKmh) <= MAX_EXTRAPOLATION_KMH) only.litersPer100Km else null
        }

        val first = moving.first()
        val last = moving.last()
        if (speedKmh < first.speedKmh - MAX_EXTRAPOLATION_KMH) return null
        if (speedKmh > last.speedKmh + MAX_EXTRAPOLATION_KMH) return null
        if (speedKmh <= first.speedKmh) return first.litersPer100Km
        if (speedKmh >= last.speedKmh) return last.litersPer100Km

        for (i in 0 until moving.size - 1) {
            val a = moving[i]
            val b = moving[i + 1]
            if (speedKmh <= b.speedKmh) {
                val span = b.speedKmh - a.speedKmh
                val t = if (span <= 0.0) 0.0 else (speedKmh - a.speedKmh) / span
                return a.litersPer100Km + t * (b.litersPer100Km - a.litersPer100Km)
            }
        }
        return last.litersPer100Km
    }

    /**
     * How many measured kilometers back the estimate at [speedKmh].
     * Tapers linearly to zero at the edge of the extrapolation window.
     */
    fun confidenceKm(speedKmh: Double): Double {
        if (moving.isEmpty()) return 0.0
        val nearest = moving.minByOrNull { abs(it.speedKmh - speedKmh) } ?: return 0.0
        val distance = abs(nearest.speedKmh - speedKmh)
        if (distance > MAX_EXTRAPOLATION_KMH) return 0.0
        return nearest.distanceKm * (1.0 - distance / MAX_EXTRAPOLATION_KMH)
    }

    companion object {
        const val MAX_EXTRAPOLATION_KMH = 12.5
    }
}