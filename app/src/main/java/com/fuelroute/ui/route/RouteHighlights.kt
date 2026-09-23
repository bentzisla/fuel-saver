package com.fuelroute.ui.route

import com.fuelroute.domain.model.RouteCost
import com.fuelroute.domain.ranking.RouteInsights

/**
 * What the results UI says about one route relative to the others. Pure (no Android) so the
 * wording decisions — "saves X vs fastest", "+Y min" — are unit-testable.
 */
data class RouteComparison(
    val isCheapest: Boolean,
    val isFastest: Boolean,
    /** Money this route saves compared with the fastest route (0 when it is the fastest). */
    val savingsVsFastest: Double,
    /** Extra minutes this route takes compared with the fastest route (0 when fastest). */
    val extraMinutesVsFastest: Double,
    /** Extra money this route costs compared with the cheapest route (0 when cheapest). */
    val premiumVsCheapest: Double,
    /** Minutes this route saves compared with the cheapest route (0 when not faster). */
    val minutesSavedVsCheapest: Double,
) {
    /** Only one alternative exists, or this route is both cheapest and fastest. */
    val noTradeOff: Boolean get() = isCheapest && isFastest
}

object RouteHighlights {

    /** Below this a money difference is shown as "no difference". */
    const val MONEY_EPSILON = 0.005

    /** Below this a time difference is shown as "no difference". */
    const val MINUTES_EPSILON = 0.5

    fun compare(costs: List<RouteCost>, index: Int): RouteComparison? {
        val cost = costs.getOrNull(index) ?: return null
        val badges = RouteInsights.badges(costs)
        val cheapest = badges.cheapestIndex?.let { costs[it] } ?: cost
        val fastest = badges.fastestIndex?.let { costs[it] } ?: cost
        return RouteComparison(
            isCheapest = badges.cheapestIndex == index ||
                cost.totalCost - cheapest.totalCost < MONEY_EPSILON,
            isFastest = badges.fastestIndex == index ||
                cost.durationMinutes - fastest.durationMinutes < MINUTES_EPSILON,
            savingsVsFastest = (fastest.totalCost - cost.totalCost).coerceAtLeast(0.0),
            extraMinutesVsFastest = (cost.durationMinutes - fastest.durationMinutes).coerceAtLeast(0.0),
            premiumVsCheapest = (cost.totalCost - cheapest.totalCost).coerceAtLeast(0.0),
            minutesSavedVsCheapest = (cheapest.durationMinutes - cost.durationMinutes).coerceAtLeast(0.0),
        )
    }
}
