package com.fuelroute.domain.fuel

import com.fuelroute.domain.model.SpeedPoint

/**
 * Piecewise-linear "fuel consumption vs speed" curve, in liters per 100 km.
 * Values outside the defined range are clamped to the nearest endpoint.
 */
class ConsumptionCurve(points: List<SpeedPoint>) {

    private val points: List<SpeedPoint> = points.sortedBy { it.speedKmh }

    init {
        require(this.points.size >= 2) { "A consumption curve needs at least two points" }
    }

    val minSpeedKmh: Double get() = points.first().speedKmh

    val maxSpeedKmh: Double get() = points.last().speedKmh

    fun litersPer100Km(speedKmh: Double): Double {
        val last = points.last()
        if (speedKmh >= last.speedKmh) return last.litersPer100Km

        val v = speedKmh.coerceAtLeast(points.first().speedKmh)

        var lo = 0
        var hi = points.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (points[mid].speedKmh <= v) lo = mid else hi = mid
        }

        val a = points[lo]
        val b = points[lo + 1]
        val span = b.speedKmh - a.speedKmh
        if (span <= 0.0) return a.litersPer100Km
        val t = (v - a.speedKmh) / span
        return a.litersPer100Km + t * (b.litersPer100Km - a.litersPer100Km)
    }

    fun samples(): List<SpeedPoint> = points

    companion object {
        fun of(vararg points: SpeedPoint): ConsumptionCurve = ConsumptionCurve(points.toList())
    }
}