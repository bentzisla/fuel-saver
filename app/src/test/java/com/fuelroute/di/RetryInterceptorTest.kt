package com.fuelroute.di

import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryInterceptorTest {

    private val request = Request.Builder().url("https://routes.googleapis.com/x").build()

    @Test
    fun `retries a 429 then returns the successful response`() {
        val sleeps = mutableListOf<Long>()
        val chain = chainOf(response(429), response(200))
        val interceptor = interceptor(sleeps = sleeps)

        val result = interceptor.intercept(chain)

        assertEquals(200, result.code)
        assertEquals(1, sleeps.size)
    }

    @Test
    fun `retries a 5xx up to the attempt bound and returns the last response`() {
        val sleeps = mutableListOf<Long>()
        val chain = chainOf(response(500), response(503), response(500), response(200))
        val interceptor = interceptor(sleeps = sleeps)

        val result = interceptor.intercept(chain)

        assertEquals(500, result.code)
        assertEquals(2, sleeps.size)
    }

    @Test
    fun `does not retry a successful response`() {
        val sleeps = mutableListOf<Long>()
        val chain = chainOf(response(200))

        val result = interceptor(sleeps = sleeps).intercept(chain)

        assertEquals(200, result.code)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `does not retry a non-transient 4xx`() {
        val sleeps = mutableListOf<Long>()
        val chain = chainOf(response(404), response(200))

        val result = interceptor(sleeps = sleeps).intercept(chain)

        assertEquals(404, result.code)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `honors retry-after seconds on a 429`() {
        val sleeps = mutableListOf<Long>()
        val chain = chainOf(response(429, retryAfter = "2"), response(200))

        interceptor(sleeps = sleeps).intercept(chain)

        assertEquals(listOf(2_000L), sleeps)
    }

    @Test
    fun `honors an http-date retry-after on a 429`() {
        val sleeps = mutableListOf<Long>()
        val chain = chainOf(response(429, retryAfter = "Wed, 21 Oct 2099 07:28:00 GMT"), response(200))

        interceptor(sleeps = sleeps).intercept(chain)

        assertEquals(1, sleeps.size)
        assertTrue(sleeps.single() > 0L)
    }

    private fun interceptor(sleeps: MutableList<Long>) = RetryInterceptor(
        maxAttempts = 3,
        baseDelayMs = 100L,
        maxDelayMs = 1_000L,
        jitterMs = 0L,
        sleep = { sleeps += it },
        random = { 0.0 },
    )

    /** A chain that returns [responses] in order and fails the test if called too often. */
    private fun chainOf(vararg responses: Response): Interceptor.Chain {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        var index = 0
        every { chain.proceed(any()) } answers {
            check(index < responses.size) { "unexpected extra proceed call" }
            responses[index++]
        }
        return chain
    }

    private fun response(code: Int, retryAfter: String? = null): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .apply { retryAfter?.let { header("Retry-After", it) } }
            .build()
}