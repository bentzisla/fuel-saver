package com.fuelroute.data.routes

import org.junit.Assert.assertEquals
import org.junit.Test

class PolylineDecoderTest {

    @Test
    fun `decodes the canonical google example`() {
        val points = PolylineDecoder.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")

        assertEquals(3, points.size)
        assertEquals(38.5, points[0].lat, 1e-9)
        assertEquals(-120.2, points[0].lng, 1e-9)
        assertEquals(40.7, points[1].lat, 1e-9)
        assertEquals(-120.95, points[1].lng, 1e-9)
        assertEquals(43.252, points[2].lat, 1e-9)
        assertEquals(-126.453, points[2].lng, 1e-9)
    }

    @Test
    fun `returns empty for null or blank input`() {
        assertEquals(0, PolylineDecoder.decode(null).size)
        assertEquals(0, PolylineDecoder.decode("").size)
    }

    @Test
    fun `haversine measures a known distance`() {
        val a = PolylineDecoder.LatLng(0.0, 0.0)
        val b = PolylineDecoder.LatLng(0.0, 1.0)
        assertEquals(111_195.0, PolylineDecoder.distanceMeters(a, b), 100.0)
    }
}