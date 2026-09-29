package com.fuelroute.domain.ranking

import com.fuelroute.domain.model.RouteCost
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Indices (into the ranked results) of the cheapest and fastest routes. */
data class RouteBadges(
    val cheapestIndex: Int?,
    val fastestIndex: Int?,
    /**
     * Another route costs about the same as the cheapest one (within [RouteInsights.tieThreshold]),
     * so "cheapest" is not a meaningful claim given the model's ~10-20% error.
     */
    val cheapestIsTie: Boolean = false,
)

/**
 * Derives the cheapest/fastest badges and the cost comparisons the ranking and the result cards
 * share. "Cheapest" is lowest [rankingCost], independent of the value-of-time weighting used for
 * ranking; "fastest" is lowest duration. Pure so it is unit-testable.
 */
object RouteInsights {

    /**
     * Stand-in toll for ranking a route whose toll exists but has no price from Google (e.g.
     * Route 6 or the Carmel tunnels without an estimate). Deliberately pessimistic - a typical
     * Israeli toll segment - so an unpriced toll road does not beat a toll-free route on fuel
     * alone. Never shown as a price: the card says the cost excludes the toll instead.
     */
    const val UNKNOWN_TOLL_ESTIMATE_NIS = 15.0

    /** Cost differences below this many NIS are "about the same". */
    const val TIE_ABSOLUTE_NIS = 1.0

    /** ...or below this fraction of the cheaper route's cost, whichever is larger. */
    const val TIE_FRACTION = 0.05

    /** [RouteCost.totalCost] plus [UNKNOWN_TOLL_ESTIMATE_NIS] when the route's toll is unpriced. */
    fun rankingCost(cost: RouteCost): Double {
        val base = cost.totalCost.takeIf { it.isFinite() } ?: Double.MAX_VALUE
        return if (cost.route.tollUnknown) base + UNKNOWN_TOLL_ESTIMATE_NIS else base
    }

    /** Smallest cost difference that is more than model noise, for a route costing [referenceCost]. */
    fun tieThreshold(referenceCost: Double): Double {
        val reference = referenceCost.takeIf { it.isFinite() }?.let { abs(it) } ?: 0.0
        return max(TIE_ABSOLUTE_NIS, TIE_FRACTION * reference)
    }

    /** True when [a] and [b] cost about the same (by [rankingCost]). */
    fun isSimilarCost(a: RouteCost, b: RouteCost): Boolean {
        val costA = rankingCost(a)
        val costB = rankingCost(b)
        return abs(costA - costB) < tieThreshold(min(costA, costB))
    }

    fun badges(costs: List<RouteCost>): RouteBadges {
        if (costs.isEmpty()) return RouteBadges(cheapestIndex = null, fastestIndex = null)
        val cheapestIndex = costs.indices.minByOrNull { rankingCost(costs[it]) }
        val cheapestIsTie = cheapestIndex != null && costs.indices.any { index ->
            index != cheapestIndex && isSimilarCost(costs[index], costs[cheapestIndex])
        }
        return RouteBadges(
            cheapestIndex = cheapestIndex,
            fastestIndex = costs.indices.minByOrNull { costs[it].durationMinutes },
            cheapestIsTie = cheapestIsTie,
        )
    }
}
