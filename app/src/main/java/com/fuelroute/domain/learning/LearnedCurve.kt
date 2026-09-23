package com.fuelroute.domain.learning

import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.binIndexToSpeedKmh
import com.fuelroute.domain.obd.SampleSanitizer
import kotlin.math.abs

/**
 * Consumption curve produced from real OBD measurements, aggregated into
 * speed bins.
 *
 * Read-time guard ([LearnedDataPlausibility]): only moving bins with at least
 * [LearnedDataPlausibility.MIN_BIN_DISTANCE_KM] of distance *and* plausible sums become curve
 * points, and the idle rate is only reported when plausible. A tiny-distance bin (fuel / almost
 * no distance) or a bin corrupted by bad data stored before the sample filters existed (e.g.
 * 52.6 L over 8.5 km) therefore can never bend the curve; [excludedBins] lists them so the UI
 * or a repair can react. [bins] still exposes every stored row unchanged.
 */
class LearnedCurve(
    val bins: List<SpeedBinStats>,
    maxFuelRateLph: Double = SampleSanitizer.MAX_FUEL_RATE_LPH_DEFAULT,
) {

    data class BinPoint(
        val speedKmh: Double,
        val litersPer100Km: Double,
        val distanceKm: Double,
    )

    /** Moving bins that were rejected by the plausibility guard (not tiny-distance ones). */
    val excludedBins: List<SpeedBinStats> =
        bins.filter { !LearnedDataPlausibility.isBinPlausible(it, maxFuelRateLph) }

    private val moving: List<BinPoint> = bins
        .mapNotNull { bin ->
            if (!LearnedDataPlausibility.isCurvePoint(bin, maxFuelRateLph)) return@mapNotNull null
            val l100 = bin.litersPer100Km ?: return@mapNotNull null
            BinPoint(binIndexToSpeedKmh(bin.binIndex), l100, bin.distanceKm)
        }
        .sortedBy { it.speedKmh }

    /** The curve points actually used (plausible, enough distance), sorted by speed. */
    val points: List<BinPoint>
        get() = moving

    val idleLitersPerHour: Double? =
        bins.firstOrNull { it.binIndex == 0 }
            ?.takeIf { LearnedDataPlausibility.isBinPlausible(it, maxFuelRateLph) }
            ?.litersPerHour

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