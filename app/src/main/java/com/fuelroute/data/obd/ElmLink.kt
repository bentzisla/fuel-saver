package com.fuelroute.data.obd

import com.fuelroute.domain.obd.ElmLinkFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * One open ELM327 byte stream (e.g. an RFCOMM socket) with request/response exchanges that
 * have a deadline which actually works.
 *
 * `BluetoothSocket` has no public read timeout and its `read()` ignores both thread
 * interrupts and coroutine cancellation, so a silent adapter used to park the engine forever.
 * Here every [exchange] runs its blocking write+read on a dedicated IO thread while the calling
 * coroutine suspends. When the deadline expires — or the caller is cancelled — the link is
 * [close]d: closing the underlying socket is the one reliable way to make the parked `read()`
 * throw, which frees the thread. The caller gets `""` right away and never waits for the
 * abandoned read. A closed link stays closed; the owner reconnects with a fresh one.
 *
 * [exchangeSoft] is the non-destructive variant used for the line-clear and reset commands
 * of flaky clones: it polls `available()` instead of blocking, so an unanswered reset does not
 * cost the whole connection.
 *
 * Pure JVM (no Android types) so the watchdog is unit-tested with a stream that blocks
 * exactly like a Bluetooth socket.
 *
 * @param closeAction closes the underlying socket (called once, from [close]).
 * @param log sink for raw traffic / diagnostics (`FuelRoute` logcat tag in production).
 */
