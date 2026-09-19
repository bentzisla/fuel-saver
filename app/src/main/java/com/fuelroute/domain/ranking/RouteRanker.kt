package com.fuelroute.domain.ranking

import com.fuelroute.domain.model.RouteCost

object RouteRanker {

    /**
     * Orders routes by total cost. When [valuePerMinute] is greater than zero the
     * travel time is priced in as well, so a slightly cheaper but much slower
     * route does not automatically win.
     */
    fun rank(
        costs: List<RouteCost>,
        valuePerMinute: Double = 0.0,
    ): List<RouteCost> = costs.sortedBy { it.totalCost + it.durationMinutes * valuePerMinute }

    fun cheapest(
        costs: List<RouteCost>,
        valuePerMinute: Double = 0.0,
    ): RouteCost? = rank(costs, valuePerMinute).firstOrNull()
}