package com.fuelroute.data.obd

import android.util.Log
import com.fuelroute.data.db.ObdSampleDao
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.learning.ColdStartRepository
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.domain.model.VehicleProfile
import com.fuelroute.domain.obd.EngineOffTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How a run ends by itself (ignition off), when it may call itself Connected, and that DB
 * failures / the demo transport never leak into the process or the real vehicle's data.
 */
class ObdEngineRunEndTest {

    private val vehicle = VehicleProfile(id = "v1", name = "Civic")
    private val engines = mutableListOf<ObdEngine>()
    private val watchers = CoroutineScope(Dispatchers.Default)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        watchers.cancel()
        runBlocking { engines.forEach { withTimeout(15_000) { it.stop() } } }
        unmockkStatic(Log::class)
    }

    private class Deps(
        val sampleDao: ObdSampleDao = mockk(relaxed = true),
        val tripDao: TripDao = mockk(relaxed = true),
        val coldStart: ColdStartRepository = mockk(relaxed = true),
    )

    private fun newEngine(deps: Deps = Deps(), ignitionOffMs: Long = 60_000L): ObdEngine {
        val speedBinDao = mockk<SpeedBinDao>(relaxed = true)
        coEvery { speedBinDao.getForVehicle(any()) } returns emptyList()
        val price = mockk<FuelPriceRepository>()
        coEvery { price.current(any()) } throws IllegalStateException("no price in tests")
        return ObdEngine(
            sampleDao = deps.sampleDao,
            speedBinDao = speedBinDao,
            tripDao = deps.tripDao,
            fuelPriceRepository = price,
            tripLinker = mockk(relaxed = true),
            coldStartRepository = deps.coldStart,
        ).also {
            it.engineOffTrackerFactory = { EngineOffTracker(rpmTimeoutMs = ignitionOffMs) }
            engines += it
        }
    }

    private fun ObdEngine.recordStatuses(): MutableList<ObdStatus> {
        val seen = CopyOnWriteArrayList<ObdStatus>()
        watchers.launch { live.collect { seen += it.status } }
        return seen
    }

    private suspend fun ObdEngine.awaitStopped(timeoutMs: Long) = withTimeout(timeoutMs) {
        while (isRunning || live.value.status == ObdStatus.Connecting) delay(20)
        live.value
    }

    @Test
    fun `parked car - adapter answers but the ECU is asleep - never Connected, ends as IGNITION_OFF`() = runBlocking {
        val device = FakeElmDevice(responder = { cmd -> if (cmd.startsWith("01")) "NO DATA" else FakeElmDevice.healthy(cmd) })
        val transport = StreamObdTransport(device)
        val engine = newEngine(ignitionOffMs = 1_500L)
        val statuses = engine.recordStatuses()

        engine.start(transport, vehicle)
        val end = engine.awaitStopped(10_000)

        assertFalse("no valid PID reply: must never claim Connected", ObdStatus.Connected in statuses)
        assertEquals(ObdStatus.Disconnected, end.status)
        assertEquals(ObdStopReason.IGNITION_OFF, end.stopReason)
        assertEquals(0, end.validSampleCount)
        assertEquals(1, transport.connectCount.get())
        assertFalse(transport.isConnected)
    }

    @Test
    fun `ignition off after a drive ends the run with IGNITION_OFF and real data counted`() = runBlocking {
        val ecuAsleep = AtomicBoolean(false)
        val device = FakeElmDevice(responder = { cmd ->
            if (ecuAsleep.get() && cmd.startsWith("01")) "NO DATA" else FakeElmDevice.healthy(cmd)
        })
        val engine = newEngine(ignitionOffMs = 1_500L)
        engine.start(StreamObdTransport(device), vehicle)
        withTimeout(10_000) { while (engine.live.value.status != ObdStatus.Connected) delay(20) }
        assertNull(engine.live.value.stopReason)
        ecuAsleep.set(true)

        val end = engine.awaitStopped(10_000)

        assertEquals(ObdStatus.Disconnected, end.status)
        assertEquals(ObdStopReason.IGNITION_OFF, end.stopReason)
        assertTrue(end.validSampleCount > 0)
    }

    @Test
    fun `a fresh start clears the previous stop reason`() = runBlocking {
        val asleep = FakeElmDevice(responder = { cmd -> if (cmd.startsWith("01")) "NO DATA" else FakeElmDevice.healthy(cmd) })
        val engine = newEngine(ignitionOffMs = 1_000L)
        engine.start(StreamObdTransport(asleep), vehicle)
        assertEquals(ObdStopReason.IGNITION_OFF, engine.awaitStopped(10_000).stopReason)

        engine.start(StreamObdTransport(FakeElmDevice()), vehicle)
        withTimeout(10_000) { while (engine.live.value.status != ObdStatus.Connected) delay(20) }
        assertNull(engine.live.value.stopReason)
    }

    @Test
    fun `DB failures in the loop never kill the run`() = runBlocking {
        val deps = Deps(
            sampleDao = mockk { coEvery { insertAll(any()) } throws IllegalStateException("SQLITE_FULL") },
            tripDao = mockk { coEvery { closeOpenTrips(any()) } throws IllegalStateException("locked") },
            coldStart = mockk { coEvery { record(any(), any()) } throws IllegalStateException("locked") },
        )
        val engine = newEngine(deps)
        engine.start(StreamObdTransport(FakeElmDevice()), vehicle)
        withTimeout(10_000) { while (engine.live.value.status != ObdStatus.Connected) delay(20) }
        val before = engine.live.value.sampleCount

        delay(2_000) // several sample flushes fail, the trip start fails

        assertTrue(engine.isRunning)
        assertEquals(ObdStatus.Connected, engine.live.value.status)
        assertTrue(engine.live.value.sampleCount > before)
        coVerify(atLeast = 1) { deps.sampleDao.insertAll(any()) }
    }

    @Test
    fun `the demo transport never feeds the cold-start mean, a real one does`() = runBlocking {
        // Cold engine (0105 -> 0 C), moving at 60 km/h with MAF: every sample is a cold one.
        val cold = { cmd: String -> if (cmd == "0105") "41 05 28" else FakeElmDevice.healthy(cmd) }

        val demoDeps = Deps()
        val demo = newEngine(demoDeps)
        demo.start(SimulatedFlag(StreamObdTransport(FakeElmDevice(responder = cold))), vehicle)
        withTimeout(10_000) { while (demo.live.value.status != ObdStatus.Connected) delay(20) }
        delay(1_500)
        withTimeout(10_000) { demo.stop() }
        coVerify(exactly = 0) { demoDeps.coldStart.record(any(), any()) }

        val realDeps = Deps()
        val real = newEngine(realDeps)
        real.start(StreamObdTransport(FakeElmDevice(responder = cold)), vehicle)
        withTimeout(10_000) { while (real.live.value.status != ObdStatus.Connected) delay(20) }
        delay(1_500)
        withTimeout(10_000) { real.stop() }
        coVerify(exactly = 1) { realDeps.coldStart.record("v1", any()) }
    }

    /** Marks any transport as the simulated demo. */
    private class SimulatedFlag(private val inner: ObdTransport) : ObdTransport by inner {
        override val isSimulated: Boolean = true
    }
}
