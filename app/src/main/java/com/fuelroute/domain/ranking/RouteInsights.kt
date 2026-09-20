package com.fuelroute.domain.ranking

import com.fuelroute.domain.model.RouteCost

/** Indices (into the ranked results) of the cheapest and fastest routes. */
data class RouteBadges(
    val cheapestIndex: Int?,
    val fastestIndex: Int?,
)

/**
 * Derives the cheapest/fastest badges. "Cheapest" is lowest total cost,
 * independent of the value-of-time weighting used for ranking; "fastest" is
 * lowest duration. Pure so it is unit-testable.
 */
object RouteInsights {

    fun badges(costs: List<RouteCost>): RouteBadges {
        if (costs.isEmpty()) return RouteBadges(cheapestIndex = null, fastestIndex = null)
        return RouteBadges(
            cheapestIndex = costs.indices.minByOrNull { costs[it].totalCost },
            fastestIndex = costs.indices.minByOrNull { costs[it].durationMinutes },
        )
    }
}