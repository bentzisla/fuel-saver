package com.fuelroute.domain.obd

/**
 * OBD-II Mode 01 PID definitions plus the byte-to-value formulas.
 * Everything here is pure Kotlin and unit-testable without a car.
 */
object ElmProtocol {

    const val PID_SUPPORTED_01_20 = 0x00
    const val PID_SUPPORTED_21_40 = 0x20
    const val PID_SUPPORTED_41_60 = 0x40
    const val PID_SUPPORTED_61_80 = 0x60
    const val PID_ENGINE_LOAD = 0x04
    const val PID_COOLANT_TEMP = 0x05
    const val PID_MAP = 0x0B
    const val PID_RPM = 0x0C
    const val PID_SPEED = 0x0D
    const val PID_INTAKE_TEMP = 0x0F
    const val PID_MAF = 0x10
    const val PID_FUEL_LEVEL = 0x2F
    const val PID_FUEL_RATE = 0x5E

    /** Mode 09 (vehicle information) PID 02 — VIN. */
    const val PID_VIN = 0x02

    /** AT command returning the adapter's measured battery voltage, e.g. `12.3V`. */
    const val CMD_BATTERY_VOLTAGE = "ATRV"

    /** Bitmap PIDs probed during negotiation, in order. */
    val supportedPidBlocks: List<Int> =
        listOf(PID_SUPPORTED_01_20, PID_SUPPORTED_21_40, PID_SUPPORTED_41_60, PID_SUPPORTED_61_80)

    /** Full adapter reset; answers with the identity banner (e.g. `ELM327 v1.5`). */
    const val CMD_RESET = "ATZ"

    /** Warm start: like `ATZ` but skips the LED test; the fallback when `ATZ` misbehaves. */
    const val CMD_WARM_START = "ATWS"

    /**
     * Protocol close. Sent (a) as the sacrificial line-clear command right after the socket
     * opens and (b) best-effort before closing the socket, so the adapter is idle for the next
     * session instead of being left mid-search.
     */
    const val CMD_PROTOCOL_CLOSE = "ATPC"

    /**
     * Set all to defaults (no reboot). Last resort when neither `ATZ` nor `ATWS` produced a
     * banner: an `OK` proves the adapter is alive and parsing commands.
     */
    const val CMD_SET_DEFAULTS = "ATD"

    /** Describe protocol by number; logged once the bus is locked, for diagnostics. */
    const val CMD_DESCRIBE_PROTOCOL_NUMBER = "ATDPN"

    /**
     * Reset attempts in order. A clone whose first `ATZ` answer is garbage / `?` / `STOPPED`
     * (because it was still busy with a previous session's command) gets a second `ATZ`, then
     * a warm start.
     */
    val resetSequence: List<String> = listOf(CMD_RESET, CMD_RESET, CMD_WARM_START)

    /** Configuration sent after a successful reset (a reset restores all defaults). */
    val configurationCommands: List<String> = listOf(
        "ATE0",  // echo off
        "ATL0",  // linefeeds off
        "ATS1", // spaces ON — PidParser tokenizes space-delimited hex; ATS0 would break parsing
        "ATH0",  // headers off
        "ATAT1", // adaptive timing on: AT1 (not the more aggressive AT2 — AT2 shortens the
                 // adapter's own per-request wait further, which is the wrong direction for a
                 // flaky clone) lets the adapter grow its internal wait, up to the ATST ceiling
                 // below, when it observes slow replies.
        "ATSP0", // auto-detect protocol
        // ATST is the ELM327's own bus-response timeout ceiling (each unit is ~4 ms), i.e. the
        // longest the ADAPTER itself will wait for the ECU before it gives up and answers
        // "NO DATA" — separate from, and much shorter than, our own COMMAND_TIMEOUT_MS socket
        // deadline. 0x64 (~400ms) left almost no headroom for a slow K-line/ISO9141/KWP2000
        // bus, where a legitimate reply can take 300-500ms per PID: the adapter would declare
        // "NO DATA" for a perfectly healthy but slow ECU before adaptive timing (ATAT1) got a
        // chance to widen its own estimate. 0xFA (~1000ms) gives real headroom while staying
        // well under our 10s software deadline, so a slow-but-alive bus gets an honest
        // "NO DATA" from the adapter (harmless — see ObdEngine's NO-DATA handling) instead of
        // us hard-timing-out and tearing the RFCOMM socket down for a reply that was still on
        // its way.
        "ATSTFA",
    )

