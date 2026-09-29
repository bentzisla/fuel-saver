package com.fuelroute.data.obd

import com.fuelroute.domain.model.SpeedBinStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingFlushTest {

    private fun bin(index: Int, km: Double) = SpeedBinStats("v1", index, km, km / 10, km * 40, 10)

    @Test
    fun `successful bin flush clears the deltas`() = runTest {
        val deltas = mutableMapOf(1 to bin(1, 2.0))
        val written = mutableListOf<SpeedBinStats>()
        assertTrue(PendingFlush.bins(deltas, { written += it }))
        assertTrue(deltas.isEmpty())
        assertEquals(listOf(bin(1, 2.0)), written)
    }

    @Test
    fun `a failed bin flush merges the snapshot back with increments added meanwhile`() = runTest {
        val deltas = mutableMapOf(1 to bin(1, 2.0))
        var failure: Exception? = null
        val ok = PendingFlush.bins(
            deltas,
            write = {
                // The loop kept learning while the write was in flight.
                deltas[1] = bin(1, 1.0)
                throw IllegalStateException("SQLITE_FULL")
            },
            onFailure = { failure = it },
        )
        assertFalse(ok)
        assertEquals(3.0, deltas.getValue(1).distanceKm, 1e-9)
        assertEquals(20, deltas.getValue(1).samples)
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `cancellation during a committed write never leaves the data pending (no double count)`() = runBlocking {
        val buffer = mutableListOf(1, 2, 3)
        val writes = mutableListOf<List<Int>>()
        val entered = CompletableDeferred<Unit>()
        val flush = async {
            PendingFlush.batch(buffer, write = { batch ->
                entered.complete(Unit)
                delay(200) // the transaction commits while a stop cancels the loop
                writes += batch
            })
            yield()
        }
        entered.await()
        flush.cancel()
        runCatching { flush.await() }

        assertEquals("the write completed exactly once", listOf(listOf(1, 2, 3)), writes)
        assertTrue("nothing left for the final flush to write again", buffer.isEmpty())
        // The final (NonCancellable) flush therefore has nothing to do.
        assertTrue(PendingFlush.batch(buffer, write = { writes += it }))
        assertEquals(1, writes.size)
    }

    @Test
    fun `a failed batch flush keeps the rows in order, bounded`() = runTest {
        val buffer = MutableList(PendingFlush.MAX_BUFFERED_SAMPLES) { it }
        val ok = PendingFlush.batch(buffer, write = {
            buffer += -1 // appended while in flight
            throw IllegalStateException("locked")
        })
        assertFalse(ok)
        assertEquals(PendingFlush.MAX_BUFFERED_SAMPLES, buffer.size)
        assertEquals(-1, buffer.last())
        assertEquals("oldest row dropped", 1, buffer.first())
    }
}
