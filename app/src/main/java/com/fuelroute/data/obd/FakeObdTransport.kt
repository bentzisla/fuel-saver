package com.fuelroute.data.obd

import kotlinx.coroutines.delay

/**
 * Replays canned ELM327 responses so the whole pipeline (protocol -> fuel rate
 * -> bins -> UI) can be developed and tested without a car.
 *
 * Two modes:
 *  - map lookup (default): each command is answered from [responses]; unknown
 *    commands return `NO DATA`.
 *  - sequential script replay: pass a list of [ScriptedResponse] (typically via
 *    [parseScript]) and each `sendCommand` consumes the next entry in order,
 *    honouring its optional per-entry delay. This is how recorded sessions in
 *    `app/src/test/resources/fixtures/obd/` are replayed.
 */
class FakeObdTransport(
    private val responses: Map<String, String> = DEFAULT_RESPONSES,
    private val latencyMs: Long = 15L,
    override val deviceName: String = "FakeOBD (ELM327 v1.5)",
) : ObdTransport {

    private var connected = false
    private var scripted: List<ScriptedResponse>? = null
    private var scriptIndex = 0

    /**
     * Sequential replay constructor. Entries are returned in order regardless of
     * the command text (a recording is a timeline, not a dictionary).
     */
    constructor(
        script: List<ScriptedResponse>,
        deviceName: String = "FakeOBD (replay)",
        latencyMs: Long = 0L,
    ) : this(DEFAULT_RESPONSES, latencyMs, deviceName) {
        this.scripted = script
    }

    override val isConnected: Boolean
        get() = connected

    /** Number of script entries already consumed; 0 in map mode. */
    val replayedCount: Int
        get() = scriptIndex

    override suspend fun connect(): Result<Unit> {
        connected = true
        scriptIndex = 0
        if (scripted == null) delay(latencyMs)
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun sendCommand(command: String): String {
        if (!connected) return "UNABLE TO CONNECT"

        val script = scripted
        if (script != null) {
            if (scriptIndex >= script.size) return "NO DATA"
            val entry = script[scriptIndex++]
            if (entry.delayMs > 0L) delay(entry.delayMs)
            return entry.response
        }

        delay(latencyMs)
        return responses[command.trim().uppercase()] ?: "NO DATA"
    }

    /** One line of a recorded ELM session. [delayMs] applies before the reply is sent. */
    data class ScriptedResponse(
        val command: String,
        val response: String,
        val delayMs: Long = 0L,
    )

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
            "ATSTFA" to "OK",
            "ATWS" to "ELM327 v1.5",
            "ATD" to "OK",
            "ATPC" to "OK",
            "ATDPN" to "A6",
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

        /**
         * Parses a recorded session into a replay script.
         *
         * Format (one entry per line, `#` comments and blank lines ignored):
         * ```
         * delay=20
         * 010D> 41 0D 3C
         * 0902> SEARCHING...\n49 02 01 31 ...
         * ```
         * A `delay=NN` line sets the delay (ms) applied before the *next* response.
         * A literal `\n` in the response expands to a newline (multi-frame replies).
         */
        fun parseScript(text: String): List<ScriptedResponse> {
            val entries = mutableListOf<ScriptedResponse>()
            var pendingDelayMs = 0L

            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue

                if (line.startsWith("delay=")) {
                    pendingDelayMs = line.removePrefix("delay=").trim().toLongOrNull() ?: 0L
                    continue
                }

                val separator = line.indexOf('>')
                if (separator <= 0) continue

                val command = line.substring(0, separator).trim()
                val response = line.substring(separator + 1).trim()
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")

                entries += ScriptedResponse(command = command, response = response, delayMs = pendingDelayMs)
                pendingDelayMs = 0L
            }
            return entries
        }
    }
}