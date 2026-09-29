package com.fuelroute.ui.route

import com.fuelroute.domain.model.RouteCost
import com.fuelroute.domain.ranking.RouteInsights

/**
 * What the results UI says about one route relative to the others. Pure (no Android) so the
 * wording decisions — "saves X vs fastest", "+Y min", "about the same cost" — are unit-testable.
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
    /**
     * This route and the cheapest one (or, for the cheapest, some other route) differ by less
     * than the model's error margin ([RouteInsights.tieThreshold]): show "about the same cost"
     * rather than a firm winner.
     */
    val costTie: Boolean = false,
    /** The route has a toll whose price is unknown, so its shown cost excludes it. */
    val excludesToll: Boolean = false,
) {
    /** Only one alternative exists, or this route is both cheapest and fastest. */
    val noTradeOff: Boolean get() = isCheapest && isFastest && !costTie
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
        val isCheapest = badges.cheapestIndex == index ||
            RouteInsights.rankingCost(cost) - RouteInsights.rankingCost(cheapest) < MONEY_EPSILON
        val costTie = if (isCheapest) {
            badges.cheapestIsTie
        } else {
            RouteInsights.isSimilarCost(cost, cheapest)
        }
        return RouteComparison(
            isCheapest = isCheapest,
            isFastest = badges.fastestIndex == index ||
                cost.durationMinutes - fastest.durationMinutes < MINUTES_EPSILON,
            savingsVsFastest = (fastest.totalCost - cost.totalCost).coerceAtLeast(0.0),
            extraMinutesVsFastest = (cost.durationMinutes - fastest.durationMinutes).coerceAtLeast(0.0),
            premiumVsCheapest = (cost.totalCost - cheapest.totalCost).coerceAtLeast(0.0),
            minutesSavedVsCheapest = (cheapest.durationMinutes - cost.durationMinutes).coerceAtLeast(0.0),
            costTie = costTie,
            excludesToll = cost.route.tollUnknown,
        )
    }
}
