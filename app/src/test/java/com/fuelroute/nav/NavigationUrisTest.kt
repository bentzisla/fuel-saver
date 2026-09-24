package com.fuelroute.nav

import java.net.URLDecoder
import java.util.Locale
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
    fun `coordinate formatting ignores a comma-decimal default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // decimal comma separator
            assertEquals("32.1234", NavigationUris.coord(32.1234))
            assertEquals("34.8567", NavigationUris.coord(34.8567))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `google maps does not emit an unsupported via-enc waypoint and requests navigation`() {
        val dest = NavDestination(label = "יצחק שדה, הרצליה", placeId = "ChIJ_place")
        val polyline = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val url = NavigationUris.googleMaps(dest, encodedPolyline = polyline)
        assertTrue(url.contains("dir_action=navigate"))
        assertTrue(url.contains("travelmode=driving"))
        // The `via:enc:` Directions-API syntax is NOT emitted on the consumer api=1 URL — it is
        // unreliable and can drop the destination. Only documented coordinate waypoints are used.
        assertFalse(url.contains("via:enc:"))
        assertFalse(url.contains("enc:"))
        assertTrue(url.contains("destination=יצחק שדה, הרצליה") || url.contains("destination_place_id=ChIJ_place"))
    }

    @Test
    fun `waypoints are omitted cleanly when none are provided`() {
        val dest = NavDestination(label = "יצחק שדה, הרצליה", placeId = "ChIJ_place")
        val url = NavigationUris.googleMaps(dest)
        assertFalse(url.contains("waypoints"))
        assertFalse(url.contains("enc:"))
        assertTrue(url.contains("dir_action=navigate"))
    }

    @Test
    fun `waypoints fall back to coordinates when no polyline is provided`() {
        val dest = NavDestination(label = "יצחק שדה, הרצליה", placeId = "ChIJ_place")
        val url = NavigationUris.googleMaps(dest, waypoints = listOf(31.5 to 34.5, 31.6 to 34.6))
        val waypoints = queryValue(url, "waypoints")
        assertEquals("31.5,34.5|31.6,34.6", waypoints)
    }

    @Test
    fun `waze stays destination only with no waypoints or polyline`() {
        val dest = NavDestination(label = "יצחק שדה, הרצליה", latitude = 32.1234, longitude = 34.8567)
        val url = NavigationUris.waze(dest)
        assertTrue(url.startsWith("waze://?"))
        assertTrue(url.contains("ll=32.1234,34.8567"))
        assertTrue(url.contains("navigate=yes"))
        assertFalse(url.contains("waypoints"))
        assertFalse(url.contains("enc"))
        assertFalse(url.contains("q="))
    }

    @Test
    fun `direct google navigation uri has coordinates and driving mode and no stops`() {
        val dest = NavDestination(label = "Home", latitude = 32.0853, longitude = 34.7818)
        assertEquals("google.navigation:q=32.0853,34.7818&mode=d", NavigationUris.googleNavigation(dest))
    }

    @Test
    fun `direct google navigation falls back to the label`() {
        val dest = NavDestination(label = "Herzliya")
        assertEquals("google.navigation:q=Herzliya&mode=d", NavigationUris.googleNavigation(dest))
    }

    @Test
    fun `omitting the origin lets maps start navigating from the current location`() {
        val dest = NavDestination(label = "x", latitude = 32.0, longitude = 34.0)
        val url = NavigationUris.googleMaps(dest, origin = null, waypoints = listOf(32.01 to 34.01))
        assertEquals(null, queryValue(url, "origin"))
        assertEquals("navigate", queryValue(url, "dir_action"))
        assertEquals("32.01,34.01", queryValue(url, "waypoints"))
    }
}
