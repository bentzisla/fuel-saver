package com.fuelroute.data.obd

import com.fuelroute.domain.obd.ElmLinkFailure
import com.fuelroute.domain.obd.ObdConnectionPolicy

/**
 * Raw line-oriented channel to an ELM327-compatible adapter. Implementations may
 * be real Bluetooth (Classic/BLE) or a scripted fake used during development.
 */
interface ObdTransport {

    val isConnected: Boolean

    val deviceName: String

    /**
     * True when this transport produces a synthetic drive cycle (the "הדגמה" demo)
     * rather than reading a real vehicle, so the recorded trip can be flagged as such.
     */
    val isSimulated: Boolean get() = false

    suspend fun connect(): Result<Unit>

    /**
     * Closes the link. Must be safe to call at any time, from any thread, concurrently with an
     * in-flight [sendCommand] or [connect]: closing is what unblocks a read that is parked on a
     * silent adapter, so it must never wait for that read (or for a command lock).
     */
    suspend fun disconnect()

    /**
     * Sends one ELM/OBD command and returns the raw text response (may span
     * several lines). Never throws for protocol-level failures such as
     * `NO DATA`; those come back as text and are handled by the parser.
     *
     * Uses the transport's default deadline ([ObdConnectionPolicy.COMMAND_TIMEOUT_MS]).
     */
    suspend fun sendCommand(command: String): String

    /**
     * Like [sendCommand] with an explicit deadline. Real transports MUST return within about
     * [timeoutMs] even if the adapter never answers: on expiry they close the link (so
     * [isConnected] becomes false and the engine's reconnect path takes over) and return `""`.
     *
     * The default implementation ignores the deadline, which is only acceptable for in-memory
     * fakes that always answer immediately.
     */
    suspend fun sendCommand(command: String, timeoutMs: Long): String = sendCommand(command)

    /**
     * Non-destructive exchange for the line-clear (blank [command] = bare CR) and reset
     * commands: waits up to [timeoutMs] for the prompt but, unlike [sendCommand], does NOT close
     * the link when the adapter stays silent — a clone that swallows its `ATZ` reply must still
     * get an `ATWS`/`ATD` on the same socket. Returns whatever arrived, keeping the terminating
     * `>` when the prompt was seen, so `""` means the adapter said nothing at all.
     */
    suspend fun sendSoft(command: String, timeoutMs: Long): String = sendCommand(command, timeoutMs)

    /**
     * Why the most recent command returned `""` (write failure, EOF, IO error, deadline, closed
     * link), or `null` when it got a reply / the transport cannot tell.
     */
    val lastFailure: ElmLinkFailure? get() = null
}
