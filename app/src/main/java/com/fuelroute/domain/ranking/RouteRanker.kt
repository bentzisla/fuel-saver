package com.fuelroute.domain.ranking

import com.fuelroute.domain.fuel.ModelConstants
import com.fuelroute.domain.model.RouteCost

object RouteRanker {

    /** Upper bound for the value of time at use, NIS per minute. */
    const val MAX_VALUE_PER_MINUTE = 10.0

    /**
     * Orders [costs] by [RouteInsights.rankingCost] (total cost, with a pessimistic stand-in for an
     * unpriced toll) plus `minutes * valuePerMinute`.
     *
     * The model's error is ~10-20%, so a lead smaller than [RouteInsights.tieThreshold] is not a
     * real win: every route within that margin of the leader is treated as tied and the tied
     * routes are ordered fastest first, so the recommendation (rank 1) is the faster of routes
     * that cost about the same.
     */
    fun rank(
        costs: List<RouteCost>,
        valuePerMinute: Double = ModelConstants.DEFAULT_VALUE_PER_MINUTE,
    ): List<RouteCost> {
        if (costs.size < 2) return costs.toList()
        val vpm = sanitizeValuePerMinute(valuePerMinute)
        val sorted = costs.sortedBy { key(it, vpm) }
        val leaderKey = key(sorted.first(), vpm)
        val threshold = RouteInsights.tieThreshold(RouteInsights.rankingCost(sorted.first()))
        val (tied, rest) = sorted.partition { key(it, vpm) - leaderKey < threshold }
        return tied.sortedBy { it.durationMinutes } + rest
    }

    fun cheapest(
        costs: List<RouteCost>,
        valuePerMinute: Double = ModelConstants.DEFAULT_VALUE_PER_MINUTE,
    ): RouteCost? = rank(costs, valuePerMinute).firstOrNull()

    /**
     * [valuePerMinute] clamped to `0..MAX_VALUE_PER_MINUTE`; NaN falls back to the default. A
     * negative, NaN or infinite value would otherwise rank the slowest route first or make
     * every key NaN.
     */
    fun sanitizeValuePerMinute(valuePerMinute: Double): Double =
        if (valuePerMinute.isNaN()) {
            ModelConstants.DEFAULT_VALUE_PER_MINUTE
        } else {
            valuePerMinute.coerceIn(0.0, MAX_VALUE_PER_MINUTE)
        }

    private fun key(cost: RouteCost, valuePerMinute: Double): Double {
        val minutes = cost.durationMinutes.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        return RouteInsights.rankingCost(cost) + minutes * valuePerMinute
    }
}
