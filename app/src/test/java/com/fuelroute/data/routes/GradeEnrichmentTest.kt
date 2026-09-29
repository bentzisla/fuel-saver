package com.fuelroute.data.routes

import com.fuelroute.testutil.Fixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GradeEnrichedRoutesRepository.enrichAll] over the recorded 3-alternatives response: sample
 * count scales with route length, and a failure on any alternative drops the grade term for all.
 */
class GradeEnrichmentTest {

    private val routes = RoutesMapper.toDomain(
        Fixtures.json.decodeFromString<ComputeRoutesResponse>(Fixtures.read("fixtures/routes/3-alternatives.json")),
    )

    /** Linear 0 -> 100 m climb along every polyline; optionally fails for one polyline. */
    private class FakeElevation(private val failFor: String? = null) : ElevationRepository {
        val requestedSamples = mutableMapOf<String, Int>()

        override suspend fun profile(encodedPolyline: String, samples: Int): List<Double>? {
            requestedSamples[encodedPolyline] = samples
            if (encodedPolyline == failFor) return null
            return List(samples) { i -> 100.0 * i / (samples - 1) }
        }
    }

    @Test
    fun `every alternative gets elevation deltas when all profiles load`() = runTest {
        val elevation = FakeElevation()

        val enriched = GradeEnrichedRoutesRepository.enrichAll(routes, elevation)

        assertEquals(routes.size, enriched.size)
        for (route in enriched) {
            assertFalse(route.gradeDataMissing)
            assertTrue(route.segments.all { it.elevationDeltaM != null })
            assertEquals(100.0, route.segments.sumOf { it.elevationDeltaM!! }, 1e-6)
        }
    }

    @Test
    fun `one failed profile drops the grade term for all alternatives`() = runTest {
        val elevation = FakeElevation(failFor = routes[1].encodedPolyline)

        val enriched = GradeEnrichedRoutesRepository.enrichAll(routes, elevation)

        assertEquals(routes, enriched)
        for (route in enriched) {
            assertTrue(route.gradeDataMissing)
            assertTrue(route.segments.all { it.elevationDeltaM == null })
        }
    }

    @Test
    fun `a route without a polyline drops the grade term for all alternatives`() = runTest {
        val withoutPolyline = routes.mapIndexed { i, route -> if (i == 2) route.copy(encodedPolyline = null) else route }

        val enriched = GradeEnrichedRoutesRepository.enrichAll(withoutPolyline, FakeElevation())

        assertTrue(enriched.all { it.gradeDataMissing })
        assertNull(enriched[0].segments.first().elevationDeltaM)
    }

    @Test
    fun `sample count follows route length`() = runTest {
        val elevation = FakeElevation()

        GradeEnrichedRoutesRepository.enrichAll(routes, elevation)

        for (route in routes) {
            val expected = ElevationRepository.samplesFor(route.distanceMeters)
            assertEquals(expected, elevation.requestedSamples[route.encodedPolyline])
        }
        // ~52 km default route: one sample per 250 m, not the old fixed 32.
        assertEquals(209, ElevationRepository.samplesFor(routes[0].distanceMeters))
    }

    @Test
    fun `samplesFor is bounded`() {
        assertEquals(ElevationRepository.MIN_SAMPLES, ElevationRepository.samplesFor(500.0))
        assertEquals(ElevationRepository.MIN_SAMPLES, ElevationRepository.samplesFor(Double.NaN))
        assertEquals(ElevationRepository.MAX_SAMPLES, ElevationRepository.samplesFor(400_000.0))
        assertEquals(41, ElevationRepository.samplesFor(10_000.0))
    }
}
