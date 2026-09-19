package com.fuelroute.domain.fuel

import com.fuelroute.domain.model.CongestionLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class CongestionModelTest {

    @Test
    fun `fifty-fifty mix returns the mean speed factor`() {
        val intervals = listOf(
            CongestionInterval(0.0, 100.0, CongestionLevel.NORMAL),
            CongestionInterval(100.0, 200.0, CongestionLevel.TRAFFIC_JAM),
        )

        val factor = CongestionModel.weightedSpeedFactor(intervals, startM = 0.0, endM = 200.0)

        assertEquals(0.625, factor, 1e-9)
    }

    @Test
    fun `empty intervals fall back to 1`() {
        assertEquals(1.0, CongestionModel.weightedSpeedFactor(emptyList(), 0.0, 100.0), 1e-9)
    }

    @Test
    fun `normal-only intervals average to 1`() {
        val intervals = listOf(
            CongestionInterval(0.0, 50.0, CongestionLevel.NORMAL),
            CongestionInterval(50.0, 100.0, CongestionLevel.NORMAL),
        )

        assertEquals(1.0, CongestionModel.weightedSpeedFactor(intervals, 0.0, 100.0), 1e-9)
    }

    @Test
    fun `weights only overlap with the queried range`() {
        val intervals = listOf(
            CongestionInterval(0.0, 50.0, CongestionLevel.TRAFFIC_JAM),
            CongestionInterval(50.0, 100.0, CongestionLevel.NORMAL),
        )

        val factor = CongestionModel.weightedSpeedFactor(intervals, startM = 50.0, endM = 100.0)

        assertEquals(1.0, factor, 1e-9)
    }

    @Test
    fun `dominant level is the longest overlapping interval`() {
        val intervals = listOf(
            CongestionInterval(0.0, 30.0, CongestionLevel.TRAFFIC_JAM),
            CongestionInterval(30.0, 100.0, CongestionLevel.SLOW),
        )

        assertEquals(CongestionLevel.SLOW, CongestionModel.dominantLevel(intervals, 0.0, 100.0))
    }

    @Test
    fun `dominant level falls back to normal when empty`() {
        assertEquals(CongestionLevel.NORMAL, CongestionModel.dominantLevel(emptyList(), 0.0, 100.0))
    }
}