class ElmLink(
    private val input: InputStream,
    private val output: OutputStream,
    private val label: String,
    private val closeAction: () -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val closed = AtomicBoolean(false)

    /** Serializes exchanges so a second command is never written while a read is pending. */
    private val exchangeMutex = Mutex()

    @Volatile
    var closeReason: String? = null
        private set

    /** Cause of the most recent empty reply, or `null` when the last exchange got a reply. */
    @Volatile
    var lastFailure: ElmLinkFailure? = null
        private set

    val isOpen: Boolean
        get() = !closed.get()

    /**
     * Closes the link (idempotent, non-blocking apart from the socket close itself, callable
     * from any thread). Unblocks an in-flight read.
     */
    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        closeReason = reason
        log("ELM link $label closed: $reason")
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { closeAction() }
    }

    /**
     * Writes [command] + CR and returns everything up to the `>` prompt. Returns `""` when the
     * link is (or becomes) closed, on a write/read error or EOF, or when [timeoutMs] expires —
     * in all of those cases the link ends up closed and [lastFailure] says why. Cancellation of
     * the caller also closes the link (the read cannot be abandoned any other way) and is
     * rethrown as usual.
     */
    suspend fun exchange(command: String, timeoutMs: Long): String {
        if (!isOpen) return failed(ElmLinkFailure.LINK_CLOSED, command)
        return exchangeMutex.withLock {
            if (!isOpen) return@withLock failed(ElmLinkFailure.LINK_CLOSED, command)
            val startedNs = System.nanoTime()
            log("ELM>> ${printable(command)}")
            val outcome = withTimeoutOrNull(timeoutMs) { runOnIoThread(command, timeoutMs) }
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            when {
                outcome is Outcome.Reply -> {
                    lastFailure = null
                    log("ELM<< ${printable(outcome.text)} (${elapsedMs}ms)")
                    outcome.text
                }
                outcome is Outcome.Failed -> {
                    log("ELM<< FAILED ${outcome.kind} on ${printable(command)} after ${elapsedMs}ms: $closeReason")
                    failed(outcome.kind, command, logIt = false)
                }
                else -> {
                    // Deadline expired: the cancellation handler already closed the link.
                    if (isOpen) close("no reply to ${printable(command)} within ${timeoutMs}ms")
                    log("ELM<< TIMEOUT on ${printable(command)} after ${elapsedMs}ms (link closed)")
                    failed(ElmLinkFailure.TIMEOUT, command, logIt = false)
                }
            }
        }
    }

    /**
     * Non-destructive exchange: writes [command] + CR (a blank [command] sends a bare CR) and
     * collects bytes until the `>` prompt or [timeoutMs], polling `available()` so nothing ever
     * blocks. A timeout does NOT close the link — it returns what arrived (possibly `""`) and
     * the next exchange drains any late bytes first. Only a genuine IO error closes the link.
     *
     * Unlike [exchange], the returned text keeps the terminating `>` when the prompt was seen,
     * so a prompt-only answer (`">"`) is distinguishable from total silence (`""`).
     */
    suspend fun exchangeSoft(command: String, timeoutMs: Long): String {
        if (!isOpen) return failed(ElmLinkFailure.LINK_CLOSED, command)
        return exchangeMutex.withLock {
            if (!isOpen) return@withLock failed(ElmLinkFailure.LINK_CLOSED, command)
            log("ELM>> ${printable(command)} (soft, ${timeoutMs}ms)")
            val startedNs = System.nanoTime()
            val buffer = StringBuilder()
            var promptSeen = false
            val failure: ElmLinkFailure? = withContext(Dispatchers.IO) {
                try {
                    drainStale()
                } catch (_: IOException) {
                    // surfaced by the write below
                }
                try {
                    output.write((command + '\r').toByteArray(Charsets.US_ASCII))
                    output.flush()
                } catch (e: IOException) {
                    if (isOpen) close("write of ${printable(command)} failed: ${e.message}")
                    return@withContext ElmLinkFailure.WRITE_FAILED
                }
                val deadlineNs = startedNs + timeoutMs * 1_000_000
                try {
                    while (!promptSeen && System.nanoTime() < deadlineNs) {
                        if (input.available() <= 0) {
                            delay(SOFT_POLL_MS)
                            continue
                        }
                        val b = input.read()
                        if (b < 0) {
                            if (isOpen) close("EOF (peer closed the link) during ${printable(command)}")
                            return@withContext ElmLinkFailure.EOF
                        }
                        if (b.toChar() == '>') promptSeen = true else buffer.append(b.toChar())
                    }
                    null
                } catch (e: IOException) {
                    if (!isOpen) return@withContext ElmLinkFailure.LINK_CLOSED
                    close("read during ${printable(command)} failed: ${e.message}")
                    ElmLinkFailure.READ_ERROR
                }
            }
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            if (failure != null) return@withLock failed(failure, command)
            lastFailure = null
            val suffix = if (promptSeen) "" else " [no prompt — soft timeout, link kept]"
            log("ELM<< ${printable(buffer.toString())} (${elapsedMs}ms)$suffix")
            if (promptSeen) buffer.append('>')
            buffer.toString()
        }
    }

    private fun failed(kind: ElmLinkFailure, command: String, logIt: Boolean = true): String {
        lastFailure = kind
        if (logIt) log("ELM<< FAILED $kind on ${printable(command)}: $closeReason")
        return ""
    }

    private sealed class Outcome {
        class Reply(val text: String) : Outcome()
        class Failed(val kind: ElmLinkFailure) : Outcome()
    }

    /**
     * Runs the blocking exchange on an IO thread. If this coroutine is cancelled (deadline or
     * caller), the link is closed so the parked thread is released; its late result is then
     * ignored.
     */
    private suspend fun runOnIoThread(command: String, timeoutMs: Long): Outcome =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cause ->
                if (isOpen) {
                    close(
                        if (cause is TimeoutCancellationException) {
                            "no reply to ${printable(command)} within ${timeoutMs}ms"
                        } else {
                            "exchange of ${printable(command)} cancelled"
                        },
                    )
                }
            }
            try {
                IO.execute {
                    // Resuming a cancelled continuation is a no-op.
                    cont.resume(blockingExchange(command))
                }
            } catch (e: RejectedExecutionException) {
                cont.resume(Outcome.Failed(ElmLinkFailure.LINK_CLOSED))
            }
        }

    private fun blockingExchange(command: String): Outcome {
        try {
            drainStale()
        } catch (_: IOException) {
            // surfaced by the write below
        }
        try {
            output.write((command + '\r').toByteArray(Charsets.US_ASCII))
            output.flush()
        } catch (e: IOException) {
            if (!isOpen) return Outcome.Failed(ElmLinkFailure.LINK_CLOSED)
            close("write of ${printable(command)} failed: ${e.message}")
            return Outcome.Failed(ElmLinkFailure.WRITE_FAILED)
        }
        val buffer = StringBuilder()
        try {
            while (true) {
                val byte = input.read()
                if (byte < 0) {
                    if (!isOpen) return Outcome.Failed(ElmLinkFailure.LINK_CLOSED)
                    close("EOF (peer closed the link) during ${printable(command)}")
                    return Outcome.Failed(ElmLinkFailure.EOF)
                }
                val char = byte.toChar()
                if (char == '>') return Outcome.Reply(buffer.toString())
                buffer.append(char)
            }
        } catch (e: IOException) {
            // Our own close() (deadline/cancel/disconnect) also lands here; only an unexpected
            // error counts as a read failure.
            if (!isOpen) return Outcome.Failed(ElmLinkFailure.LINK_CLOSED)
            close("read during ${printable(command)} failed: ${e.message}")
            return Outcome.Failed(ElmLinkFailure.READ_ERROR)
        }
    }

    /**
     * Discards bytes that arrived outside an exchange (a late reply to an earlier command, a
     * spontaneous `STOPPED`/`?`), which would otherwise be read as the answer to the next one.
     */
    private fun drainStale() {
        val stale = StringBuilder()
        while (input.available() > 0) {
            val b = input.read()
            if (b < 0) break
            stale.append(b.toChar())
        }
        if (stale.isNotEmpty()) log("ELM drained stale bytes: ${printable(stale.toString())}")
    }

    companion object {
        private const val MAX_LOG_CHARS = 200
        private const val SOFT_POLL_MS = 10L

        /** Makes CR/LF and control bytes visible in logcat (raw replies are the diagnosis). */
        fun printable(raw: String): String {
            if (raw.isEmpty()) return "\"\""
            val sb = StringBuilder()
            for (c in raw) {
                when {
                    c == '\r' -> sb.append("\\r")
                    c == '\n' -> sb.append("\\n")
                    c.code < 0x20 || c.code in 0x7F..0xFF ->
                        sb.append("\\x").append(c.code.toString(16).uppercase().padStart(2, '0'))
                    else -> sb.append(c)
                }
                if (sb.length >= MAX_LOG_CHARS) {
                    sb.append("…")
                    break
                }
            }
            return sb.toString()
        }

        private val threadCounter = AtomicInteger()

        /**
         * Shared pool for the blocking reads. Cached (not single-threaded) so a read that is
         * still unwinding from a closed socket can never delay the next link's first command.
         * Daemon threads: an abandoned read must not keep the process alive.
         */
        private val IO: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "elm-io-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}
