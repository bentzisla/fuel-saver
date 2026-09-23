package com.fuelroute.domain.learning

import com.fuelroute.domain.fuel.CurveBlender
import com.fuelroute.domain.fuel.DefaultCurve
import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.ObdSample
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read-time guards and the conservative repair, driven by the numbers pulled from the user's
 * phone DB (Honda Civic 2008, displacement stored as 1800).
 */
class LearnedDataRepairTest {

    // speed_bin_stats rows as found: bin 19 = 8.5 km / 52.6 L (619 L/100); bin 22 looks sane.
    private val corrupted19 = SpeedBinStats("civic", 19, distanceKm = 8.5, fuelL = 52.6, seconds = 331.0, samples = 331)
    private val corrupted5 = SpeedBinStats("civic", 5, distanceKm = 1.2, fuelL = 18.0, seconds = 196.0, samples = 196)
    private val sane22 = SpeedBinStats("civic", 22, distanceKm = 5.0, fuelL = 0.33, seconds = 170.0, samples = 170)

    @Test
    fun `corrupted bins are excluded from the learned curve and the blend`() {
        val learned = LearnedCurve(listOf(corrupted5, corrupted19, sane22))
        assertEquals(listOf(22), learned.points.map { speedToBin(it.speedKmh) })
        assertEquals(setOf(5, 19), learned.excludedBins.map { it.binIndex }.toSet())

        val fallback = DefaultCurve.forVehicle(10.0)
        val blended = CurveBlender.blend(learned, fallback)
        for (v in listOf(30.0, 60.0, 90.0, 110.0)) {
            val value = blended.litersPer100Km(v)
            assertTrue("blend at $v = $value", value < 20.0)
        }
    }

    @Test
    fun `a tiny-distance bin is not a curve point`() {
        val tiny = SpeedBinStats("v", 2, distanceKm = 0.01, fuelL = 0.004, seconds = 5.0, samples = 5) // 40 L/100 over 10 m
        val learned = LearnedCurve(listOf(tiny))
        assertTrue(learned.isEmpty)
        assertTrue(learned.excludedBins.isEmpty()) // not implausible, just not enough data
    }

    @Test
    fun `absurd idle rate is not reported`() {
        val idle = SpeedBinStats("v", 0, distanceKm = 0.0, fuelL = 10.0, seconds = 600.0, samples = 600) // 60 L/h idle
        assertNull(LearnedCurve(listOf(idle)).idleLitersPerHour)
    }

    @Test
    fun `trip guards - short trips and impossible fuel show no L per 100 km`() {
        // Trip #18 from the phone DB: 4.37 km, 345.8 L in 4.7 min (7919 L/100 km).
        val trip18 = Trip(18, "civic", 0L, 282_000L, 4.37, 345.8, 55.8, 80.0, 20.0)
        assertNull(trip18.litersPer100Km)
        assertFalse(trip18.isFuelPlausible)

        val short = Trip(1, "civic", 0L, 60_000L, 0.2, 0.05, 12.0, 20.0, 30.0) // 25 L/100 over 200 m
        assertNull(short.litersPer100Km)
        assertTrue(short.isFuelPlausible)

        val normal = Trip(2, "civic", 0L, 1_200_000L, 15.0, 1.0, 45.0, 90.0, 60.0)
        assertEquals(6.667, normal.litersPer100Km!!, 1e-3)
    }

    /** ~1 Hz raw samples of a trip at 90 km/h with MAP/RPM/IAT only (speed-density path). */
    private fun sdSamples(fromMs: Long, count: Int): List<ObdSample> = (0 until count).map {
        ObdSample(
            timestampMs = fromMs + it * 1000L,
            speedKmh = 92.0,
            rpm = 2800.0,
            mapKpa = 45.0,
            intakeTempC = 30.0,
            coolantTempC = 88.0,
        )
    }

