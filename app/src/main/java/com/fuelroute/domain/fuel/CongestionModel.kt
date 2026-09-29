package com.fuelroute.domain.fuel

import com.fuelroute.domain.model.CongestionLevel

data class CongestionInterval(
    val startM: Double,
    val endM: Double,
    val level: CongestionLevel,
)

/**
 * Metres of a queried range at each congestion level, de-overlapped (where intervals overlap the
 * more severe level wins) and with uncovered length counted as [normalM].
 */
data class CongestionLengths(
    val normalM: Double,
    val slowM: Double,
    val jamM: Double,
) {
    val totalM: Double get() = normalM + slowM + jamM

    fun metersAt(level: CongestionLevel): Double = when (level) {
        CongestionLevel.NORMAL -> normalM
        CongestionLevel.SLOW -> slowM
        CongestionLevel.TRAFFIC_JAM -> jamM
    }
}

object CongestionModel {

    /**
     * Time-equivalent speed factor over `[startM, endM]`: the *harmonic* length-weighted mean of
     * the level factors, `L / sum(len_i / f_i)`.
     *
     * Travel time over mixed traffic is `sum(len_i / (v_free * f_i))`, so the single factor that
     * reproduces that time is the harmonic mean, not the arithmetic one (which overstated the
     * speed: half NORMAL / half JAM is 0.4, not 0.625). Uncovered length counts as
     * [ModelConstants.NORMAL_FACTOR]; overlaps are resolved as in [levelLengths].
     */
    fun weightedSpeedFactor(
        intervals: List<CongestionInterval>,
        startM: Double,
        endM: Double,
    ): Double {
        if (!(startM < endM) || !startM.isFinite() || !endM.isFinite()) return 1.0
        return harmonicFactor(levelLengths(intervals, startM, endM))
    }

    /** Harmonic (time-equivalent) speed factor of [lengths] using the default level factors. */
    fun harmonicFactor(lengths: CongestionLengths): Double {
        val total = lengths.totalM
        if (!(total > 0.0)) return ModelConstants.NORMAL_FACTOR
        val timeUnits = CongestionLevel.entries.sumOf { lengths.metersAt(it) / it.speedFactor }
        return if (timeUnits > 0.0) total / timeUnits else ModelConstants.NORMAL_FACTOR
    }

    /**
     * Metres of `[startM, endM]` at each level. Intervals are clipped to the range; where two
     * intervals cover the same metre the more severe (lower factor) level wins, so coverage is
     * never double-counted, and any uncovered length is NORMAL (not jammed).
     */
    fun levelLengths(
        intervals: List<CongestionInterval>,
        startM: Double,
        endM: Double,
    ): CongestionLengths {
        if (!(startM < endM) || !startM.isFinite() || !endM.isFinite()) return CongestionLengths(0.0, 0.0, 0.0)
        val clipped = clip(intervals, startM, endM)
        if (clipped.isEmpty()) return CongestionLengths(endM - startM, 0.0, 0.0)

        val boundaries = sortedSetOf(startM, endM)
        for (c in clipped) {
            boundaries.add(c.startM)
            boundaries.add(c.endM)
        }

        val points = boundaries.toList()
        var normal = 0.0
        var slow = 0.0
        var jam = 0.0
        for (i in 0 until points.size - 1) {
            val pieceStart = points[i]
            val pieceEnd = points[i + 1]
            val pieceLength = pieceEnd - pieceStart
            if (pieceLength <= 0.0) continue
            val mid = (pieceStart + pieceEnd) / 2.0
            val level = clipped
                .filter { mid >= it.startM && mid < it.endM }
                .minByOrNull { it.level.speedFactor }
                ?.level
                ?: CongestionLevel.NORMAL
            when (level) {
                CongestionLevel.NORMAL -> normal += pieceLength
                CongestionLevel.SLOW -> slow += pieceLength
                CongestionLevel.TRAFFIC_JAM -> jam += pieceLength
            }
        }
        return CongestionLengths(normal, slow, jam)
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
