package com.fuelroute.data.obd

import com.fuelroute.domain.obd.ElmLinkFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.OutputStream

/**
 * Bug 1: the per-command deadline must really fire even though the socket read ignores
 * interrupts and coroutine cancellation.
 */
class ElmLinkTest {

    private fun link(device: FakeElmDevice): Pair<ElmLink, BlockingSocketInput> {
        val (input, output) = device.open()
        return ElmLink(input, output, label = "test") to input
    }

    @Test
    fun `exchange returns the reply up to the prompt`() = runBlocking {
        val (link, _) = link(FakeElmDevice())
        val reply = link.exchange("010D", 1_000)
        assertTrue(reply, reply.contains("41 0D 3C"))
        assertTrue(link.isOpen)
        assertEquals(null, link.lastFailure)
    }

    @Test
    fun `silent adapter - deadline fires, link closes and the parked read is released`() = runBlocking {
        val device = FakeElmDevice(responder = { null })
        val (link, input) = link(device)

        val started = System.nanoTime()
        val reply = withTimeout(5_000) { link.exchange("010D", 300) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals("", reply)
        assertTrue("took ${elapsedMs}ms", elapsedMs < 2_000)
        assertFalse(link.isOpen)
        assertEquals(ElmLinkFailure.TIMEOUT, link.lastFailure)
        assertTrue(input.closed)
        // The abandoned IO thread is unblocked by the close, not left parked forever.
        withTimeout(2_000) { while (input.parkedReaders.get() > 0) delay(10) }
        // A closed link answers immediately with LINK_CLOSED.
        assertEquals("", link.exchange("0105", 5_000))
        assertEquals(ElmLinkFailure.LINK_CLOSED, link.lastFailure)
    }

    @Test
    fun `cancelling the caller closes the link instead of waiting for the read`() = runBlocking {
        val (link, input) = link(FakeElmDevice(responder = { null }))
        val call = async(Dispatchers.Default) { link.exchange("010D", 60_000) }
        delay(200)
        val started = System.nanoTime()
        withTimeout(2_000) { call.cancelAndJoinQuietly() }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500)
        assertFalse(link.isOpen)
        withTimeout(2_000) { while (input.parkedReaders.get() > 0) delay(10) }
    }

    @Test
    fun `external close from another thread unblocks a pending exchange`() = runBlocking {
        val (link, _) = link(FakeElmDevice(responder = { null }))
        val call = async(Dispatchers.Default) { link.exchange("010D", 60_000) }
        delay(200)
        link.close("disconnect()")
        assertEquals("", withTimeout(2_000) { call.await() })
        assertEquals(ElmLinkFailure.LINK_CLOSED, link.lastFailure)
    }

    @Test
    fun `peer hang-up is reported as EOF, not as a timeout`() = runBlocking {
        val input = object : java.io.InputStream() {
            override fun read(): Int = -1
            override fun available(): Int = 0
        }
        val sink = object : OutputStream() {
            override fun write(b: Int) = Unit
        }
        val link = ElmLink(input, sink, label = "eof")
        assertEquals("", link.exchange("ATZ", 1_000))
        assertEquals(ElmLinkFailure.EOF, link.lastFailure)
        assertFalse(link.isOpen)
    }

    @Test
    fun `write failure is reported as WRITE_FAILED`() = runBlocking {
        val output = object : OutputStream() {
            override fun write(b: Int) = throw IOException("Broken pipe")
        }
        val (input, _) = FakeElmDevice().open()
        val link = ElmLink(input, output, label = "wfail")
        assertEquals("", link.exchange("ATZ", 1_000))
        assertEquals(ElmLinkFailure.WRITE_FAILED, link.lastFailure)
        assertFalse(link.isOpen)
    }

    @Test
    fun `stale bytes from an earlier reply are drained, not read as the next answer`() = runBlocking {
        val (link, input) = link(FakeElmDevice())
        input.feed("STOPPED\r\r>")
        delay(50)
        val reply = link.exchange("010D", 1_000)
        assertTrue(reply, reply.contains("41 0D 3C"))
        assertFalse(reply.contains("STOPPED"))
    }

    @Test
    fun `soft exchange keeps the link on silence and marks the prompt`() = runBlocking {
        var swallowReset = true
        val device = FakeElmDevice(responder = { cmd ->
            if (cmd == "ATZ" && swallowReset) null else FakeElmDevice.healthy(cmd)
        })
        val (link, _) = link(device)

        assertEquals("", link.exchangeSoft("ATZ", 200))
        assertTrue("soft timeout must not close the link", link.isOpen)
        assertEquals(null, link.lastFailure)

        swallowReset = false
        val banner = link.exchangeSoft("ATWS", 1_000)
        assertTrue(banner, banner.contains("ELM327") && banner.endsWith(">"))
        assertEquals("?\r\r>", link.exchangeSoft("", 1_000))
        assertTrue(link.exchange("ATE0", 1_000).contains("OK"))
    }

    @Test
    fun `printable makes control bytes visible`() {
        assertEquals("ELM\\r\\n\\x00>", ElmLink.printable("ELM\r\n\u0000>"))
        assertEquals("\"\"", ElmLink.printable(""))
    }

    private suspend fun kotlinx.coroutines.Deferred<*>.cancelAndJoinQuietly() {
        cancel()
        runCatching { join() }
    }
}
