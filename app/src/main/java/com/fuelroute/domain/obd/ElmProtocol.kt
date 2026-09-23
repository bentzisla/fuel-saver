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

    val initializationCommands: List<String> = listOf(
        "ATZ",   // reset
        "ATAT1", // adaptive timing on (reduces clone timeouts)
        "ATE0",  // echo off
        "ATL0",  // linefeeds off
        "ATS1", // spaces ON — PidParser tokenizes space-delimited hex; ATS0 would break parsing
        "ATH0",  // headers off
        "ATSP0", // auto-detect protocol
        "ATST64", // set 64 ms timeout (clone-friendly)
    )

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
     * True when an `ATZ` reset reply is an acceptable adapter banner. Recommended STN/OBDLink
     * adapters (and many clones) do not say "ELM327", so accept any non-blank reply that is not
     * an explicit no-answer marker (`NO DATA`, `?`, `UNABLE TO CONNECT`, …).
     */
    fun isAcceptedAdapterBanner(raw: String): Boolean = !PidParser.isError(raw)

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