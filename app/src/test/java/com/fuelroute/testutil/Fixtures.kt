package com.fuelroute.testutil

import kotlinx.serialization.json.Json

/**
 * Loads test fixtures from `app/src/test/resources`. Kept in one place so tests
 * never build classpath-relative paths by hand.
 */
object Fixtures {

    /** JSON decoder matching the production Retrofit config (tolerant of extra API fields). */
    val json: Json = Json { ignoreUnknownKeys = true }

    /** Reads a UTF-8 resource such as `fixtures/routes/3-alternatives.json`. */
    fun read(path: String): String {
        val stream = Fixtures::class.java.classLoader?.getResourceAsStream(path)
            ?: error("Fixture not found on the test classpath: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}