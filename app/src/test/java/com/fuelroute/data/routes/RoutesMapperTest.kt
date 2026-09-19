package com.fuelroute.data.routes

import com.fuelroute.domain.model.CongestionLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesMapperTest {

    @Test
    fun `parses duration toll and fallback congestion`() {
        val response = ComputeRoutesResponse(
            routes = listOf(
                RouteDto(
                    routeLabels = listOf("DEFAULT_ROUTE"),
                    description = "תל אביב",
                    distanceMeters = 12_000.0,
                    duration = "1500s",
                    staticDuration = "1200s",
                    polyline = PolylineDto(encodedPolyline = "abc"),
                    legs = listOf(
                        LegDto(
                            distanceMeters = 12_000.0,
                            duration = "1500s",
                            staticDuration = "1200s",
                            steps = listOf(
                                StepDto(distanceMeters = 12_000.0, staticDuration = "1200s"),
                            ),
                        ),
                    ),
                    travelAdvisory = TravelAdvisoryDto(
                        tollInfo = TollInfoDto(
                            estimatedPrice = listOf(
                                MoneyDto(currencyCode = "ILS", units = 12L, nanos = 500_000_000),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val route = RoutesMapper.toDomain(response).single()

        assertEquals(null, route.label)
        assertEquals(listOf("DEFAULT_ROUTE"), route.routeLabels)
        assertEquals(1500.0, route.durationSeconds, 1e-9)
        assertEquals(1200.0, route.staticDurationSeconds, 1e-9)
        assertEquals(12.5, route.tollCost!!, 1e-9)
        assertEquals(1, route.segments.size)
        val segment = route.segments.single()
        assertEquals(12_000.0, segment.distanceMeters, 1e-9)
        assertEquals(1200.0, segment.staticDurationSeconds, 1e-9)
        assertEquals(CongestionLevel.SLOW, segment.congestion)
        assertEquals(1500.0, segment.trafficDurationSeconds!!, 1e-9)
    }

    @Test
    fun `maps empty response to empty list`() {
        assertEquals(0, RoutesMapper.toDomain(ComputeRoutesResponse()).size)
    }
}