    /** The nominal full init sequence: one reset followed by [configurationCommands]. */
    val initializationCommands: List<String> = listOf(CMD_RESET) + configurationCommands

    /** Result of the first data request after `ATSP0` (automatic protocol search). */
    enum class SearchOutcome {
        /** A valid `41 xx` reply: the bus protocol is locked. */
        LOCKED,

        /** `NO DATA`: the bus answered the search but the ECU is quiet (e.g. ignition off). */
        NO_DATA,

        /**
         * `UNABLE TO CONNECT`, `BUS INIT: ...ERROR`, `CAN ERROR`, `BUS ERROR`, `STOPPED`, `?`
         * or a bare `SEARCHING...`: the search failed and is worth an `ATPC` + retry.
         */
        BUS_ERROR,

        /** Empty reply: the deadline expired and the transport closed the link. */
        NO_REPLY,
    }

    /**
     * Classifies the reply to the first `0100` after `ATSP0`. Note that a successful search
     * prints `SEARCHING...` *and* the data in the same reply, so data wins over the marker.
     */
    fun classifySearchReply(raw: String): SearchOutcome {
        if (raw.isBlank()) return SearchOutcome.NO_REPLY
        if (PidParser.parseSupportedPids(raw, PID_SUPPORTED_01_20) != null) return SearchOutcome.LOCKED
        val cleaned = PidParser.clean(raw).uppercase()
        if (cleaned.contains("NO DATA") || cleaned.contains("NODATA")) return SearchOutcome.NO_DATA
        return SearchOutcome.BUS_ERROR
    }

    fun command(pid: Int): String = "01" + pid.toString(16).uppercase().padStart(2, '0')

    /** Mode 09 command, e.g. PID 02 (VIN) -> `0902`. */
    fun command09(pid: Int): String = "09" + pid.toString(16).uppercase().padStart(2, '0')

    /**
     * Merges the four supported-PID bitmaps (`0100`/`0120`/`0140`/`0160`) into one
     * set. Blocks whose reply is missing or unparseable simply contribute nothing.
     */
    fun supportedPids(replies: Map<Int, String>): Set<Int> = negotiateSupport(replies).pids

    /** Result of probing the supported-PID bitmaps. */
    data class PidSupport(
        val pids: Set<Int>,
        /** True when not a single block reply parsed — the adapter did not negotiate. */
        val negotiationFailed: Boolean,
    )

    /**
     * Like [supportedPids] but also reports whether any block parsed at all. A "supported but
     * empty" set (a valid reply advertising nothing) must be distinguished from a failed
     * negotiation, because the engine falls back to the mandatory trio only on failure.
     */
    fun negotiateSupport(replies: Map<Int, String>): PidSupport {
        val pids = mutableSetOf<Int>()
        var parsedAnyBlock = false
        for (base in supportedPidBlocks) {
            val parsed = PidParser.parseSupportedPids(replies[base] ?: "", base) ?: continue
            parsedAnyBlock = true
            pids += parsed
        }
        return PidSupport(pids = pids, negotiationFailed = !parsedAnyBlock)
    }

