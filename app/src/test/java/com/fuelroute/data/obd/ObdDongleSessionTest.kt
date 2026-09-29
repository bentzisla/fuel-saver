package com.fuelroute.data.obd

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The probe holds the dongle for its whole check and yields it to the engine at once. */
class ObdDongleSessionTest {

    private val engineOwner = Any()

    @After
    fun tearDown() {
        ObdDongleSession.releaseEngine(engineOwner)
    }

    @Test
    fun `an unclaimed dongle runs the probe`() = runBlocking {
        val result = ObdDongleSession.runProbe(abort = {}) { "checked" }
        assertEquals("checked", result)
        assertFalse(ObdDongleSession.isProbeActive)
    }

    @Test
    fun `the probe does not start while the engine holds the dongle`() = runBlocking {
        ObdDongleSession.claimForEngine(engineOwner)
        var ran = false
        assertNull(ObdDongleSession.runProbe(abort = {}) { ran = true })
        assertFalse(ran)

        ObdDongleSession.releaseEngine(engineOwner)
        assertEquals(1, ObdDongleSession.runProbe(abort = {}) { 1 })
    }

    @Test
    fun `an engine claim aborts a running probe and waits until it let go`() = runBlocking {
        val socketClosed = CompletableDeferred<Unit>()
        val probeStarted = CompletableDeferred<Unit>()
        val probe = async(Dispatchers.Default) {
            ObdDongleSession.runProbe(abort = { socketClosed.complete(Unit) }) {
                probeStarted.complete(Unit)
                // A pending exchange: returns only once the socket is closed by the abort.
                socketClosed.await()
                "engine off"
            }
        }
        probeStarted.await()
        assertTrue(ObdDongleSession.isProbeActive)

        withTimeout(2_000) { ObdDongleSession.claimForEngine(engineOwner) }

        // The claim returned only after the probe released the dongle, and its verdict is dropped.
        assertFalse(ObdDongleSession.isProbeActive)
        assertTrue(socketClosed.isCompleted)
        assertNull(probe.await())
        assertTrue(ObdDongleSession.isEngineActive)
    }

    @Test
    fun `the engine does not wait forever for a probe that ignores the abort`() = runBlocking {
        val probeStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val probe = async(Dispatchers.Default) {
            ObdDongleSession.runProbe(abort = {}) {
                probeStarted.complete(Unit)
                release.await()
            }
        }
        probeStarted.await()
        val started = System.nanoTime()
        ObdDongleSession.claimForEngine(engineOwner, waitMs = 200)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500)
        release.complete(Unit)
        assertNull(probe.await())
    }

    @Test
    fun `a second concurrent probe is skipped`() = runBlocking {
        val inner = ObdDongleSession.runProbe(abort = {}) {
            ObdDongleSession.runProbe(abort = {}) { "nested" }
        }
        assertNull(inner)
    }
}
