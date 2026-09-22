package com.fuelroute.di

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidAppHeadersTest {

    @Test
    fun `adds the android package and cert headers`() {
        val request = AndroidAppHeaders
            .apply(
                Request.Builder().url("https://routes.googleapis.com/directions/v2:computeRoutes"),
                packageName = "com.fuelroute",
                certSha1 = "AA:BB:CC",
            )
            .build()

        assertEquals("com.fuelroute", request.header("X-Android-Package"))
        assertEquals("AA:BB:CC", request.header("X-Android-Cert"))
    }

    @Test
    fun `omits the cert header when the certificate is unavailable`() {
        val request = AndroidAppHeaders
            .apply(
                Request.Builder().url("https://routes.googleapis.com/directions/v2:computeRoutes"),
                packageName = "com.fuelroute",
                certSha1 = null,
            )
            .build()

        assertEquals("com.fuelroute", request.header("X-Android-Package"))
        assertNull(request.header("X-Android-Cert"))
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

        AndroidAppHeadersInterceptor(packageName = "com.fuelroute", certSha1 = "AA:BB:CC").intercept(chain)

        assertEquals("com.fuelroute", forwarded.captured.header("X-Android-Package"))
        assertEquals("AA:BB:CC", forwarded.captured.header("X-Android-Cert"))
    }
}
