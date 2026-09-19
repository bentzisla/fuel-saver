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

    /**
     * Payload bytes of a Mode 09 response for [pid], with the per-frame framing
     * removed. Handles the three shapes a real adapter produces:
     *
     *  - modern CAN single message: `49 02 01 <17 VIN bytes>` (the `01` is the
     *    "number of data items" and is dropped);
     *  - legacy multi-line: every line is `49 02 <counter> <up to 4 bytes>` (the
     *    counter and the leading zero padding of the first frame are dropped);
     *  - CAN ISO-TP continuation: `0: 49 02 01 ...` followed by `1: ...` lines that
     *    carry no `49 02` marker of their own (the `N:` prefixes are not hex bytes
     *    and are ignored by the tokenizer).
     *
     * Returns null when no `49 <pid>` marker is present.
     */
    fun mode09DataBytes(raw: String, pid: Int): List<Int>? {
        val tokens = HEX_BYTE.findAll(clean(raw)).map { it.value.toInt(16) }.toList()
        if (tokens.isEmpty()) return null

        val frames = mutableListOf<List<Int>>()
        var i = 0
        while (i < tokens.size - 1) {
            if (tokens[i] == 0x49 && tokens[i + 1] == (pid and 0xFF)) {
                val frame = mutableListOf<Int>()
                var j = i + 2
                while (j < tokens.size) {
                    if (j < tokens.size - 1 && tokens[j] == 0x49 && tokens[j + 1] == (pid and 0xFF)) break
                    frame.add(tokens[j])
                    j++
                }
                frames.add(frame)
                i = j
            } else {
                i++
            }
        }
        if (frames.isEmpty()) return null

        val payload = mutableListOf<Int>()
        if (frames.size == 1) {
            val frame = frames.first()
            payload.addAll(if (frame.firstOrNull() == 0x01) frame.drop(1) else frame)
        } else {
            frames.forEach { payload.addAll(it.drop(1)) }
            while (payload.isNotEmpty() && payload.first() == 0x00) payload.removeAt(0)
        }
        return payload
    }

    /**
     * Decodes the 17-character VIN from a Mode 09 PID 02 response. Returns null
     * for a missing/short response or one that is not VIN-shaped (A-Z, 0-9).
     */
    fun parseVin(raw: String): String? {
        val bytes = mode09DataBytes(raw, MODE09_PID_VIN) ?: return null
        if (bytes.size < VIN_LENGTH) return null

        val vin = StringBuilder(VIN_LENGTH)
        for (k in 0 until VIN_LENGTH) {
            val b = bytes[k]
            val valid = b in 0x30..0x39 || b in 0x41..0x5A
            if (!valid) return null
            vin.append(b.toChar())
        }
        return vin.toString()
    }

    private const val MODE09_PID_VIN = 0x02
    private const val VIN_LENGTH = 17
}