    /**
     * True when an `ATZ`/`ATWS` reset reply is an acceptable adapter banner.
     *
     *  - A reply carrying a recognizable identity (`ELM`, `STN`, `OBD`, or a `v1.5`-style
     *    version) is accepted even when surrounded by garbage bytes or a stale `?` — clones
     *    often emit noise on reset, and a leftover `?` from an interrupted previous command
     *    can precede the banner.
     *  - Otherwise accept any non-blank reply that is not an explicit no-answer marker
     *    (`NO DATA`, `?`, `UNABLE TO CONNECT`, `STOPPED`, …) and is not a bare `OK` (which is
     *    the reply to some *other* command, i.e. the stream is out of sync).
     *
     * Recommended STN/OBDLink adapters (and many clones) do not say "ELM327", hence the
     * permissive fallback.
     */
    fun isAcceptedAdapterBanner(raw: String): Boolean {
        // Drop the echoed command itself (echo is ON right after a reset).
        val cleaned = PidParser.clean(raw).uppercase()
            .split(' ')
            .filterNot { it == CMD_RESET || it == CMD_WARM_START }
            .joinToString(" ")
        if (cleaned.isEmpty()) return false
        if (ADAPTER_IDENTITY.containsMatchIn(cleaned)) return true
        if (cleaned == "OK") return false
        return !PidParser.isError(cleaned)
    }

    private val ADAPTER_IDENTITY = Regex("ELM|STN|OBD|\\bV\\d+\\.\\d")

    /**
     * Parses the leading float of an `ATRV` reply (e.g. `12.3V`). Returns null for
     * `NO DATA` / `UNABLE TO CONNECT` / anything without a number.
     */
    fun batteryVoltage(raw: String): Double? {
        val match = VOLTAGE.find(PidParser.clean(raw)) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

    /** PID 0D - vehicle speed, km/h. */
    fun speed(raw: String): Double? =
        PidParser.parseMode01Bytes(raw, PID_SPEED)?.firstOrNull()?.toDouble()

    /** PID 0C - engine RPM. */
    fun rpm(raw: String): Double? {
        val bytes = PidParser.parseMode01Bytes(raw, PID_RPM, minDataBytes = 2) ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 4.0
    }

    /** PID 10 - mass air flow, g/s. */
    fun mafGps(raw: String): Double? {
        val bytes = PidParser.parseMode01Bytes(raw, PID_MAF, minDataBytes = 2) ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 100.0
    }

    /** PID 5E - engine fuel rate, L/h (only on some vehicles). */
    fun fuelRateLph(raw: String): Double? {
        val bytes = PidParser.parseMode01Bytes(raw, PID_FUEL_RATE, minDataBytes = 2) ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 20.0
    }

    /** PID 05 - coolant temperature, degrees Celsius. */
    fun coolantTempC(raw: String): Double? =
        PidParser.parseMode01Bytes(raw, PID_COOLANT_TEMP)?.firstOrNull()?.let { it - 40.0 }

    /** PID 0F - intake air temperature, degrees Celsius. */
    fun intakeTempC(raw: String): Double? =
        PidParser.parseMode01Bytes(raw, PID_INTAKE_TEMP)?.firstOrNull()?.let { it - 40.0 }

    /** PID 0B - intake manifold absolute pressure, kPa. */
    fun mapKpa(raw: String): Double? =
        PidParser.parseMode01Bytes(raw, PID_MAP)?.firstOrNull()?.toDouble()

    /** PID 04 - calculated engine load, percent. */
    fun engineLoadPct(raw: String): Double? =
        PidParser.parseMode01Bytes(raw, PID_ENGINE_LOAD)?.firstOrNull()?.let { it * 100.0 / 255.0 }

    /** PID 2F - fuel tank level, percent. */
    fun fuelLevelPct(raw: String): Double? =
        PidParser.parseMode01Bytes(raw, PID_FUEL_LEVEL)?.firstOrNull()?.let { it * 100.0 / 255.0 }

    fun supportedPids(raw: String): Set<Int>? =
        PidParser.parseSupportedPids(raw, PID_SUPPORTED_01_20)

    /** Mode 09 PID 02 - VIN. */
    fun vin(raw: String): String? = PidParser.parseVin(raw)

    private val VOLTAGE = Regex("([0-9]+(?:\\.[0-9]+)?)")
}