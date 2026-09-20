package com.fuelroute.nav

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationUrisTest {

    private fun queryValue(url: String, key: String): String? =
        url.substringAfter('?', "")
            .split('&')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
            }
            .firstOrNull { it.first == key }
            ?.second
            ?.let { URLDecoder.decode(it, "UTF-8") }

    @Test
    fun `google maps prefers destination_place_id and keeps travelmode`() {
        val dest = NavDestination(label = "יצחק שדה, הרצליה", placeId = "ChIJ_place", latitude = null, longitude = null)
        val url = NavigationUris.googleMaps(dest)
        assertTrue(url.startsWith("https://www.google.com/maps/dir/?api=1"))
        assertTrue(url.contains("destination_place_id=ChIJ_place"))
        assertTrue(url.contains("travelmode=driving"))
    }

    @Test
    fun `google maps uses a comma-joined coordinate pair when only coordinates are known`() {
        val dest = NavDestination(label = "יצחק שדה, הרצליה", latitude = 32.1234, longitude = 34.8567)
        val url = NavigationUris.googleMaps(dest)
        assertTrue(url.contains("destination=32.1234,34.8567"))
        assertFalse(url.contains("destination_place_id"))
    }

    @Test
    fun `google maps falls back to the full street-city label`() {
        val dest = NavDestination(label = "יצחק שדה, הרצליה", placeId = null)
        val url = NavigationUris.googleMaps(dest)
        val destination = queryValue(url, "destination")
        assertEquals("יצחק שדה, הרצליה", destination)
    }

    @Test
    fun `place id wins over coordinates for the destination parameter`() {
        val dest = NavDestination(
            label = "יצחק שדה, הרצליה",
            placeId = "ChIJ_place",
            latitude = 32.1234,
            longitude = 34.8567,
        )
        val url = NavigationUris.googleMaps(dest)
        assertTrue(url.contains("destination=32.1234,34.8567"))
        assertTrue(url.contains("destination_place_id=ChIJ_place"))
    }

    @Test
    fun `origin place id and coordinates are emitted`() {
        val origin = NavDestination(label = "בית", placeId = "ChIJ_home", latitude = 32.0, longitude = 34.0)
        val dest = NavDestination(label = "יצחק שדה, הרצליה", placeId = "ChIJ_place")
        val url = NavigationUris.googleMaps(dest, origin)
        assertTrue(url.contains("origin=32,34"))
        assertTrue(url.contains("origin_place_id=ChIJ_home"))
    }

    @Test
    fun `waze uses ll when coordinates are known`() {
        val dest = NavDestination(label = "יצחק שדה, הרצליה", latitude = 32.1234, longitude = 34.8567)
        val url = NavigationUris.waze(dest)
        assertTrue(url.startsWith("waze://?"))
        assertTrue(url.contains("ll=32.1234,34.8567"))
        assertTrue(url.contains("navigate=yes"))
        assertFalse(url.contains("q="))
    }

    @Test
    fun `waze falls back to q with the full label`() {
        val dest = NavDestination(label = "יצחק שדה, הרצליה", placeId = "ChIJ_place")
        val url = NavigationUris.waze(dest)
        assertTrue(url.contains("navigate=yes"))
        assertEquals("יצחק שדה, הרצליה", queryValue(url, "q"))
    }

    @Test
    fun `hebrew label with comma round-trips through encoding`() {
        val label = "יצחק שדה, הרצליה"
        val url = NavigationUris.waze(NavDestination(label = label))
        assertEquals(label, queryValue(url, "q"))
        // Percent-encoded UTF-8, space is %20 (never '+'), and no raw spaces leak into the URI.
        assertTrue(url.contains("%20"))
        assertFalse(url.contains("+"))
        assertFalse(url.contains(" "))
    }

    @Test
    fun `coordinate formatting is locale independent and drops trailing zeros`() {
        assertEquals("32.1234", NavigationUris.coord(32.1234))
        assertEquals("34.8567", NavigationUris.coord(34.8567))
        assertEquals("32", NavigationUris.coord(32.0))
        assertEquals("0", NavigationUris.coord(0.0))
    }

    @Test
    fun `waypoints are retained in the google maps url`() {
        val dest = NavDestination(label = "יצחק שדה, הרצליה", placeId = "ChIJ_place")
        val url = NavigationUris.googleMaps(dest, waypoints = listOf(31.5 to 34.5, 31.6 to 34.6))
        val waypoints = queryValue(url, "waypoints")
        assertEquals("31.5,34.5|31.6,34.6", waypoints)
    }
}