package com.fuelroute.domain.ranking

import com.fuelroute.domain.fuel.ModelConstants
import com.fuelroute.domain.model.RouteCost

object RouteRanker {

    fun rank(
        costs: List<RouteCost>,
        valuePerMinute: Double = ModelConstants.DEFAULT_VALUE_PER_MINUTE,
    ): List<RouteCost> = costs.sortedBy { it.totalCost + it.durationMinutes * valuePerMinute }

    fun cheapest(
        costs: List<RouteCost>,
        valuePerMinute: Double = ModelConstants.DEFAULT_VALUE_PER_MINUTE,
    ): RouteCost? = rank(costs, valuePerMinute).firstOrNull()
}