package com.fuelroute.domain.obd

/**
 * OBD-II Mode 01 PID definitions plus the byte-to-value formulas.
 * Everything here is pure Kotlin and unit-testable without a car.
 */
object ElmProtocol {

    const val PID_SUPPORTED_01_20 = 0x00
    const val PID_ENGINE_LOAD = 0x04
    const val PID_COOLANT_TEMP = 0x05
    const val PID_MAP = 0x0B
    const val PID_RPM = 0x0C
    const val PID_SPEED = 0x0D
    const val PID_INTAKE_TEMP = 0x0F
    const val PID_MAF = 0x10
    const val PID_FUEL_LEVEL = 0x2F
    const val PID_FUEL_RATE = 0x5E

    val initializationCommands: List<String> = listOf(
        "ATZ",   // reset
        "ATAT1", // adaptive timing on (reduces clone timeouts)
        "ATE0",  // echo off
        "ATL0",  // linefeeds off
        "ATS0",  // spaces off
        "ATH0",  // headers off
        "ATSP0", // auto-detect protocol
        "ATST64", // set 64 ms timeout (clone-friendly)
    )

    fun command(pid: Int): String = "01" + pid.toString(16).uppercase().padStart(2, '0')

    /** PID 0D - vehicle speed, km/h. */
    fun speed(raw: String): Double? =
        PidParser.parseMode01Bytes(raw, PID_SPEED)?.firstOrNull()?.toDouble()

    /** PID 0C - engine RPM. */
    fun rpm(raw: String): Double? {
        val bytes = PidParser.parseMode01Bytes(raw, PID_RPM) ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 4.0
    }

    /** PID 10 - mass air flow, g/s. */
    fun mafGps(raw: String): Double? {
        val bytes = PidParser.parseMode01Bytes(raw, PID_MAF) ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 100.0
    }

    /** PID 5E - engine fuel rate, L/h (only on some vehicles). */
    fun fuelRateLph(raw: String): Double? {
        val bytes = PidParser.parseMode01Bytes(raw, PID_FUEL_RATE) ?: return null
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
}