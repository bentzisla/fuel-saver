package com.fuelroute.domain.ranking

import com.fuelroute.domain.fuel.ConsumptionCurve
import com.fuelroute.domain.fuel.CurveBlender
import com.fuelroute.domain.learning.LearnedCurve
import com.fuelroute.domain.model.RouteCost

/** How much of a route's fuel estimate rests on the car's own OBD measurements. */
enum class RouteConfidenceLevel { LOW, MEDIUM, HIGH }

object RouteConfidence {

    /** Below this learned share the estimate is mostly the fallback curve. */
    const val LOW_MAX_SHARE = 0.25

    /** From this learned share on, the estimate is mostly measured. */
    const val HIGH_MIN_SHARE = 0.6

    /**
     * Distance-weighted learned weight along [cost]'s speed mix, in `0..1`: for each step, the
     * [CurveBlender] weight `km/(km+20)` the learned curve gets at that step's costed speed (0
     * where the learned value is missing or rejected as implausible against [fallback]), averaged
     * by step distance. Mirrors how [CurveBlender.blend] builds the effective curve, so it says
     * how much of *this* route's estimate is measured rather than assumed.
     */
    fun learnedShare(cost: RouteCost, learned: LearnedCurve?, fallback: ConsumptionCurve): Double {
        if (learned == null || learned.isEmpty) return 0.0
        var weighted = 0.0
        var distance = 0.0
        for (segment in cost.segments) {
            val km = segment.distanceKm.takeIf { it.isFinite() && it > 0.0 } ?: continue
            distance += km
            val speed = segment.effectiveSpeedKmh.takeIf { it.isFinite() } ?: continue
            val learnedValue = learned.litersPer100Km(speed) ?: continue
            if (!CurveBlender.usesLearnedValue(learnedValue, fallback.litersPer100Km(speed))) continue
            weighted += km * CurveBlender.weight(learned.confidenceKm(speed))
        }
        return if (distance > 0.0) (weighted / distance).coerceIn(0.0, 1.0) else 0.0
    }

    fun level(learnedShare: Double): RouteConfidenceLevel = when {
        !learnedShare.isFinite() || learnedShare < LOW_MAX_SHARE -> RouteConfidenceLevel.LOW
        learnedShare < HIGH_MIN_SHARE -> RouteConfidenceLevel.MEDIUM
        else -> RouteConfidenceLevel.HIGH
    }
}
