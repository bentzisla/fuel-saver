package com.fuelroute.domain.fuel

import com.fuelroute.domain.model.CongestionLevel

data class CongestionInterval(
    val startM: Double,
    val endM: Double,
    val level: CongestionLevel,
)

object CongestionModel {

    fun weightedSpeedFactor(
        intervals: List<CongestionInterval>,
        startM: Double,
        endM: Double,
    ): Double {
        if (startM >= endM) return 1.0
        var totalLength = 0.0
        var weighted = 0.0
        for (interval in intervals) {
            val overlapStart = maxOf(startM, interval.startM)
            val overlapEnd = minOf(endM, interval.endM)
            if (overlapEnd <= overlapStart) continue
            val length = overlapEnd - overlapStart
            totalLength += length
            weighted += length * interval.level.speedFactor
        }
        if (totalLength <= 0.0) return 1.0
        return weighted / totalLength
    }

    fun dominantLevel(
        intervals: List<CongestionInterval>,
        startM: Double,
        endM: Double,
    ): CongestionLevel {
        if (startM >= endM) return CongestionLevel.NORMAL
        val overlapByLevel = linkedMapOf<CongestionLevel, Double>()
        for (interval in intervals) {
            val overlapStart = maxOf(startM, interval.startM)
            val overlapEnd = minOf(endM, interval.endM)
            if (overlapEnd <= overlapStart) continue
            val length = overlapEnd - overlapStart
            overlapByLevel[interval.level] = (overlapByLevel[interval.level] ?: 0.0) + length
        }
        return overlapByLevel.maxByOrNull { it.value }?.key ?: CongestionLevel.NORMAL
    }
}