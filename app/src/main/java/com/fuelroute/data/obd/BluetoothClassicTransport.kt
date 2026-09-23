package com.fuelroute.data.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import android.util.Log
import com.fuelroute.domain.obd.ElmLinkFailure
import com.fuelroute.domain.obd.ObdConnectionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
 * Real ELM327 transport over Bluetooth Classic SPP.
 *
 * Link hygiene for cheap single-connection clones (the "works only after re-plugging the
 * dongle" bug):
 *  - **single-flight** connect per dongle address, process-wide (two transports, e.g. an ACL
 *    auto-start racing a manual connect or a service re-arm, can never open two RFCOMM links);
 *  - discovery is cancelled before every `connect()`;
 *  - every socket from every failed/abandoned/cancelled attempt is closed, and the next
 *    connect waits [ObdConnectionPolicy.RFCOMM_RELEASE_MS] after the last close so the clone
 *    can release its RFCOMM channel;
 *  - [disconnect] also aborts a connect that is still in flight.
 *
 * Exchanges go through [ElmLink], whose per-command deadline really fires (it closes the
 * socket to unblock the read). Every connect step and raw command/reply is logged under the
 * `FuelRoute` tag: `adb logcat -s FuelRoute:*`.
 */
class BluetoothClassicTransport(
    private val device: BluetoothDevice,
) : ObdTransport {

    @Volatile
    private var link: ElmLink? = null

    /** Socket of a connect that is still in flight, so [disconnect] can abort it. */
    @Volatile
    private var pendingSocket: BluetoothSocket? = null

    /** Bumped by [disconnect]; a connect started under an older epoch aborts. */
    private val epoch = AtomicLong()

    override val isConnected: Boolean
        get() = link?.isOpen == true

    override val lastFailure: ElmLinkFailure?
        get() = link?.lastFailure ?: lastLinkFailure

    /** Failure of the previous (now discarded) link, kept so the engine can still classify it. */
    @Volatile
    private var lastLinkFailure: ElmLinkFailure? = null

    override val deviceName: String
        get() = try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }

    private val address: String
        get() = device.address

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
        val startEpoch = epoch.get()
        // A reconnect always starts from a fully closed previous link.
        link?.let { old ->
            old.close("replaced by a new connect")
            lastLinkFailure = old.lastFailure
        }
        link = null

        val gate = connectLock(address)
        if (gate.isLocked) Log.i(TAG, "connect $address: waiting for another in-flight connect")
        gate.withLock {
            Log.i(TAG, "connect $address: begin (${deviceName})")
            cancelDiscovery()
            var lastError: Throwable? = null
            var attempt = 1
            while (true) {
                if (epoch.get() != startEpoch) {
                    Log.i(TAG, "connect $address: aborted by disconnect()")
                    return@withContext Result.failure(
                        ObdConnectException(ObdConnectionPolicy.ERROR_CONNECT, IOException("aborted")),
                    )
                }
                val result = attemptConnect(startEpoch)
                result.exceptionOrNull()?.let { lastError = it }
                if (result.isSuccess) {
                    Log.i(TAG, "connect $address: RFCOMM link up (attempt $attempt)")
                    return@withContext result
                }

                if (!ObdConnectionPolicy.shouldRetryConnect(attempt)) break
                Log.w(
                    TAG,
                    "connect $address: attempt $attempt/${ObdConnectionPolicy.connectAttempts()} failed " +
                        "(${classifyConnectError(lastError)}: ${lastError?.message}) — retrying",
                )
                delay(ObdConnectionPolicy.CONNECT_RETRY_BACKOFF_MS)
                attempt++
            }
            val reason = classifyConnectError(lastError)
            Log.e(TAG, "connect $address: failed after $attempt attempt(s): $reason", lastError)
            Result.failure(ObdConnectException(reason, lastError))
        }
    }

    /**
     * One pass over the workaround chain (secure → insecure → channel 1). Returns the first
     * socket that connects, or the last error when every variant fails. Every socket that does
     * not become the live link is closed before the next variant is tried.
     */
    @SuppressLint("MissingPermission")
    private suspend fun attemptConnect(startEpoch: Long): Result<Unit> {
        var lastError: Throwable? = null
        for (variant in ObdConnectionPolicy.connectVariants()) {
            waitForRfcommRelease()
            if (epoch.get() != startEpoch) return Result.failure(IOException("aborted"))

            val socket = try {
                createSocket(variant)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(TAG, "connect $address: create $variant socket failed: ${t.message}")
                lastError = t
                continue
            }

            Log.i(TAG, "connect $address: trying $variant")
            pendingSocket = socket
            val connected = try {
                connectSocket(socket)
            } catch (e: CancellationException) {
                closeSocket(socket, "connect cancelled ($variant)")
                throw e
            } finally {
                pendingSocket = null
            }

            if (connected.isSuccess) {
                if (epoch.get() != startEpoch) {
                    // disconnect() raced the successful connect: do not leak the socket.
                    closeSocket(socket, "disconnect() during connect ($variant)")
                    return Result.failure(IOException("aborted"))
                }
                return try {
                    link = ElmLink(
                        input = socket.inputStream,
                        output = socket.outputStream,
                        label = "$address/$variant",
                        closeAction = { closeSocket(socket, null) },
                        log = { Log.d(TAG, it) },
                    )
                    lastLinkFailure = null
                    Result.success(Unit)
                } catch (t: Throwable) {
                    closeSocket(socket, "stream setup failed ($variant)")
                    Result.failure(t)
                }
            }

            closeSocket(socket, "$variant failed")
            val error = connected.exceptionOrNull()
            lastError = error
            Log.w(TAG, "connect $address: $variant failed: ${error?.javaClass?.simpleName}: ${error?.message}")
            // A timeout means the dongle did not answer at all; the other socket types
            // will not help within this attempt, so fail fast and let the outer retry
            // (with its own backoff) have another go later.
            if (error is SocketTimeoutException) return Result.failure(error)
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
     * Runs the blocking, non-cancellable `BluetoothSocket.connect()` on its own thread so the
     * caller can give up after [ObdConnectionPolicy.CONNECT_TIMEOUT_MS] (or be cancelled). The
     * caller closes the socket on every non-success, which also aborts the parked connect.
     */
    private suspend fun connectSocket(s: BluetoothSocket): Result<Unit> {
        val done = CompletableDeferred<Result<Unit>>()
        Thread({
            done.complete(runCatching { s.connect() })
        }, "bt-connect").apply { isDaemon = true }.start()

        val outcome = withTimeoutOrNull(ObdConnectionPolicy.CONNECT_TIMEOUT_MS) { done.await() }
        if (outcome == null) {
            Log.w(TAG, "connect $address: timed out after ${ObdConnectionPolicy.CONNECT_TIMEOUT_MS}ms")
            return Result.failure(SocketTimeoutException(ObdConnectionPolicy.ERROR_CONNECT_TIMEOUT))
        }
        return outcome
    }

    /** Waits until the dongle has had [ObdConnectionPolicy.RFCOMM_RELEASE_MS] since our last close. */
    private suspend fun waitForRfcommRelease() {
        val waitMs = ObdConnectionPolicy.rfcommReleaseWaitMs(lastCloseAt[key(address)], SystemClock.elapsedRealtime())
        if (waitMs > 0) {
            Log.i(TAG, "connect $address: waiting ${waitMs}ms for the dongle to release the previous RFCOMM link")
            delay(waitMs)
        }
    }

    /**
     * Discovery (e.g. a device scan left running) starves RFCOMM connects. Needs
     * BLUETOOTH_SCAN on API 31+; when it is not granted there is nothing we can do about a
     * running scan anyway.
     */
    @SuppressLint("MissingPermission")
    private fun cancelDiscovery() {
        try {
            @Suppress("DEPRECATION")
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            if (adapter.isDiscovering) {
                Log.i(TAG, "connect $address: cancelling running discovery")
            }
            adapter.cancelDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "connect $address: cannot cancel discovery (BLUETOOTH_SCAN not granted)")
        } catch (t: Throwable) {
            Log.w(TAG, "connect $address: cancelDiscovery failed: ${t.message}")
        }
    }

    private fun closeSocket(s: BluetoothSocket, reason: String?) {
        if (reason != null) Log.i(TAG, "connect $address: closing socket ($reason)")
        runCatching { s.close() }
        lastCloseAt[key(address)] = SystemClock.elapsedRealtime()
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

    /**
     * Closes the link and aborts an in-flight connect. Never waits for a pending read or the
     * connect lock: closing the socket is exactly what unblocks them.
     */
    override suspend fun disconnect() {
        epoch.incrementAndGet()
        pendingSocket?.let { closeSocket(it, "disconnect() while connecting") }
        link?.let { current ->
            current.close("disconnect()")
            lastLinkFailure = current.lastFailure
        }
        link = null
    }

    override suspend fun sendCommand(command: String): String =
        sendCommand(command, ObdConnectionPolicy.COMMAND_TIMEOUT_MS)

    override suspend fun sendCommand(command: String, timeoutMs: Long): String {
        val current = link ?: return ""
        return current.exchange(command, timeoutMs)
    }

    override suspend fun sendSoft(command: String, timeoutMs: Long): String {
        val current = link ?: return ""
        return current.exchangeSoft(command, timeoutMs)
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "FuelRoute"

        /** Process-wide single-flight connect lock per dongle address. */
        private val connectLocks = ConcurrentHashMap<String, Mutex>()

        /** Process-wide time (elapsedRealtime) we last closed a socket to each dongle. */
        private val lastCloseAt = ConcurrentHashMap<String, Long>()

        private fun key(address: String): String = address.uppercase()

        /**
         * True while this process is running the connect chain for [address]. Each variant that
         * fails closes its socket, which makes the ACL link flap; the ACL receiver uses this to
         * avoid treating our own connect attempts as "the dongle went away".
         */
        fun isConnectInFlight(address: String): Boolean =
            connectLocks[key(address)]?.isLocked == true

        private fun connectLock(address: String): Mutex =
            connectLocks.getOrPut(key(address)) { Mutex() }
    }
}
