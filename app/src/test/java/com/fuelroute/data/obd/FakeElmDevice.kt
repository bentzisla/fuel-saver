package com.fuelroute.data.obd

import com.fuelroute.domain.obd.ElmLinkFailure
import com.fuelroute.domain.obd.ObdConnectionPolicy
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Input side of a fake RFCOMM socket that behaves like `BluetoothSocket.getInputStream()`:
 * `read()` blocks until a byte arrives and IGNORES thread interrupts (coroutine cancellation
 * cannot unblock it). Only [close] releases a parked reader, with an IOException — exactly the
 * property the production watchdog relies on.
 */
class BlockingSocketInput : InputStream() {
    private val queue = LinkedBlockingQueue<Int>()

    @Volatile
    var closed = false
        private set

    /** Number of threads currently parked in [read]. */
    val parkedReaders = AtomicInteger()

    fun feed(text: String) {
        text.forEach { queue.put(it.code) }
    }

    override fun read(): Int {
        parkedReaders.incrementAndGet()
        try {
            while (true) {
                if (closed) throw IOException("bt socket closed, read return: -1")
                val next = try {
                    queue.poll(20, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null // non-interruptible, like the real socket
                }
                if (next != null) return next
            }
        } finally {
            parkedReaders.decrementAndGet()
        }
    }

    override fun available(): Int {
        if (closed) throw IOException("socket closed")
        return queue.size
    }

    override fun close() {
        closed = true
    }
}

/**
 * Output side: collects bytes until CR and hands the command to [onCommand] (on the writing
 * thread, like the dongle answering asynchronously into the input queue).
 */
class DeviceSocketOutput(private val onCommand: (String) -> Unit) : OutputStream() {
    private val line = StringBuilder()

    @Volatile
    var closed = false
        private set

    override fun write(b: Int) {
        if (closed) throw IOException("Broken pipe")
        val c = b.toChar()
        if (c == '\r') {
            val command = line.toString()
            line.setLength(0)
            onCommand(command)
        } else {
            line.append(c)
        }
    }

    override fun close() {
        closed = true
    }
}

/**
 * Scriptable ELM327 on the far side of the fake socket. [responder] maps a command to its
 * reply (without the prompt) or `null` to stay silent (the hang this whole stream is about).
 */
class FakeElmDevice(
    @Volatile var responder: (String) -> String? = ::healthy,
) {
    val commands = CopyOnWriteArrayList<String>()

    fun open(): Pair<BlockingSocketInput, DeviceSocketOutput> {
        val input = BlockingSocketInput()
        val output = DeviceSocketOutput { command ->
            commands += command
            responder(command)?.let { input.feed("$it\r\r>") }
        }
        return input to output
    }

    companion object {
        fun healthy(command: String): String? = when (command.trim().uppercase()) {
            "" -> "?"
            "ATZ", "ATWS" -> "\r\rELM327 v1.5"
            "0100" -> "SEARCHING...\r41 00 BE 3F A8 13"
            "ATDPN" -> "A6"
            "ATRV" -> "12.6V"
            else -> FakeObdTransport.DEFAULT_RESPONSES[command.trim().uppercase()] ?: "NO DATA"
        }
    }
}

/**
 * [ObdTransport] over [FakeElmDevice] using the PRODUCTION [ElmLink] (the watchdog under test).
 * Every deadline is capped at [timeoutCapMs] so the tests run in seconds.
 */
class StreamObdTransport(
    private val device: FakeElmDevice,
    private val timeoutCapMs: Long = 300L,
) : ObdTransport {

    @Volatile
    private var link: ElmLink? = null

    val connectCount = AtomicInteger()
    val disconnectCount = AtomicInteger()
    val inputs = CopyOnWriteArrayList<BlockingSocketInput>()
    val failures = CopyOnWriteArrayList<ElmLinkFailure>()

    override val isConnected: Boolean get() = link?.isOpen == true
    override val deviceName: String = "FakeElmDevice"
    override val lastFailure: ElmLinkFailure? get() = link?.lastFailure

    override suspend fun connect(): Result<Unit> {
        connectCount.incrementAndGet()
        link?.close("replaced")
        val (input, output) = device.open()
        inputs += input
        link = ElmLink(input, output, label = "fake#${connectCount.get()}")
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        disconnectCount.incrementAndGet()
        link?.close("disconnect()")
    }

    override suspend fun sendCommand(command: String): String =
        sendCommand(command, ObdConnectionPolicy.COMMAND_TIMEOUT_MS)

    override suspend fun sendCommand(command: String, timeoutMs: Long): String {
        val current = link ?: return ""
        return current.exchange(command, minOf(timeoutMs, timeoutCapMs)).also { record(current) }
    }

    override suspend fun sendSoft(command: String, timeoutMs: Long): String {
        val current = link ?: return ""
        return current.exchangeSoft(command, minOf(timeoutMs, timeoutCapMs)).also { record(current) }
    }

    private fun record(current: ElmLink) {
        current.lastFailure?.let { failures += it }
    }
}
