package com.fuelroute.domain.obd

/**
 * Pure parsing of ELM327 / OBD-II text responses. It has to survive everything a
 * cheap adapter throws at it: echo, `SEARCHING...`, `NO DATA`, `?`,
 * `UNABLE TO CONNECT`, CAN errors and multi-frame noise.
 */
object PidParser {

    // Unanchored so findAll() can tokenize a hex run regardless of spacing: ATS1 gives
    // "41 0D 3C", ATS0 (spaces off) gives "410D3C" — both must yield the same bytes.
    private val HEX_BYTE = Regex("[0-9A-Fa-f]{2}")

    private val ERROR_MARKERS = listOf(
        "NO DATA",
        "NODATA",
        "UNABLE TO CONNECT",
        "CAN ERROR",
        "BUS INIT",
        "ERROR",
        "STOPPED",
        "TIMEOUT",
        "?",
        // SEARCHING is a not-yet-ready state (bus auto-detect in progress), not a hard
        // error on the vehicle — but a response of only SEARCHING... carries no data.
        "SEARCHING",
        "ACT ALERT",
        "LVP RESET",
        "RTR TIMEOUT",
    )

    fun clean(raw: String): String = raw
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    fun isError(raw: String): Boolean {
        val cleaned = clean(raw).uppercase()
        if (cleaned.isEmpty()) return true
        return ERROR_MARKERS.any { cleaned.contains(it) }
    }

    /**
     * Returns the data bytes of a Mode 01 response for [pid], or null when the
     * response is an error / does not contain the requested PID.
     */
    fun parseMode01Bytes(raw: String, pid: Int): List<Int>? {
        val cleaned = clean(raw)

        // Valid data wins over "noise" markers (echo, SEARCHING...). Extract every 2-hex
        // pair regardless of spacing (spaces-on "41 0D 3C" OR spaces-off "410D3C"), then
        // scan for the `41 <PID>` pair so a response like "SEARCHING...\r41 0D 3C\r>" still
        // parses, while a response that is only "SEARCHING..." (or "NO DATA") has no
        // payload and maps to null below.
        val tokens = HEX_BYTE.findAll(cleaned).map { it.value.toInt(16) }.toList()

        for (i in 0 until tokens.size - 1) {
            if (tokens[i] == 0x41 && tokens[i + 1] == (pid and 0xFF)) {
                return tokens.drop(i + 2)
            }
        }
        return null
    }

    /**
     * Decodes a 32-bit supported-PID bitmap (e.g. PID 0x00, 0x20, 0x40).
     */
    fun parseSupportedPids(raw: String, basePid: Int): Set<Int>? {
        val data = parseMode01Bytes(raw, basePid) ?: return null
        if (data.size < 4) return null

        val result = mutableSetOf<Int>()
        for (i in 0 until 32) {
            val byte = data[i / 8]
            val bit = 7 - (i % 8)
            if ((byte shr bit) and 1 == 1) result.add(basePid + 1 + i)
        }
        return result
    }
}