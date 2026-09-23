package com.fuelroute.domain.fuel

import com.fuelroute.domain.model.CongestionLevel

data class CongestionInterval(
    val startM: Double,
    val endM: Double,
    val level: CongestionLevel,
)

object CongestionModel {

    /**
     * Length-weighted speed factor over `[startM, endM]`, treating every metre as covered.
     *
     * Intervals are clipped to the queried range and de-overlapped: where two intervals cover the
     * same metre the more severe (lower) factor wins, so coverage is never double-counted. Any
     * uncovered length is weighted [ModelConstants.NORMAL_FACTOR] instead of being treated as
     * jammed, which is what the old covered-length-only denominator effectively did.
     */
    fun weightedSpeedFactor(
        intervals: List<CongestionInterval>,
        startM: Double,
        endM: Double,
    ): Double {
        if (!(startM < endM) || !startM.isFinite() || !endM.isFinite()) return 1.0
        val length = endM - startM
        val clipped = clip(intervals, startM, endM)
        if (clipped.isEmpty()) return ModelConstants.NORMAL_FACTOR

        val boundaries = sortedSetOf(startM, endM)
        for (c in clipped) {
            boundaries.add(c.startM)
            boundaries.add(c.endM)
        }

        val points = boundaries.toList()
        var weighted = 0.0
        for (i in 0 until points.size - 1) {
            val pieceStart = points[i]
            val pieceEnd = points[i + 1]
            val pieceLength = pieceEnd - pieceStart
            if (pieceLength <= 0.0) continue
            val mid = (pieceStart + pieceEnd) / 2.0
            val factor = clipped
                .filter { mid >= it.startM && mid < it.endM }
                .minOfOrNull { it.level.speedFactor }
                ?: ModelConstants.NORMAL_FACTOR
            weighted += pieceLength * factor
        }
        return weighted / length
    }

    /**
     * The congestion level covering the most metres in `[startM, endM]`, or
     * [CongestionLevel.NORMAL] when nothing overlaps. Overlapping intervals of the same level are
     * merged before measuring, so their coverage is not double-counted.
     */
    fun dominantLevel(
        intervals: List<CongestionInterval>,
        startM: Double,
        endM: Double,
    ): CongestionLevel {
        if (!(startM < endM) || !startM.isFinite() || !endM.isFinite()) return CongestionLevel.NORMAL
        val clipped = clip(intervals, startM, endM)
        var bestLevel = CongestionLevel.NORMAL
        var bestLength = 0.0
        for ((level, group) in clipped.groupBy { it.level }) {
            val length = unionLength(group)
            if (length > bestLength) {
                bestLength = length
                bestLevel = level
            }
        }
        return bestLevel
    }

    private fun clip(
        intervals: List<CongestionInterval>,
        startM: Double,
        endM: Double,
    ): List<CongestionInterval> = intervals.mapNotNull { interval ->
        if (!interval.startM.isFinite() || !interval.endM.isFinite()) return@mapNotNull null
        val overlapStart = maxOf(startM, interval.startM)
        val overlapEnd = minOf(endM, interval.endM)
        if (overlapEnd <= overlapStart) null else CongestionInterval(overlapStart, overlapEnd, interval.level)
    }

    /** Total length covered by [intervals] with overlaps merged. */
    private fun unionLength(intervals: List<CongestionInterval>): Double {
        val sorted = intervals.sortedBy { it.startM }
        var total = 0.0
        var currentStart = Double.NaN
        var currentEnd = Double.NaN
        for (interval in sorted) {
            if (currentStart.isNaN()) {
                currentStart = interval.startM
                currentEnd = interval.endM
            } else if (interval.startM <= currentEnd) {
                currentEnd = maxOf(currentEnd, interval.endM)
            } else {
                total += currentEnd - currentStart
                currentStart = interval.startM
                currentEnd = interval.endM
            }
        }
        if (!currentStart.isNaN()) total += currentEnd - currentStart
        return total
    }
}