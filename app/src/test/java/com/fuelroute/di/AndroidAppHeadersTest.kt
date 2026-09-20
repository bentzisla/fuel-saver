package com.fuelroute.di

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAppHeadersTest {

    @Test
    fun `adds the android package and cert headers`() {
        val request = AndroidAppHeaders
            .apply(Request.Builder().url("https://routes.googleapis.com/directions/v2:computeRoutes"))
            .build()

        assertEquals("com.fuelroute", request.header("X-Android-Package"))
        assertEquals(AndroidAppHeaders.DEBUG_CERT_SHA1, request.header("X-Android-Cert"))
    }

    @Test
    fun `interceptor forwards a request carrying the headers`() {
        val original = Request.Builder()
            .url("https://routes.googleapis.com/directions/v2:computeRoutes")
            .build()
        val forwarded = slot<Request>()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns original
        every { chain.proceed(capture(forwarded)) } answers {
            Response.Builder()
                .request(forwarded.captured)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }

        AndroidAppHeadersInterceptor().intercept(chain)

        assertEquals("com.fuelroute", forwarded.captured.header("X-Android-Package"))
        assertEquals(AndroidAppHeaders.DEBUG_CERT_SHA1, forwarded.captured.header("X-Android-Cert"))
    }
}
