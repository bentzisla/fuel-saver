package com.fuelroute.domain.obd

import com.fuelroute.domain.model.ObdSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SampleSanitizerTest {

    private val good = ObdSample(
        timestampMs = 0,
        speedKmh = 50.0,
        rpm = 2000.0,
        mafGps = 12.0,
        fuelRateLph = 6.0,
        mapKpa = 40.0,
        intakeTempC = 30.0,
        coolantTempC = 88.0,
        engineLoadPct = 40.0,
        fuelLevelPct = 55.0,
    )

    @Test
    fun `plausible sample passes unchanged`() {
        assertEquals(good, SampleSanitizer.sanitize(good, 1.8))
    }

    @Test
    fun `sentinel values are rejected`() {
        val bad = good.copy(
            speedKmh = 255.0,
            rpm = 16383.75,
            mafGps = 655.35,
            fuelRateLph = 3276.75,
        )
        val clean = SampleSanitizer.sanitize(bad, 1.8)
        assertNull(clean.speedKmh)
        assertNull(clean.rpm)
        assertNull(clean.mafGps)
        assertNull(clean.fuelRateLph)
    }

    @Test
    fun `airflow and fuel with the engine off are rejected`() {
        val off = good.copy(rpm = 0.0, speedKmh = 0.0)
        val clean = SampleSanitizer.sanitize(off, 1.8)
        assertNull(clean.mafGps)
        assertNull(clean.fuelRateLph)
        assertEquals(0.0, clean.rpm!!, 0.0)
    }

    @Test
    fun `fuel rate bound derives from displacement`() {
        assertEquals(SampleSanitizer.MAX_FUEL_RATE_LPH_DEFAULT, SampleSanitizer.maxFuelRateLph(null), 0.0)
        assertEquals(72.0, SampleSanitizer.maxFuelRateLph(1.8), 1e-9)
        assertEquals(40.0, SampleSanitizer.maxFuelRateLph(0.8), 1e-9)
        assertNull(SampleSanitizer.sanitize(good.copy(fuelRateLph = 75.0), 1.8).fuelRateLph)
    }

    @Test
    fun `non finite and negative values are rejected`() {
        val bad = good.copy(speedKmh = Double.NaN, mafGps = -1.0, coolantTempC = Double.POSITIVE_INFINITY)
        val clean = SampleSanitizer.sanitize(bad, null)
        assertNull(clean.speedKmh)
        assertNull(clean.mafGps)
        assertNull(clean.coolantTempC)
    }
}
