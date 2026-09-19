package com.fuelroute.data.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Real ELM327 transport over Bluetooth Classic SPP. Reads until the ELM `>` prompt
 * (a character at a time, with a deadline), which is the reliable end-of-response
 * marker common to cheap adapters.
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
        runCatching {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            input = s.inputStream
            output = s.outputStream
            _connected.value = true
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

        runCatching {
            out.write((command + '\r').toByteArray(Charsets.US_ASCII))
            out.flush()
        }.onFailure {
            return@withContext ""
        }

        readUntilPrompt(stream)
    }

    private fun readUntilPrompt(stream: java.io.InputStream): String {
        val buffer = StringBuilder()
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (stream.available() > 0) {
                val byte = stream.read()
                if (byte < 0) break
                val char = byte.toChar()
                if (char == '>') break
                buffer.append(char)
            } else {
                Thread.sleep(POLL_SLEEP_MS)
            }
        }
        return buffer.toString()
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val READ_TIMEOUT_MS = 2_000L
        private const val POLL_SLEEP_MS = 5L
    }
}