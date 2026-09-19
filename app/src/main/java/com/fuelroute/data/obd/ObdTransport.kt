package com.fuelroute.data.obd

/**
 * Raw line-oriented channel to an ELM327-compatible adapter. Implementations may
 * be real Bluetooth (Classic/BLE) or a scripted fake used during development.
 */
interface ObdTransport {

    val isConnected: Boolean

    val deviceName: String

    suspend fun connect(): Result<Unit>

    suspend fun disconnect()

    /**
     * Sends one ELM/OBD command and returns the raw text response (may span
     * several lines). Never throws for protocol-level failures such as
     * `NO DATA`; those come back as text and are handled by the parser.
     */
    suspend fun sendCommand(command: String): String
}