    @Test
    fun `rebuild from raw samples replaces bad bins and re-integrates bad trips`() {
        val samples = sdSamples(fromMs = 1_000_000L, count = 283)
        val rebuilder = LearnedDataRebuilder(
            fuelType = FuelType.GASOLINE,
            engineDisplacementL = 1800.0, // stored value; normalized to 1.8 inside
            fuelRateCorrection = 1.0,
            vehicleId = "civic",
            tripWindows = listOf(LearnedDataRebuilder.TripWindow(18, 1_000_000L, 1_282_000L)),
        )
        samples.forEach(rebuilder::add)

        val trips = listOf(
            LearnedDataRepairPlanner.TripRow(18, distanceKm = 4.37, fuelL = 345.8, durationSeconds = 282.0),
            LearnedDataRepairPlanner.TripRow(7, distanceKm = 30.0, fuelL = 2.0, durationSeconds = 1_800.0),
        )
        val plan = LearnedDataRepairPlanner.plan(
            stored = listOf(corrupted5, corrupted19, sane22),
            rebuilt = rebuilder.bins,
            trips = trips,
            tripFuel = rebuilder.tripFuel,
            maxFuelRateLph = rebuilder.maxFuelRateLph,
        )

        // Bin 19 (92.5 km/h) is rebuilt from the samples with a sane value.
        val rebuilt19 = plan.replaceBins.single { it.binIndex == 19 }
        assertTrue(LearnedDataPlausibility.isBinPlausible(rebuilt19))
        assertTrue("L/100 ${rebuilt19.litersPer100Km}", rebuilt19.litersPer100Km!! in 3.0..15.0)
        // Bin 5 has no raw samples left: removed (it is archived by the data layer first).
        assertEquals(listOf(5), plan.deleteBins)
        // The plausible bin 22 is never touched.
        assertTrue(plan.replaceBins.none { it.binIndex == 22 })

        // Trip 18 re-integrated to a few decilitres; the plausible trip 7 is untouched.
        val fuel18 = plan.tripFuel.getValue(18)
        assertTrue("fuel $fuel18", fuel18 in 0.2..1.0)
        assertFalse(plan.tripFuel.containsKey(7))
    }

    @Test
    fun `wrong displacement also recomputes plausible looking rows the samples cover`() {
        val samples = sdSamples(fromMs = 0L, count = 200)
        val rebuilder = LearnedDataRebuilder(
            FuelType.GASOLINE, 1800.0, 1.0, "civic",
            tripWindows = listOf(LearnedDataRebuilder.TripWindow(3, 0L, 199_000L)),
        )
        samples.forEach(rebuilder::add)
        // Looks plausible (18 L/100) but was inflated by a few 1000x speed-density samples.
        val inflated19 = SpeedBinStats("civic", 19, 10.0, 1.8, 390.0, 390)
        val trip3 = LearnedDataRepairPlanner.TripRow(3, distanceKm = 5.1, fuelL = 0.9, durationSeconds = 199.0)

        val conservative = LearnedDataRepairPlanner.plan(
            listOf(inflated19), rebuilder.bins, listOf(trip3), rebuilder.tripFuel, rebuilder.maxFuelRateLph,
        )
        assertTrue(conservative.isEmpty)

        val displacementFix = LearnedDataRepairPlanner.plan(
            listOf(inflated19), rebuilder.bins, listOf(trip3), rebuilder.tripFuel, rebuilder.maxFuelRateLph,
            displacementWasWrong = true,
        )
        assertEquals(listOf(19), displacementFix.replaceBins.map { it.binIndex })
        assertTrue(displacementFix.tripFuel.getValue(3) < 0.9)
    }

    @Test
    fun `demo windows are excluded and poor coverage is not repaired`() {
        val rebuilder = LearnedDataRebuilder(
            FuelType.GASOLINE, 1.8, 1.0, "v",
            excludedWindows = listOf(0L..100_000L),
            tripWindows = listOf(LearnedDataRebuilder.TripWindow(1, 0L, 100_000L)),
        )
        sdSamples(0L, 100).forEach(rebuilder::add)
        assertEquals(0, rebuilder.sampleCount)
        assertTrue(rebuilder.bins.isEmpty())
        assertNull(LearnedDataRebuilder.repairedTripFuel(rebuilder.tripFuel[1], 100.0))
        assertNull(LearnedDataRebuilder.repairedTripFuel(LearnedDataRebuilder.TripFuel(0.1, 30.0), 100.0))
        assertEquals(0.2, LearnedDataRebuilder.repairedTripFuel(LearnedDataRebuilder.TripFuel(0.1, 50.0), 100.0)!!, 1e-9)
    }

    private fun speedToBin(speedKmh: Double): Int = com.fuelroute.domain.model.speedToBinIndex(speedKmh)
}
