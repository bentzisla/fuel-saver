package com.fuelroute.data.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.fuelroute.domain.obd.ConnectPolicy
import com.fuelroute.domain.obd.ObdConnectionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * Carries the transport's connect-chain reason code (`CONNECT TIMEOUT`, `SOCKET CLOSED`,
 * `SECURITY`, …) up to [com.fuelroute.data.obd.ObdEngine] so the UI can show a specific
 * failure instead of a generic one.
 */
internal class ObdConnectException(
    val reason: String,
    cause: Throwable?,
) : IOException(reason, cause)

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

    /**
     * Best-effort ACL state of the dongle. `BluetoothDevice.isConnected()` is hidden API, so
     * fall back to the bond state when reflection is blocked. Used to stop the reconnect
     * loop from hammering a dongle that is no longer connected.
     */
    val isDeviceAclConnected: Boolean
        @SuppressLint("MissingPermission")
        get() = try {
            val method = BluetoothDevice::class.java.getMethod("isConnected")
            method.isAccessible = true
            method.invoke(device) as? Boolean ?: false
        } catch (_: Throwable) {
            device.bondState == BluetoothDevice.BOND_BONDED
        }

    @SuppressLint("MissingPermission")
    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        var attempt = 1
        while (true) {
            val result = attemptConnect()
            result.exceptionOrNull()?.let { lastError = it }
            if (result.isSuccess) return@withContext result

            if (!ObdConnectionPolicy.shouldRetryConnect(attempt)) break
            Log.w(
                TAG,
                "connect attempt $attempt/${ObdConnectionPolicy.connectAttempts()} failed " +
                    "(${classifyConnectError(lastError)}) — retrying",
                lastError,
            )
            delay(ObdConnectionPolicy.CONNECT_RETRY_BACKOFF_MS)
            attempt++
        }
        val reason = classifyConnectError(lastError)
        Log.e(TAG, "connect to ${device.address} failed after $attempt attempt(s): $reason", lastError)
        Result.failure(ObdConnectException(reason, lastError))
    }

    /**
     * One pass over the workaround chain (secure → insecure → channel 1). Returns the first
     * socket that connects, or the last error when every variant fails.
     */
    @SuppressLint("MissingPermission")
    private suspend fun attemptConnect(): Result<Unit> {
        var lastError: Throwable? = null
        for (variant in ObdConnectionPolicy.connectVariants()) {
            val socket = try {
                createSocket(variant)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                continue
            }

            val connected = connectSocket(socket)
            if (connected.isSuccess) {
                try {
                    applySoTimeout(socket, SO_TIMEOUT_MS)
                    this.socket = socket
                    input = socket.inputStream
                    output = socket.outputStream
                    _connected.value = true
                    return Result.success(Unit)
                } catch (t: Throwable) {
                    runCatching { socket.close() }
                    if (t is CancellationException) throw t
                    lastError = t
                }
            } else {
                runCatching { socket.close() }
                val error = connected.exceptionOrNull()
                lastError = error
                // A timeout means the dongle did not answer at all; the other socket types
                // will not help within this attempt, so fail fast and let the outer retry
                // (with its own backoff) have another go later.
                if (error is SocketTimeoutException) return Result.failure(error)
            }
        }
        return Result.failure(lastError ?: IOException(ObdConnectionPolicy.ERROR_CONNECT))
    }

    private fun createSocket(variant: ObdConnectionPolicy.ConnectVariant): BluetoothSocket =
        when (variant) {
            ObdConnectionPolicy.ConnectVariant.SECURE_RFCOMM ->
                device.createRfcommSocketToServiceRecord(SPP_UUID)

            ObdConnectionPolicy.ConnectVariant.INSECURE_RFCOMM ->
                device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)

            ObdConnectionPolicy.ConnectVariant.CHANNEL_1 -> createChannel1Socket()
        }

    /** Reflection fallback for dongles that only answer on RFCOMM channel 1. */
    private fun createChannel1Socket(): BluetoothSocket {
        val method = BluetoothDevice::class.java.getMethod(
            "createRfcommSocket",
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(device, 1) as BluetoothSocket
    }

    /**
     * Runs the blocking, non-cancellable `BluetoothSocket.connect()` on a sibling coroutine
     * so `withTimeout` can abandon it, then closes the socket to unblock the RFCOMM thread.
     */
    private suspend fun connectSocket(s: BluetoothSocket): Result<Unit> {
        val failure = CompletableDeferred<Throwable?>()
        val connectJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                s.connect()
                failure.complete(null)
            } catch (t: Throwable) {
                failure.complete(t)
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
            return Result.failure(SocketTimeoutException(ObdConnectionPolicy.ERROR_CONNECT_TIMEOUT))
        }
        failure.getCompleted()?.let { return Result.failure(it) }
        return Result.success(Unit)
    }

    /** Maps the chain's last throwable to a machine reason code for the UI. */
    private fun classifyConnectError(cause: Throwable?): String = when {
        cause == null -> ObdConnectionPolicy.ERROR_CONNECT
        cause is SocketTimeoutException -> ObdConnectionPolicy.ERROR_CONNECT_TIMEOUT
        cause is SecurityException -> ObdConnectionPolicy.ERROR_SECURITY
        cause.message?.contains("socket closed", ignoreCase = true) == true ->
            ObdConnectionPolicy.ERROR_SOCKET_CLOSED
        cause.message?.contains("permission", ignoreCase = true) == true ->
            ObdConnectionPolicy.ERROR_SECURITY
        else -> ObdConnectionPolicy.ERROR_CONNECT
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