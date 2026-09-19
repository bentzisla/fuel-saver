package com.fuelroute.domain.learning

import com.fuelroute.domain.model.ObdSample
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.speedToBinIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedBinAggregatorTest {

    private val aggregator = SpeedBinAggregator()

    private val movingSample = ObdSample(
        timestampMs = 0,
        speedKmh = 90.0,
        rpm = 2_000.0,
        coolantTempC = 85.0,
    )

    @Test
    fun `accumulates distance and fuel into the right bin`() {
        val bins = mutableMapOf<Int, SpeedBinStats>()

        val applied = aggregator.accumulate(bins, movingSample, dtSec = 1.0, fuelRateLph = 6.0, vehicleId = "v")

        assertTrue(applied)
        val stats = bins[speedToBinIndex(90.0)]!!
        assertEquals(0.025, stats.distanceKm, 1e-9)
        assertEquals(6.0 / 3600.0, stats.fuelL, 1e-9)
        assertEquals(6.6667, stats.litersPer100Km!!, 1e-3)
        assertEquals(1, stats.samples)
    }

    @Test
    fun `rejects samples with a long gap`() {
        val bins = mutableMapOf<Int, SpeedBinStats>()
        assertFalse(aggregator.accumulate(bins, movingSample, dtSec = 5.0, fuelRateLph = 6.0, vehicleId = "v"))
        assertTrue(bins.isEmpty())
    }

    @Test
    fun `excludes a cold engine`() {
        val bins = mutableMapOf<Int, SpeedBinStats>()
        val cold = movingSample.copy(coolantTempC = 30.0)
        assertFalse(aggregator.accumulate(bins, cold, dtSec = 1.0, fuelRateLph = 6.0, vehicleId = "v"))
    }

    @Test
    fun `a 4 kmh crawl lands in bin 1 not the idle bin`() {
        val bins = mutableMapOf<Int, SpeedBinStats>()
        val crawl = ObdSample(timestampMs = 0, speedKmh = 4.0, rpm = 900.0, coolantTempC = 85.0)

        assertTrue(aggregator.accumulate(bins, crawl, dtSec = 1.0, fuelRateLph = 3.0, vehicleId = "v"))

        assertEquals(1, speedToBinIndex(4.0))
        assertTrue(bins.containsKey(1))
        assertFalse(bins.containsKey(0))
    }

    @Test
    fun `idle bin produces liters per hour but no liters per 100 km`() {
        val bins = mutableMapOf<Int, SpeedBinStats>()
        val idle = ObdSample(timestampMs = 0, speedKmh = 0.0, rpm = 800.0, coolantTempC = 85.0)

        assertTrue(aggregator.accumulate(bins, idle, dtSec = 1.0, fuelRateLph = 0.9, vehicleId = "v"))

        val stats = bins[0]!!
        assertNull(stats.litersPer100Km)
        assertEquals(0.9, stats.litersPerHour!!, 1e-9)
    }

    @Test
    fun `learned curve reflects accumulated bins`() {
        val bins = mutableMapOf<Int, SpeedBinStats>()
        repeat(100) {
            aggregator.accumulate(bins, movingSample, dtSec = 1.0, fuelRateLph = 6.0, vehicleId = "v")
        }

        val learned = LearnedCurve(bins.values.toList())
        assertFalse(learned.isEmpty)
        assertEquals(2.5, learned.totalDistanceKm, 1e-9)
        assertEquals(6.6667, learned.litersPer100Km(92.5)!!, 1e-3)
    }
}