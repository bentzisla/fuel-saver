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
     * Markers that invalidate the *whole* reply: the adapter tells us the frame it just printed
     * was corrupt (`<DATA ERROR`, `<RX ERROR`), truncated (`STOPPED`, `BUFFER FULL`) or that the
     * bus itself failed. Any hex that happens to precede them cannot be trusted.
     */
    private val CORRUPT_REPLY_MARKERS = listOf(
        "STOPPED",
        "DATA ERROR",
        "RX ERROR",
        "BUFFER FULL",
        "CAN ERROR",
        "BUS ERROR",
        "BUS BUSY",
        "FB ERROR",
    )
    private val ELM_ERROR_CODE = Regex("ERR\\d{2}")

    /** `0:` / `1:` ... prefix of a CAN ISO-TP multi-frame line (ATH0, ATCAF1). */
    private val ISO_TP_INDEX = Regex("^([0-9A-F]):\\s*(.*)$")

    /** Status text an adapter may print on the same line in front of real data. */
    private val NOISE_PREFIX = Regex("^(SEARCHING\\.*|BUS INIT:?\\s*(\\.\\.\\.)?\\s*(OK)?)\\s*")
    private val SPACED_HEX = Regex("^[0-9A-F]{2}( [0-9A-F]{2})*$")
    private val PACKED_HEX = Regex("^([0-9A-F]{2})+$")

    /**
     * Splits a raw ELM reply into complete OBD messages (each a list of bytes), in the order
     * the adapter printed them. Each response line is one message (one per answering ECU);
     * CAN ISO-TP continuation lines (`0:`, `1:`, ...) are joined into a single message.
     * Lines that are not pure hex (echo is kept — it is filtered by the service byte later —
     * but `SEARCHING...`, `NO DATA`, `?`, truncated odd-length hex, byte-count headers like
     * `00A`, ...) are dropped. Returns an empty list when the reply carries a corruption
     * marker (see [CORRUPT_REPLY_MARKERS]).
     */
    fun messages(raw: String): List<List<Int>> {
        val upper = raw.uppercase()
        if (CORRUPT_REPLY_MARKERS.any { upper.contains(it) } || ELM_ERROR_CODE.containsMatchIn(upper)) {
            return emptyList()
        }
        val result = mutableListOf<MutableList<Int>>()
        var isoTpOpen: MutableList<Int>? = null
        for (rawLine in upper.split('\r', '\n', '>')) {
            var line = rawLine.trim()
            if (line.isEmpty()) continue

            var frameIndex: Int? = null
            ISO_TP_INDEX.find(line)?.let { match ->
                frameIndex = match.groupValues[1].toInt(16)
                line = match.groupValues[2].trim()
            }
            line = line.replace(NOISE_PREFIX, "").trim()
            val bytes = hexBytes(line) ?: continue

            when (frameIndex) {
                null -> {
                    isoTpOpen = null
                    result += bytes.toMutableList()
                }
                0 -> {
                    val message = bytes.toMutableList()
                    result += message
                    isoTpOpen = message
                }
                else -> isoTpOpen?.addAll(bytes)
            }
        }
        return result
    }

    /** Bytes of one pure-hex line (`41 0D 3C` or packed `410D3C`), or null for anything else. */
    private fun hexBytes(line: String): List<Int>? = when {
        line.isEmpty() -> null
        SPACED_HEX.matches(line) -> line.split(' ').map { it.toInt(16) }
        PACKED_HEX.matches(line) -> line.chunked(2).map { it.toInt(16) }
        else -> null
    }

    /**
     * Returns the data bytes of a Mode 01 response for [pid], or null when the response is an
     * error, corrupt, truncated, or does not contain the requested PID.
     *
     * Robustness rules (see `docs/changes/0.7-obd-data.md`):
     *  - only a message that *starts* with `41 <pid>` is accepted, so a stale/late reply to a
     *    different PID (desync) is never mistaken for this one, even when its data bytes happen
     *    to contain `41 <pid>`;
     *  - when several ECUs answer, the first message (in reply order) with at least
     *    [minDataBytes] data bytes wins — bytes of different ECUs are never concatenated;
     *  - a truncated message (reply cut off without the `>` prompt) with fewer than
     *    [minDataBytes] data bytes is rejected instead of being decoded from garbage.
     */
    fun parseMode01Bytes(raw: String, pid: Int, minDataBytes: Int = 1): List<Int>? {
        val service = 0x41
        val wantedPid = pid and 0xFF
        for (message in messages(raw)) {
            if (message.size < 2 || message[0] != service || message[1] != wantedPid) continue
            val data = message.subList(2, message.size)
            if (data.size >= minDataBytes) return data.toList()
        }
        return null
    }

    /**
     * Decodes a 32-bit supported-PID bitmap (e.g. PID 0x00, 0x20, 0x40).
     */
    fun parseSupportedPids(raw: String, basePid: Int): Set<Int>? {
        val data = parseMode01Bytes(raw, basePid, minDataBytes = 4) ?: return null

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