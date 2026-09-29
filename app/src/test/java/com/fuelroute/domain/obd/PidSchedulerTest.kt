package com.fuelroute.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PidSchedulerTest {

    private val speed = ElmProtocol.PID_SPEED
    private val rpm = ElmProtocol.PID_RPM
    private val coolant = ElmProtocol.PID_COOLANT_TEMP
    private val maf = ElmProtocol.PID_MAF
    private val fuelRate = ElmProtocol.PID_FUEL_RATE
    private val map = ElmProtocol.PID_MAP
    private val iat = ElmProtocol.PID_INTAKE_TEMP
    private val load = ElmProtocol.PID_ENGINE_LOAD
    private val level = ElmProtocol.PID_FUEL_LEVEL

    private val all = setOf(speed, rpm, coolant, maf, fuelRate, map, iat, load, level)

    private fun PidScheduler.pollAll(now: Long, busAlive: Boolean = true, reply: (Int) -> String = { "41 00 00" }) {
        for (pid in pidsDue(now)) record(pid, reply(pid), now, busAlive)
    }

    @Test
    fun `first loop polls everything once, later loops only speed, RPM and the best fuel source`() {
        val scheduler = PidScheduler(all, negotiationFailed = false)

        assertEquals(listOf(speed, rpm, fuelRate, coolant, iat, load, level), scheduler.pidsDue(0L))
        scheduler.pollAll(0L)

        // 5E is preferred: MAF and MAP are not polled at all; slow PIDs wait for their interval.
        assertEquals(listOf(speed, rpm, fuelRate), scheduler.pidsDue(250L))
        assertEquals(listOf(speed, rpm, fuelRate, coolant), scheduler.pidsDue(5_000L))
        scheduler.pollAll(5_000L)
        assertEquals(listOf(speed, rpm, fuelRate, coolant, iat), scheduler.pidsDue(10_000L))
        assertEquals(listOf(speed, rpm, fuelRate), scheduler.pidsDue(7_500L))
    }

    @Test
    fun `fuel source falls back MAF then MAP for speed-density`() {
        assertEquals(maf, PidScheduler(setOf(speed, rpm, maf, map), false).fuelSourcePid())
        assertEquals(map, PidScheduler(setOf(speed, rpm, map, iat), false).fuelSourcePid())
        assertNull(PidScheduler(setOf(speed, rpm), false).fuelSourcePid())
    }

    @Test
    fun `failed negotiation polls only the mandatory trio`() {
        val scheduler = PidScheduler(emptySet(), negotiationFailed = true)
        assertEquals(listOf(speed, rpm, coolant), scheduler.pidsDue(0L))
    }

    @Test
    fun `slow replies are held between polls and expire when stale`() {
        val scheduler = PidScheduler(all, negotiationFailed = false)
        scheduler.pollAll(0L) { if (it == coolant) "41 05 7B" else "41 00 00" }

        assertEquals("41 05 7B", scheduler.reply(coolant, 4_000L))
        // Fast PIDs are never held: a fuel-rate reply is only valid in the loop that polled it.
        assertEquals("", scheduler.reply(fuelRate, 250L))
        // Coolant hold = 2 x 5 s + 1 s.
        assertEquals("", scheduler.reply(coolant, 11_001L))
    }

    @Test
    fun `an optional PID that keeps answering NO DATA is dropped, and the fuel source moves on`() {
        val scheduler = PidScheduler(all, negotiationFailed = false, dropAfterNoData = 3)
        repeat(3) { i ->
            scheduler.pollAll(i * 250L) { if (it == fuelRate) "NO DATA" else "41 00 00" }
        }
        assertTrue(fuelRate in scheduler.dropped)
        assertEquals(maf, scheduler.fuelSourcePid())
        assertFalse(fuelRate in scheduler.pidsDue(1_000L))
    }

    @Test
    fun `NO DATA while the bus is dead (ignition off) never drops anything`() {
        val scheduler = PidScheduler(all, negotiationFailed = false, dropAfterNoData = 2)
        repeat(10) { i -> scheduler.pollAll(i * 250L, busAlive = false) { "NO DATA" } }
        assertTrue(scheduler.dropped.isEmpty())
    }

    @Test
    fun `speed and RPM are never dropped`() {
        val scheduler = PidScheduler(all, negotiationFailed = false, dropAfterNoData = 1)
        repeat(3) { i -> scheduler.pollAll(i * 250L) { if (it == rpm) "NO DATA" else "41 00 00" } }
        assertFalse(rpm in scheduler.dropped)
        assertTrue(rpm in scheduler.pidsDue(1_000L))
    }

    @Test
    fun `a valid reply resets the NO DATA streak`() {
        val scheduler = PidScheduler(all, negotiationFailed = false, dropAfterNoData = 3)
        var t = 0L
        repeat(2) { scheduler.pollAll(t) { if (it == fuelRate) "NO DATA" else "41 00 00" }; t += 250 }
        scheduler.pollAll(t) { "41 5E 00 10" }; t += 250
        repeat(2) { scheduler.pollAll(t) { if (it == fuelRate) "NO DATA" else "41 00 00" }; t += 250 }
        assertFalse(fuelRate in scheduler.dropped)
    }
}
