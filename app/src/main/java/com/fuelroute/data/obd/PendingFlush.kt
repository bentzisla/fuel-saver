package com.fuelroute.data.obd

import com.fuelroute.domain.model.SpeedBinStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Flushes of the run loop's pending writes (speed-bin deltas, raw-sample buffer).
 *
 * The pending data is snapshotted and cleared BEFORE the write, and the write itself runs
 * [NonCancellable]: it used to clear only after the suspend DAO call returned, so a stop that
 * cancelled the loop right after the transaction committed left the data pending and the
 * `finally` flush wrote it a second time (double-counted bins/samples). Both writes are single
 * transactions (all or nothing), so on a failure the snapshot is merged back and retried later.
 */
internal object PendingFlush {

    /** Soft cap on buffered raw samples kept for retry while the DB keeps failing (~40 min at 4 Hz). */
    const val MAX_BUFFERED_SAMPLES = 10_000

    /** Flushes [deltas] with [write]; returns true on success (or when there was nothing). */
    suspend fun bins(
        deltas: MutableMap<Int, SpeedBinStats>,
        write: suspend (List<SpeedBinStats>) -> Unit,
        onFailure: (Exception) -> Unit = {},
    ): Boolean {
        if (deltas.isEmpty()) return true
        val batch = deltas.values.toList()
        deltas.clear()
        return try {
            withContext(NonCancellable) { write(batch) }
            true
        } catch (e: Exception) {
            for (delta in batch) {
                val pending = deltas[delta.binIndex]
                deltas[delta.binIndex] = pending?.plus(delta.distanceKm, delta.fuelL, delta.seconds, delta.samples)
                    ?: delta
            }
            if (e is CancellationException) throw e
            onFailure(e)
            false
        }
    }

    /** Flushes [buffer] with [write]; returns true on success (or when there was nothing). */
    suspend fun <T> batch(
        buffer: MutableList<T>,
        write: suspend (List<T>) -> Unit,
        onFailure: (Exception) -> Unit = {},
    ): Boolean {
        if (buffer.isEmpty()) return true
        val batch = buffer.toList()
        buffer.clear()
        return try {
            withContext(NonCancellable) { write(batch) }
            true
        } catch (e: Exception) {
            buffer.addAll(0, batch)
            // Never grow without bound while the DB is broken: drop the oldest rows.
            val overflow = buffer.size - MAX_BUFFERED_SAMPLES
            if (overflow > 0) buffer.subList(0, overflow).clear()
            if (e is CancellationException) throw e
            onFailure(e)
            false
        }
    }
}
