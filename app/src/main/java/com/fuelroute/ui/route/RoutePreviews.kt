package com.fuelroute.ui.route

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteCost
import com.fuelroute.ui.components.Dimens
import com.fuelroute.ui.components.SectionCard
import com.fuelroute.ui.theme.FuelRouteTheme

private fun previewCost(total: Double, minutes: Double, toll: Double = 0.0, labels: List<String> = emptyList()) =
    RouteCost(
        route = Route(
            id = "p$total",
            routeLabels = labels,
            distanceMeters = 35_200.0,
            staticDurationSeconds = minutes * 60,
            durationSeconds = minutes * 60,
            segments = emptyList(),
            tollCost = toll,
        ),
        fuelLiters = 2.4,
        fuelCost = total - toll,
        tollCost = toll,
        totalCost = total,
        durationMinutes = minutes,
        distanceKm = 35.2,
        avgSpeedKmh = 58.0,
    )

/** The results hero as it appears for the recommended (cheapest) route. */
@Preview(name = "Route hero", locale = "iw", showBackground = true)
@Composable
private fun RouteHeroPreview() {
    val costs = listOf(
        previewCost(21.30, 44.0, labels = listOf("FUEL_EFFICIENT")),
        previewCost(29.80, 36.0, toll = 7.5, labels = listOf("DEFAULT_ROUTE")),
    )
    FuelRouteTheme(darkTheme = false) {
        SectionCard(modifier = Modifier.padding(Dimens.l)) {
            RouteTitleRow(cost = costs[0], index = 0, comparison = RouteHighlights.compare(costs, 0), recommended = true)
            RouteHeadline(cost = costs[0], comparison = RouteHighlights.compare(costs, 0), etaMs = 0L)
        }
    }
}
