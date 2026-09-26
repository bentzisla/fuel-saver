package com.fuelroute.data.obd

import android.util.Log
import com.fuelroute.data.db.ObdSampleDao
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.domain.model.VehicleProfile
import com.fuelroute.domain.obd.ElmLinkFailure
import com.fuelroute.domain.obd.ObdConnectionPolicy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Engine lifecycle against adapters that hang. The fake sockets block in a way that neither
 * interrupts nor coroutine cancellation can undo (like `BluetoothSocket.read()`), so these
 * tests prove the engine never relies on cancellation to get unstuck.
 */
class ObdEngineLifecycleTest {

    private val vehicle = VehicleProfile(id = "v1", name = "Civic")
    private val engines = mutableListOf<ObdEngine>()

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
        runBlocking { engines.forEach { withTimeout(15_000) { it.stop() } } }
        unmockkStatic(Log::class)
    }

    private fun newEngine(): ObdEngine {
        val speedBinDao = mockk<SpeedBinDao>(relaxed = true)
        coEvery { speedBinDao.getForVehicle(any()) } returns emptyList()
        val price = mockk<FuelPriceRepository>()
        coEvery { price.current(any()) } throws IllegalStateException("no price in tests")
        return ObdEngine(
            sampleDao = mockk<ObdSampleDao>(relaxed = true),
            speedBinDao = speedBinDao,
            tripDao = mockk<TripDao>(relaxed = true),
            fuelPriceRepository = price,
            tripLinker = mockk(relaxed = true),
            coldStartRepository = mockk(relaxed = true),
        ).also { engines += it }
    }

    private suspend fun ObdEngine.awaitStatus(status: ObdStatus, timeoutMs: Long): LiveObdState =
        withTimeout(timeoutMs) {
            while (live.value.status != status) delay(20)
            live.value
        }

    @Test
    fun `healthy adapter connects through the full init sequence`() = runBlocking {
        val device = FakeElmDevice()
        val transport = StreamObdTransport(device)
        val engine = newEngine()

        engine.start(transport, vehicle)
        engine.awaitStatus(ObdStatus.Connected, 10_000)

        val sent = device.commands.toList()
        assertEquals("bare CR line-clear comes first", "", sent.first())
        assertTrue(sent.indexOf("ATZ") in 1 until sent.indexOf("ATE0"))
        assertTrue(sent.containsAll(listOf("ATSP0", "0100", "ATDPN")))
        assertEquals(1, transport.connectCount.get())
    }

    @Test
    fun `silent adapter - init deadline fires, reopens once, then reports INIT TIMEOUT`() = runBlocking {
        val device = FakeElmDevice(responder = { null })
        val transport = StreamObdTransport(device)
        val engine = newEngine()

        val started = System.nanoTime()
        engine.start(transport, vehicle)
        val state = engine.awaitStatus(ObdStatus.Error, 10_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(ObdConnectionPolicy.ERROR_INIT_TIMEOUT, state.lastError)
        assertEquals("one full close + reconnect before giving up", 2, transport.connectCount.get())
        assertTrue("took ${elapsedMs}ms", elapsedMs < 8_000)
        withTimeout(3_000) { while (engine.isRunning) delay(20) }
        assertFalse("socket must be closed after a failed init", transport.isConnected)
        assertTrue(transport.inputs.all { it.closed })
    }

    @Test
    fun `hard init timeout on a config command is classified and recovered by the reopen`() = runBlocking {
        val swallowFirstAte0 = AtomicBoolean(true)
        val device = FakeElmDevice(responder = { cmd ->
            if (cmd == "ATE0" && swallowFirstAte0.getAndSet(false)) null else FakeElmDevice.healthy(cmd)
        })
        val transport = StreamObdTransport(device)
        val engine = newEngine()

        engine.start(transport, vehicle)
        engine.awaitStatus(ObdStatus.Connected, 10_000)

        assertTrue(transport.failures.contains(ElmLinkFailure.TIMEOUT))
        assertEquals(2, transport.connectCount.get())
        assertTrue("first link was closed by the watchdog", transport.inputs.first().closed)
    }

    @Test
    fun `garbage around the banner and a swallowed ATZ are tolerated`() = runBlocking {
        val atzCalls = AtomicInteger()
        val device = FakeElmDevice(responder = { cmd ->
            when (cmd) {
                "ATZ" -> if (atzCalls.incrementAndGet() == 1) null else "ÿ\u0000?\rELM327 v1.5"
                else -> FakeElmDevice.healthy(cmd)
            }
        })
        val transport = StreamObdTransport(device, timeoutCapMs = 400)
        val engine = newEngine()

        engine.start(transport, vehicle)
        engine.awaitStatus(ObdStatus.Connected, 10_000)
        assertEquals("soft reset timeout must not cost the socket", 1, transport.connectCount.get())
        assertEquals(2, atzCalls.get())
    }

    @Test
    fun `failed protocol search gets ATPC and a retry`() = runBlocking {
        val searches = AtomicInteger()
        val device = FakeElmDevice(responder = { cmd ->
            if (cmd == "0100" && searches.incrementAndGet() == 1) {
                "SEARCHING...\rUNABLE TO CONNECT"
            } else {
                FakeElmDevice.healthy(cmd)
            }
        })
        val engine = newEngine()
        engine.start(StreamObdTransport(device), vehicle)
        engine.awaitStatus(ObdStatus.Connected, 10_000)

        val sent = device.commands.toList()
        val firstSearch = sent.indexOf("0100")
        assertEquals("ATPC", sent[firstSearch + 1])
        assertEquals("0100", sent[firstSearch + 2])
    }

    @Test
    fun `run-loop poll timeout closes the link and the reconnect path recovers`() = runBlocking {
        val hang = AtomicBoolean(false)
        val device = FakeElmDevice(responder = { cmd ->
            // One hung speed poll; afterwards the adapter behaves again.
            if (cmd == "010D" && hang.getAndSet(false)) null else FakeElmDevice.healthy(cmd)
        })
        val transport = StreamObdTransport(device)
        val engine = newEngine()

        engine.start(transport, vehicle)
        engine.awaitStatus(ObdStatus.Connected, 10_000)
        hang.set(true)

        // Leaves Connected (RECONNECT), then comes back on a fresh socket.
        engine.awaitStatus(ObdStatus.Connecting, 10_000)
        engine.awaitStatus(ObdStatus.Connected, 15_000)

        assertTrue(transport.failures.contains(ElmLinkFailure.TIMEOUT))
        assertEquals(2, transport.connectCount.get())
        assertTrue(transport.inputs.first().closed)
    }

    @Test
    fun `sustained NO DATA (ignition off) never triggers a reconnect`() = runBlocking {
        // Bus is up (the adapter answers every command) but the ECU is asleep: every mode 01
        // poll comes back NO DATA, exactly like a real car with the ignition off and the
        // dongle powered from the always-on OBD port. This must stay Connected on the SAME
        // socket — reconnecting cannot make a sleeping ECU answer, and used to also reset the
        // ignition-off RPM-absence timer on every such reconnect, so the loop never stopped.
        val ecuAsleep = AtomicBoolean(false)
        val device = FakeElmDevice(responder = { cmd ->
            if (ecuAsleep.get() && cmd.startsWith("01")) "NO DATA" else FakeElmDevice.healthy(cmd)
        })
        val transport = StreamObdTransport(device)
        val engine = newEngine()

        engine.start(transport, vehicle)
        engine.awaitStatus(ObdStatus.Connected, 10_000)
        ecuAsleep.set(true)

        // RECONNECT_AFTER_FAILURES is 5 and the poll interval is 250ms, so 3s of NO DATA
        // covers many multiples of the old (buggy) reconnect threshold.
        delay(3_000)

        assertEquals(ObdStatus.Connected, engine.live.value.status)
        assertEquals("must never have reconnected", 1, transport.connectCount.get())
        assertEquals("NO DATA", engine.live.value.lastError)
    }

    @Test
    fun `persistent garbage rides out a soft resync before the escalation ladder reconnects`() = runBlocking {
        // Every speed poll comes back as unparseable garbage — NOT a hang: the adapter answers
        // promptly, so the link never times out and never closes on its own. Before the
        // escalation ladder existed, 5 such replies (~1.25s) tore the RFCOMM socket down; now it
        // must ride out a soft, non-destructive resync first and only actually reconnect once
        // the bad streak has run the full escalation window.
        val garbled = AtomicBoolean(false)
        val device = FakeElmDevice(responder = { cmd ->
            if (garbled.get() && cmd == "010D") "GARBAGE" else FakeElmDevice.healthy(cmd)
        })
        val transport = StreamObdTransport(device)
        val engine = newEngine()

        engine.start(transport, vehicle)
        engine.awaitStatus(ObdStatus.Connected, 10_000)
        garbled.set(true)

        // Comfortably past the old 5-reply trigger and past SOFT_RESYNC_AFTER_BAD_MS (3s), but
        // short of RECONNECT_AFTER_BAD_MS (9s): must still be Connected on the ORIGINAL socket —
        // the soft resync (drain + ATPC) must never touch it.
        delay(6_000)
        assertEquals("still connected on the original socket", ObdStatus.Connected, engine.live.value.status)
        assertEquals("soft resync must never reconnect the socket", 1, transport.connectCount.get())

        // Past the escalation window: the ladder must now have given up on this socket.
        engine.awaitStatus(ObdStatus.Connecting, 6_000)
        withTimeout(10_000) { while (transport.connectCount.get() < 2) delay(20) }
        assertEquals(2, transport.connectCount.get())
    }

    @Test
    fun `stop returns promptly while a read is hung on a silent adapter`() = runBlocking {
        val silent = AtomicBoolean(false)
        val device = FakeElmDevice(responder = { cmd -> if (silent.get()) null else FakeElmDevice.healthy(cmd) })
        // No cap: the run loop's read would wait the full production deadline.
        val transport = StreamObdTransport(device, timeoutCapMs = Long.MAX_VALUE)
        val engine = newEngine()
        engine.start(transport, vehicle)
        engine.awaitStatus(ObdStatus.Connected, 10_000)
        silent.set(true)
        delay(600) // let the loop park in a read

        val started = System.nanoTime()
        withTimeout(6_000) { engine.stop() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("stop took ${elapsedMs}ms", elapsedMs < 4_500)
        assertEquals(ObdStatus.Disconnected, engine.live.value.status)
        assertFalse(engine.isRunning)
        assertFalse(transport.isConnected)
    }

    @Test
    fun `stop never hangs even when the transport ignores cancellation entirely`() = runBlocking {
        val transport = UncancellableTransport()
        val engine = newEngine()
        engine.start(transport, vehicle)
        assertTrue("first command must be parked", transport.parked.await(5, TimeUnit.SECONDS))

        val started = System.nanoTime()
        withTimeout(10_000) { engine.stop() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        val maxMs = ObdConnectionPolicy.STOP_GRACE_MS + ObdConnectionPolicy.STOP_CANCEL_JOIN_MS +
            ObdConnectionPolicy.STOP_FORCE_JOIN_MS + 1_000
        assertTrue("stop took ${elapsedMs}ms", elapsedMs < maxMs)
        assertTrue("force-close must release the parked read", transport.released.get())
        assertEquals(ObdStatus.Disconnected, engine.live.value.status)
    }

    @Test
    fun `requestStop never cancels a start issued after it`() = runBlocking {
        val engine = newEngine()
        engine.start(StreamObdTransport(FakeElmDevice()), vehicle)
        engine.awaitStatus(ObdStatus.Connected, 10_000)

        val stopJob = engine.requestStop()
        val second = StreamObdTransport(FakeElmDevice())
        engine.start(second, vehicle)
        withTimeout(10_000) { stopJob.join() }
        engine.awaitStatus(ObdStatus.Connected, 10_000)
        delay(300)
        assertEquals(ObdStatus.Connected, engine.live.value.status)
        assertTrue(engine.isRunning)
        assertTrue(second.isConnected)
    }

    @Test
    fun `clean stop sends ATPC before closing the socket`() = runBlocking {
        val device = FakeElmDevice()
        val transport = StreamObdTransport(device)
        val engine = newEngine()
        engine.start(transport, vehicle)
        engine.awaitStatus(ObdStatus.Connected, 10_000)

        withTimeout(6_000) { engine.stop() }
        assertEquals("ATPC", device.commands.last())
        assertFalse(transport.isConnected)
    }

    /**
     * A transport with NO watchdog: every command blocks its thread until [disconnect], ignoring
     * interrupts and cancellation — the worst case the engine must survive.
     */
    private class UncancellableTransport : ObdTransport {
        val parked = CountDownLatch(1)
        val released = AtomicBoolean(false)
        private val gate = CountDownLatch(1)

        @Volatile
        private var open = false

        override val isConnected: Boolean get() = open
        override val deviceName: String = "uncancellable"

        override suspend fun connect(): Result<Unit> {
            open = true
            return Result.success(Unit)
        }

        override suspend fun disconnect() {
            open = false
            gate.countDown()
        }

        override suspend fun sendCommand(command: String): String {
            if (!open) return ""
            parked.countDown()
            while (true) {
                try {
                    if (gate.await(50, TimeUnit.MILLISECONDS)) break
                } catch (_: InterruptedException) {
                    // ignored, like a Bluetooth read
                }
            }
            released.set(true)
            return ""
        }
    }
}
