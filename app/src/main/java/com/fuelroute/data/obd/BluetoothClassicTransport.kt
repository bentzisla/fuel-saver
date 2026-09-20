package com.fuelroute.data.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.fuelroute.domain.obd.ConnectPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * Real ELM327 transport over Bluetooth Classic SPP. Uses a blocking read bounded by a
 * socket read-timeout (NOT polling `available()`, which is unreliable on a real Bluetooth
 * socket) and stops at the ELM `>` prompt. Every command/reply is logged as raw traffic so
 * the connection can be diagnosed with `adb logcat -s FuelRoute:*`.
 */
class BluetoothClassicTransport(
    private val device: BluetoothDevice,
) : ObdTransport {

    private var socket: BluetoothSocket? = null
    private var input: java.io.InputStream? = null
    private var output: java.io.OutputStream? = null

    private val _connected = kotlinx.coroutines.flow.MutableStateFlow(false)

    override val isConnected: Boolean
        get() = _connected.value

    override val deviceName: String
        get() = try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }

    @SuppressLint("MissingPermission")
    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        val s = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return@withContext Result.failure(t)
        }

        // `BluetoothSocket.connect()` is a blocking, non-cancellable call. Run it on a
        // sibling coroutine so `withTimeout` can abandon it, then close the socket to
        // unblock the underlying RFCOMM thread. Without this the service lingered for as
        // long as the ROM kept the connect pending while the dongle was absent.
        var failure: Throwable? = null
        val connectJob = launch {
            try {
                s.connect()
            } catch (t: Throwable) {
                failure = t
            }
        }
        val timedOut = try {
            withTimeout(ConnectPolicy.CONNECT_TIMEOUT_MS) { connectJob.join() }
            false
        } catch (_: TimeoutCancellationException) {
            true
        }

        if (timedOut) {
            Log.w(TAG, "connect to ${device.address} timed out after ${ConnectPolicy.CONNECT_TIMEOUT_MS}ms")
            runCatching { s.close() }
            connectJob.cancel()
            return@withContext Result.failure(SocketTimeoutException("connect timeout"))
        }

        failure?.let { cause ->
            runCatching { s.close() }
            return@withContext Result.failure(cause)
        }

        try {
            applySoTimeout(s, SO_TIMEOUT_MS)
            socket = s
            input = s.inputStream
            output = s.outputStream
            _connected.value = true
            Result.success(Unit)
        } catch (t: Throwable) {
            runCatching { s.close() }
            if (t is CancellationException) throw t
            Result.failure(t)
        }
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        _connected.value = false
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    override suspend fun sendCommand(command: String): String = withContext(Dispatchers.IO) {
        val out = output ?: return@withContext ""
        val stream = input ?: return@withContext ""

        Log.d(TAG, "ELM>> $command")
        val started = System.currentTimeMillis()

        runCatching {
            drain(stream)
            out.write((command + '\r').toByteArray(Charsets.US_ASCII))
            out.flush()
        }.onFailure { e ->
            Log.e(TAG, "ELM write failed", e)
            return@withContext ""
        }

        val reply = readUntilPrompt(stream)
        val elapsed = System.currentTimeMillis() - started
        Log.d(TAG, "ELM<< ${truncate(reply)} (${elapsed}ms)")
        reply
    }

    /**
     * `android.bluetooth.BluetoothSocket` has no public `setSoTimeout` and blocks forever
     * on a non-responsive adapter, so reach the underlying `java.net.Socket` via reflection
     * (best effort). If hidden-API policy blocks it, a stuck read is instead interrupted by
     * `disconnect()` closing the socket.
     */
    private fun applySoTimeout(bt: BluetoothSocket, timeoutMs: Int) {
        try {
            val field = BluetoothSocket::class.java.getDeclaredField("mSocket")
            field.isAccessible = true
            (field.get(bt) as? java.net.Socket)?.soTimeout = timeoutMs
        } catch (_: Throwable) {
            // ignore — see KDoc above
        }
    }

    /**
     * Blocking read: each `read()` waits up to the socket's read-timeout. Accumulate bytes
     * until the ELM `>` prompt, a timeout, EOF or an IO error. On timeout we still return
     * whatever was buffered (a partial reply beats an empty string that hides the data).
     */
    private fun readUntilPrompt(stream: java.io.InputStream): String {
        val buffer = StringBuilder()
        while (true) {
            val byte = try {
                stream.read()
            } catch (_: SocketTimeoutException) {
                break
            } catch (_: IOException) {
                break
            }
            if (byte < 0) break
            val char = byte.toChar()
            if (char == '>') break
            buffer.append(char)
        }
        return buffer.toString()
    }

    private fun drain(stream: java.io.InputStream) {
        try {
            while (stream.available() > 0) {
                stream.read()
            }
        } catch (_: IOException) {
            // best effort; a stale byte is not fatal
        }
    }

    private fun truncate(reply: String): String =
        if (reply.length <= MAX_LOG_CHARS) reply
        else reply.take(MAX_LOG_CHARS) + "…"

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "FuelRoute"
        private const val SO_TIMEOUT_MS = 1_500
        private const val MAX_LOG_CHARS = 160
    }
}