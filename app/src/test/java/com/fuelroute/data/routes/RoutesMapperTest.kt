package com.fuelroute.data.routes

import com.fuelroute.domain.model.CongestionLevel
import com.fuelroute.domain.model.TrafficResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesMapperTest {

    @Test
    fun `parses duration toll and no-traffic resolution`() {
        val response = ComputeRoutesResponse(
            routes = listOf(
                RouteDto(
                    routeLabels = listOf("DEFAULT_ROUTE"),
                    description = "תל אביב",
                    distanceMeters = 12_000,
                    duration = "1500s",
                    staticDuration = "1200s",
                    polyline = PolylineDto(encodedPolyline = "abc"),
                    legs = listOf(
                        LegDto(
                            distanceMeters = 12_000,
                            duration = "1500s",
                            staticDuration = "1200s",
                            steps = listOf(
                                StepDto(distanceMeters = 12_000, staticDuration = "1200s"),
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

        assertNull(route.label)
        assertEquals(listOf("DEFAULT_ROUTE"), route.routeLabels)
        assertEquals(12_000.0, route.distanceMeters, 1e-9)
        assertEquals(1500.0, route.durationSeconds, 1e-9)
        assertEquals(1200.0, route.staticDurationSeconds, 1e-9)
        assertEquals(12.5, route.tollCost!!, 1e-9)
        assertFalse(route.tollUnknown)
        assertEquals(TrafficResolution.NONE, route.trafficResolution)
        val segment = route.segments.single()
        assertEquals(12_000.0, segment.distanceMeters, 1e-9)
        assertEquals(1200.0, segment.staticDurationSeconds, 1e-9)
        assertEquals(CongestionLevel.NORMAL, segment.congestion)
        assertEquals(1.0, segment.congestionFactor, 1e-9)
    }

    @Test
    fun `missing toll info means the route is toll free`() {
        val route = RoutesMapper.toDomain(routeResponse(travelAdvisory = null)).single()

        assertEquals(0.0, route.tollCost!!, 1e-9)
        assertFalse(route.tollUnknown)
    }

    @Test
    fun `empty toll price list marks the toll unknown`() {
        val route = RoutesMapper.toDomain(
            routeResponse(travelAdvisory = TravelAdvisoryDto(tollInfo = TollInfoDto(estimatedPrice = emptyList()))),
        ).single()

        assertNull(route.tollCost)
        assertTrue(route.tollUnknown)
    }

    @Test
    fun `leg toll info is summed when the route has none`() {
        val response = ComputeRoutesResponse(
            routes = listOf(
                RouteDto(
                    distanceMeters = 2_000,
                    duration = "200s",
                    staticDuration = "200s",
                    legs = listOf(
                        legWithToll(distanceMeters = 1_000, units = 4L, nanos = 0),
                        legWithToll(distanceMeters = 1_000, units = 8L, nanos = 250_000_000),
                    ),
                ),
            ),
        )

        val route = RoutesMapper.toDomain(response).single()

        assertFalse(route.tollUnknown)
        assertEquals(12.25, route.tollCost!!, 1e-9)
    }

    @Test
    fun `leg toll info without a price marks the toll unknown`() {
        val response = ComputeRoutesResponse(
            routes = listOf(
                RouteDto(
                    distanceMeters = 1_000,
                    duration = "100s",
                    staticDuration = "100s",
                    legs = listOf(
                        LegDto(
                            distanceMeters = 1_000,
                            duration = "100s",
                            staticDuration = "100s",
                            steps = listOf(StepDto(distanceMeters = 1_000, staticDuration = "100s")),
                            travelAdvisory = TravelAdvisoryDto(tollInfo = TollInfoDto()),
                        ),
                    ),
                ),
            ),
        )

        val route = RoutesMapper.toDomain(response).single()

        assertNull(route.tollCost)
        assertTrue(route.tollUnknown)
    }

    @Test
    fun `route toll info takes precedence over leg tolls`() {
        val response = ComputeRoutesResponse(
            routes = listOf(
                RouteDto(
                    distanceMeters = 1_000,
                    duration = "100s",
                    staticDuration = "100s",
                    travelAdvisory = TravelAdvisoryDto(
                        tollInfo = TollInfoDto(
                            estimatedPrice = listOf(MoneyDto(units = 20L)),
                        ),
                    ),
                    legs = listOf(legWithToll(distanceMeters = 1_000, units = 4L, nanos = 0)),
                ),
            ),
        )

        val route = RoutesMapper.toDomain(response).single()

        assertFalse(route.tollUnknown)
        assertEquals(20.0, route.tollCost!!, 1e-9)
    }

    @Test
    fun `route level intervals fall back and set ROUTE_AVERAGE`() {
        val encoded = encode(listOf(0.0 to 0.0, 0.0 to 0.01, 0.0 to 0.02))
        val response = ComputeRoutesResponse(
            routes = listOf(
                RouteDto(
                    distanceMeters = 2_224,
                    duration = "200s",
                    staticDuration = "180s",
                    polyline = PolylineDto(encodedPolyline = encoded),
                    travelAdvisory = TravelAdvisoryDto(
                        speedReadingIntervals = listOf(
                            SpeedReadingIntervalDto(0, 1, "SLOW"),
                            SpeedReadingIntervalDto(1, 2, "NORMAL"),
                        ),
                    ),
                    legs = listOf(
                        LegDto(
                            distanceMeters = 2_224,
                            duration = "200s",
                            staticDuration = "180s",
                            // No leg polyline / no leg advisory -> route-level fallback.
                            steps = listOf(
                                StepDto(distanceMeters = 1_112, staticDuration = "90s"),
                                StepDto(distanceMeters = 1_112, staticDuration = "90s"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val route = RoutesMapper.toDomain(response).single()

        assertEquals(TrafficResolution.ROUTE_AVERAGE, route.trafficResolution)
        assertEquals(2, route.segments.size)
        assertEquals(0.55, route.segments[0].congestionFactor, 0.02)
        assertEquals(CongestionLevel.SLOW, route.segments[0].congestion)
        assertEquals(1.0, route.segments[1].congestionFactor, 0.02)
        assertEquals(CongestionLevel.NORMAL, route.segments[1].congestion)
    }

    @Test
    fun `leg intervals are per segment`() {
        val legPolyline = encode(listOf(0.0 to 0.0, 0.0 to 0.01, 0.0 to 0.02))
        val response = ComputeRoutesResponse(
            routes = listOf(
                RouteDto(
                    distanceMeters = 2_224,
                    duration = "200s",
                    staticDuration = "180s",
                    polyline = PolylineDto(encodedPolyline = legPolyline),
                    legs = listOf(
                        LegDto(
                            distanceMeters = 2_224,
                            duration = "200s",
                            staticDuration = "180s",
                            polyline = PolylineDto(encodedPolyline = legPolyline),
                            travelAdvisory = TravelAdvisoryDto(
                                speedReadingIntervals = listOf(
                                    SpeedReadingIntervalDto(0, 1, "TRAFFIC_JAM"),
                                    SpeedReadingIntervalDto(1, 2, "NORMAL"),
                                ),
                            ),
                            steps = listOf(
                                StepDto(distanceMeters = 1_112, staticDuration = "90s"),
                                StepDto(distanceMeters = 1_112, staticDuration = "90s"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val route = RoutesMapper.toDomain(response).single()

        assertEquals(TrafficResolution.PER_SEGMENT, route.trafficResolution)
        assertEquals(CongestionLevel.TRAFFIC_JAM, route.segments[0].congestion)
        assertEquals(0.25, route.segments[0].congestionFactor, 0.02)
        assertEquals(CongestionLevel.NORMAL, route.segments[1].congestion)
    }

    @Test
    fun `missing step static duration falls back to a distance share of the leg`() {
        val response = ComputeRoutesResponse(
            routes = listOf(
                RouteDto(
                    distanceMeters = 3_000,
                    duration = "300s",
                    staticDuration = "240s",
                    legs = listOf(
                        LegDto(
                            distanceMeters = 3_000,
                            duration = "300s",
                            staticDuration = "240s",
                            steps = listOf(
                                StepDto(distanceMeters = 1_000),
                                StepDto(distanceMeters = 2_000),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val route = RoutesMapper.toDomain(response).single()

        // 240 s shared 1:2 by distance -> 80 s and 160 s instead of 0 s.
        assertEquals(80.0, route.segments[0].staticDurationSeconds, 1e-9)
        assertEquals(160.0, route.segments[1].staticDurationSeconds, 1e-9)
    }

    @Test
    fun `malformed duration throws`() {
        val response = ComputeRoutesResponse(
            routes = listOf(RouteDto(distanceMeters = 100, duration = "abc")),
        )

        assertThrows(RoutesParseException::class.java) {
            RoutesMapper.toDomain(response)
        }
    }

    @Test
    fun `parseDurationSeconds rejects a missing suffix`() {
        assertThrows(RoutesParseException::class.java) { "1500".parseDurationSeconds() }
        assertEquals(1500.0, "1500s".parseDurationSeconds(), 1e-9)
    }

    @Test
    fun `maps empty response to empty list`() {
        assertEquals(0, RoutesMapper.toDomain(ComputeRoutesResponse()).size)
    }

    private fun legWithToll(distanceMeters: Int, units: Long, nanos: Int) = LegDto(
        distanceMeters = distanceMeters,
        duration = "100s",
        staticDuration = "100s",
        steps = listOf(StepDto(distanceMeters = distanceMeters, staticDuration = "100s")),
        travelAdvisory = TravelAdvisoryDto(
            tollInfo = TollInfoDto(
                estimatedPrice = listOf(MoneyDto(currencyCode = "ILS", units = units, nanos = nanos)),
            ),
        ),
    )

    private fun routeResponse(travelAdvisory: TravelAdvisoryDto?) = ComputeRoutesResponse(
        routes = listOf(
            RouteDto(
                distanceMeters = 1_000,
                duration = "100s",
                staticDuration = "100s",
                legs = listOf(
                    LegDto(
                        distanceMeters = 1_000,
                        duration = "100s",
                        staticDuration = "100s",
                        steps = listOf(StepDto(distanceMeters = 1_000, staticDuration = "100s")),
                    ),
                ),
                travelAdvisory = travelAdvisory,
            ),
        ),
    )

    private fun encode(points: List<Pair<Double, Double>>): String {
        val sb = StringBuilder()
        var prevLat = 0
        var prevLng = 0
        for ((lat, lng) in points) {
            val latE5 = Math.round(lat * 1e5).toInt()
            val lngE5 = Math.round(lng * 1e5).toInt()
            sb.append(encodeValue(latE5 - prevLat))
            sb.append(encodeValue(lngE5 - prevLng))
            prevLat = latE5
            prevLng = lngE5
        }
        return sb.toString()
    }

    private fun encodeValue(value: Int): String {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        val sb = StringBuilder()
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toChar())
        return sb.toString()
    }
}
