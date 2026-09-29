package com.fuelroute.domain.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * RFC 4180 CSV formatting, pure Kotlin so it is unit-testable on the JVM with no Android
 * dependency. A field is quoted only when it must be (it contains a comma, a quote, or a line
 * break); an embedded quote is escaped by doubling it. Rows end with CRLF, which is what RFC 4180
 * specifies and what Excel expects. [document] prefixes the file with a UTF-8 BOM so Excel opens
 * Hebrew text as UTF-8 instead of guessing the system codepage and mangling it.
 */
object Csv {
    /** U+FEFF, built from its code point so no literal byte-order mark sits in the source file. */
    val BOM: Char = 0xFEFF.toChar()
    private const val LINE_END = "\r\n"

    /** One field, quoted only when it contains a comma, a quote, or a line break. Null becomes "". */
    fun field(value: String?): String {
        val text = value ?: ""
        val needsQuoting = text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }

    /** One CSV line: comma-joined, RFC-4180-quoted fields, terminated with CRLF. */
    fun row(values: List<String?>): String = values.joinToString(",") { field(it) } + LINE_END

    /** A full CSV document: UTF-8 BOM, header row, then one row per entry of [rows]. */
    fun document(header: List<String>, rows: List<List<String?>>): String = buildString {
        append(BOM)
        append(row(header))
        rows.forEach { append(row(it)) }
    }
}

/** Number formatting shared by every CSV export: always a '.' decimal separator, regardless of locale. */
object CsvNumbers {
    fun fixed(value: Double, digits: Int): String = String.format(Locale.ROOT, "%.${digits}f", value)

    /** Same as [fixed], but blank for a null/not-applicable value instead of "0.00". */
    fun fixedOrBlank(value: Double?, digits: Int): String = value?.let { fixed(it, digits) } ?: ""

    fun orBlank(value: Long?): String = value?.toString() ?: ""
}

/** Local (device-timezone) ISO-8601 date-time, always down to the second, for CSV timestamp columns. */
object CsvDates {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

    fun isoLocal(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(FORMAT)
}
