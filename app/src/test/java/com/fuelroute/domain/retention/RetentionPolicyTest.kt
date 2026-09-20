package com.fuelroute.domain.retention

import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionPolicyTest {

    @Test
    fun `clamps bogus values into range`() {
        assertEquals(RetentionPolicy.DEFAULT_RETENTION_DAYS, RetentionPolicy.applyRetentionDays(90))
        assertEquals(RetentionPolicy.MIN_RETENTION_DAYS, RetentionPolicy.applyRetentionDays(0))
        assertEquals(RetentionPolicy.MIN_RETENTION_DAYS, RetentionPolicy.applyRetentionDays(-5))
        assertEquals(RetentionPolicy.MAX_RETENTION_DAYS, RetentionPolicy.applyRetentionDays(1_000_000))
    }

    @Test
    fun `cutoff is now minus retention window`() {
        val now = 1_000_000_000_000L
        val cutoff = RetentionPolicy.cutoffMs(now, 90)
        assertEquals(now - 90L * RetentionPolicy.MILLIS_PER_DAY, cutoff)
    }

    @Test
    fun `cutoff clamps before computing`() {
        val now = 1_000_000_000_000L
        val cutoff = RetentionPolicy.cutoffMs(now, 1) // below MIN -> clamps to MIN
        assertEquals(now - RetentionPolicy.MIN_RETENTION_DAYS * RetentionPolicy.MILLIS_PER_DAY, cutoff)
    }
}
