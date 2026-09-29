package com.fuelroute.domain.export

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {

    @Test
    fun `plain field is not quoted`() {
        assertEquals("abc 123", Csv.field("abc 123"))
    }

    @Test
    fun `null and empty become an empty field`() {
        assertEquals("", Csv.field(null))
        assertEquals("", Csv.field(""))
    }

    @Test
    fun `comma forces quoting`() {
        assertEquals("\"a,b\"", Csv.field("a,b"))
    }

    @Test
    fun `quote is doubled and the field quoted`() {
        assertEquals("\"say \"\"hi\"\"\"", Csv.field("say \"hi\""))
    }

    @Test
    fun `line breaks force quoting`() {
        assertEquals("\"a\nb\"", Csv.field("a\nb"))
        assertEquals("\"a\r\nb\"", Csv.field("a\r\nb"))
        assertEquals("\"a\rb\"", Csv.field("a\rb"))
    }

    @Test
    fun `hebrew text passes through unquoted and quoted when needed`() {
        assertEquals("סיביק ראשי", Csv.field("סיביק ראשי"))
        assertEquals("\"רכב, ראשי\"", Csv.field("רכב, ראשי"))
        assertEquals("\"\"\"ציון\"\" 5\"", Csv.field("\"ציון\" 5"))
    }

    @Test
    fun `row joins with commas, keeps nulls empty and ends with CRLF`() {
        assertEquals("a,,\"b,c\",d\r\n", Csv.row(listOf("a", null, "b,c", "d")))
    }

    @Test
    fun `document starts with a BOM then header and rows`() {
        val doc = Csv.document(listOf("h1", "h2"), listOf(listOf("x", null), listOf("y,z", "w")))
        assertEquals("${Csv.BOM}h1,h2\r\nx,\r\n\"y,z\",w\r\n", doc)
    }

    @Test
    fun `document encodes to UTF-8 with BOM bytes`() {
        val bytes = Csv.document(listOf("ש"), emptyList()).toByteArray(Charsets.UTF_8)
        assertEquals(0xEF.toByte(), bytes[0])
        assertEquals(0xBB.toByte(), bytes[1])
        assertEquals(0xBF.toByte(), bytes[2])
    }

    @Test
    fun `numbers use a dot decimal regardless of default locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("12.35", CsvNumbers.fixed(12.345678, 2))
            assertEquals("", CsvNumbers.fixedOrBlank(null, 2))
            assertEquals("7.5", CsvNumbers.fixedOrBlank(7.5, 1))
            assertEquals("", CsvNumbers.orBlank(null))
            assertEquals("42", CsvNumbers.orBlank(42L))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `iso local date time always includes seconds`() {
        val zone = ZoneId.of("UTC")
        assertEquals("1970-01-01T00:00:00", CsvDates.isoLocal(0L, zone))
        assertEquals("2026-09-28T14:30:05", CsvDates.isoLocal(1_790_605_805_000L, zone))
        assertEquals("1970-01-01T02:00:00", CsvDates.isoLocal(0L, ZoneId.of("Asia/Jerusalem")))
    }
}
