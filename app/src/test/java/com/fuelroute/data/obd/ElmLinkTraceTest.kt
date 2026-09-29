package com.fuelroute.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Verbose per-exchange traffic goes to the `trace` sink (debug builds only); link failures stay
 * on `log` so release builds keep warnings and errors without dumping every ELM exchange.
 */
class ElmLinkTraceTest {

    @Test
    fun `commands and replies go to trace, not to log`() = runBlocking {
        val logged = Collections.synchronizedList(mutableListOf<String>())
        val traced = Collections.synchronizedList(mutableListOf<String>())
        val (input, output) = FakeElmDevice().open()
        val link = ElmLink(input, output, label = "t", log = { logged += it }, trace = { traced += it })

        link.exchange("010D", 1_000)
        link.exchangeSoft("ATZ", 1_000)

        assertTrue(logged.toString(), logged.isEmpty())
        assertTrue(traced.toString(), traced.any { it.startsWith("ELM>> 010D") })
        assertTrue(traced.toString(), traced.any { it.startsWith("ELM<< ") && it.contains("41 0D") })
        assertTrue(traced.toString(), traced.any { it.startsWith("ELM>> ATZ (soft") })
    }

    @Test
    fun `a timeout is still logged when tracing is off`() = runBlocking {
        val logged = Collections.synchronizedList(mutableListOf<String>())
        val (input, output) = FakeElmDevice(responder = { null }).open()
        val link = ElmLink(
            input,
            output,
            label = "t",
            log = { logged += it },
            trace = ElmLink.traceSink(enabled = false) { error("trace must be off") },
        )

        assertEquals("", link.exchange("010D", 200))
        assertTrue(logged.toString(), logged.any { it.contains("TIMEOUT") })
        assertTrue(logged.toString(), logged.any { it.contains("closed") })
    }

    @Test
    fun `traceSink passes through when enabled`() {
        val traced = mutableListOf<String>()
        ElmLink.traceSink(enabled = true) { traced += it }("x")
        ElmLink.traceSink(enabled = false) { traced += it }("y")
        assertEquals(listOf("x"), traced)
    }
}
