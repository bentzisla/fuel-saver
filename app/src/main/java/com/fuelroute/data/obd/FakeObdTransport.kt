package com.fuelroute.data.obd

import kotlinx.coroutines.delay

/**
 * Replays canned ELM327 responses so the whole pipeline (protocol -> fuel rate
 * -> bins -> UI) can be developed and tested without a car.
 */
class FakeObdTransport(
    private val responses: Map<String, String> = DEFAULT_RESPONSES,
    private val latencyMs: Long = 15L,
    override val deviceName: String = "FakeOBD (ELM327 v1.5)",
) : ObdTransport {

    private var connected = false

    override val isConnected: Boolean
        get() = connected

    override suspend fun connect(): Result<Unit> {
        delay(latencyMs)
        connected = true
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun sendCommand(command: String): String {
        if (!connected) return "UNABLE TO CONNECT"
        delay(latencyMs)
        return responses[command.trim().uppercase()] ?: "NO DATA"
    }

    companion object {
        val DEFAULT_RESPONSES: Map<String, String> = mapOf(
            "ATZ" to "ELM327 v1.5",
            "ATE0" to "OK",
            "ATL0" to "OK",
            "ATS0" to "OK",
            "ATS1" to "OK",
            "ATH0" to "OK",
            "ATSP0" to "OK",
            "ATAT1" to "OK",
            "ATST64" to "OK",
            "ATRV" to "12.6V",
            "0100" to "41 00 BE 3F A8 13",
            "0120" to "41 20 80 00 00 00",
            "0140" to "NO DATA",
            "0160" to "NO DATA",
            "0902" to "49 02 01 31 48 47 43 4D 38 32 36 33 33 41 30 30 34 33 35 32",
            "010D" to "41 0D 3C",
            "010C" to "41 0C 1A F8",
            "0110" to "41 10 05 78",
            "015E" to "41 5E 00 96",
            "0105" to "41 05 7B",
            "010B" to "41 0B 64",
            "010F" to "41 0F 40",
        )
    }
}