package com.fuelroute.data.obd

import com.fuelroute.domain.learning.FuelRateCalculator
import com.fuelroute.domain.learning.LearnedDataPlausibility
import com.fuelroute.domain.learning.ObdSampleProcessor
import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.ObdSample
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.obd.ElmProtocol
import com.fuelroute.domain.obd.LiveConsumptionWindow
import com.fuelroute.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the user report "the app shows hundreds or thousands of L/100 km".
 * Each test first reproduces the absurd value with the pre-0.7 math on the same recorded
 * data, then asserts the fixed pipeline ([ObdSampleProcessor]) stays physically sensible.
 */
class AbsurdConsumptionRegressionTest {

    /** One loop of the recorded ELM session: virtual timestamp + parsed raw sample. */
    private fun replayPullAway(): List<ObdSample> {
        val script = FakeObdTransport.parseScript(Fixtures.read("fixtures/obd/pull-away-session.txt"))
        assertEquals(0, script.size % 5)
        var t = 0L
        return script.chunked(5).map { loop ->
            t += loop.first().delayMs
            val byCmd = loop.associate { it.command to it.response }
            ObdSample(
                timestampMs = t,
                speedKmh = ElmProtocol.speed(byCmd.getValue("010D")),
                rpm = ElmProtocol.rpm(byCmd.getValue("010C")),
                coolantTempC = ElmProtocol.coolantTempC(byCmd.getValue("0105")),
                mafGps = ElmProtocol.mafGps(byCmd.getValue("0110")),
                fuelRateLph = ElmProtocol.fuelRateLph(byCmd.getValue("015E")),
            )
        }
    }

    @Test
    fun `pull-away - old per-sample formula explodes, new live consumption stays sane`() {
        val samples = replayPullAway()

        // Before the fix: ObdEngine computed fuelRate / speed * 100 whenever speed > 1 km/h.
        val old = samples.mapNotNull { s ->
            val rate = FuelRateCalculator.fuelRateLph(s, FuelType.GASOLINE, 1.6)
            val v = s.speedKmh ?: 0.0
            if (v > 1.0 && rate != null) rate / v * 100.0 else null
        }
        assertTrue("old max ${old.max()} should reproduce the bug", old.max() >= 500.0)

        val processor = ObdSampleProcessor(FuelType.GASOLINE, engineDisplacementL = 1.6)
        val deltas = mutableMapOf<Int, SpeedBinStats>()
        val results = samples.map { processor.process(it, deltas, "civic") }

        results.forEach { r ->
            r.live.litersPer100Km?.let {
                assertTrue("L/100 $it at ${r.sample.speedKmh} km/h", it <= LiveConsumptionWindow.MAX_DISPLAY_L100)
            }
            r.fuelRateLph?.let { assertTrue("rate $it", it <= processor.maxFuelRateLph) }
        }
        // The first six loops are idle / crawling (<= 5 km/h or invalid speed): L/h, never L/100.
        results.take(6).forEach { assertNull(it.live.litersPer100Km) }
        // Sentinels and a frame flagged <DATA ERROR are rejected, not propagated.
        assertNull(results[5].sample.speedKmh) // 0xFF
        assertNull(results[5].sample.mafGps) // FF FF
        assertNull(results[6].sample.speedKmh) // <DATA ERROR
        assertNull(results[8].sample.speedKmh) // desync: reply of 015E to 010D
        assertEquals(12.0, results[7].sample.speedKmh!!, 0.0) // SEARCHING / BUS INIT noise tolerated
        // Two ECUs, first truncated: the complete one wins (20 L/h), bytes never concatenated.
        assertEquals(20.0, results[4].sample.fuelRateLph!!, 1e-9)

        // Cruising at the end: a trip-computer-like number.
        val cruise = results.last().live.litersPer100Km!!
        assertTrue("cruise L/100 $cruise", cruise in 5.0..40.0)

        // Everything learned is plausible.
        deltas.values.forEach { assertTrue("bin $it", LearnedDataPlausibility.isBinPlausible(it)) }
    }

    private fun civicSamples(): List<ObdSample> =
        Fixtures.read("fixtures/obd/civic-2008-speed-density.csv").lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val c = line.split(',')
                ObdSample(
                    timestampMs = c[0].toLong(),
                    speedKmh = c[1].toDouble(),
                    rpm = c[2].toDouble(),
                    mafGps = c[3].toDoubleOrNull(),
                    mapKpa = c[4].toDouble(),
                    intakeTempC = c[5].toDouble(),
                    coolantTempC = c[6].toDouble(),
                )
            }.toList()

    @Test
    fun `civic with displacement typed as 1800 - thousands of L per 100 km before, sane after`() {
        val samples = civicSamples()
        assertEquals(31, samples.size)

        // Before: displacement 1800 went straight into speed-density.
        val oldL100 = samples.filter { it.mafGps == null }.map { s ->
            FuelRateCalculator.speedDensityLph(s.mapKpa!!, s.rpm!!, s.intakeTempC!!, 1800.0, FuelType.GASOLINE) /
                s.speedKmh!! * 100.0
        }
        assertTrue("old min ${oldL100.min()}", oldL100.min() > 1_000.0)

        // After: the same stored value (1800) is normalized to 1.8 L.
        val processor = ObdSampleProcessor(FuelType.GASOLINE, engineDisplacementL = 1800.0)
        val deltas = mutableMapOf<Int, SpeedBinStats>()
        val results = samples.map { processor.process(it, deltas, "civic") }
        results.forEach { r ->
            val rate = r.fuelRateLph!!
            assertTrue("rate $rate L/h", rate in 1.0..5.0)
        }
        val l100 = results.mapNotNull { it.live.litersPer100Km }
        assertTrue(l100.isNotEmpty())
        l100.forEach { assertTrue("L/100 $it", it in 2.0..12.0) }

        // Learned bins are plausible and a single estimator is used per bin: once MAF answered
        // (t=10 s), later speed-density samples are only held/used live, not learned.
        assertTrue(deltas.isNotEmpty())
        deltas.values.forEach { assertTrue("bin $it", LearnedDataPlausibility.isBinPlausible(it)) }
        val learnedAfterMaf = results.drop(14).count { it.learned }
        assertEquals(4, learnedAfterMaf) // the second MAF reply (t=25 s) + its 3 s hold
    }
}
