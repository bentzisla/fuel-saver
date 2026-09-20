package com.fuelroute.nav

import java.util.Locale

/**
 * Pure URI construction for handing a destination off to an external navigation app.
 *
 * Kept free of Android types so it can be unit-tested on the JVM. Ambiguity is removed by
 * preferring, in order: Google `destination_place_id` → explicit `lat,lng` coordinates →
 * the full display label; Waze only understands coordinates or a free-text query.
 */
object NavigationUris {

    private const val MAPS_BASE = "https://www.google.com/maps/dir/"
    private const val WAZE_BASE = "waze://"

    fun googleMaps(
        destination: NavDestination,
        origin: NavDestination? = null,
        waypoints: List<Pair<Double, Double>> = emptyList(),
    ): String {
        val params = mutableListOf<Pair<String, String>>()
        params += "api" to "1"
        origin?.let { o ->
            params += "origin" to mapsEndpoint(o)
            o.nonBlankPlaceId()?.let { params += "origin_place_id" to it }
        }
        params += "destination" to mapsEndpoint(destination)
        destination.nonBlankPlaceId()?.let { params += "destination_place_id" to it }
        params += "travelmode" to "driving"
        if (waypoints.isNotEmpty()) {
            params += "waypoints" to waypoints.joinToString("|") { (lat, lng) -> "${coord(lat)},${coord(lng)}" }
        }
        return MAPS_BASE + "?" + encodeParams(params)
    }

    fun waze(destination: NavDestination): String {
        val params = mutableListOf<Pair<String, String>>()
        if (destination.latitude != null && destination.longitude != null) {
            params += "ll" to "${coord(destination.latitude)},${coord(destination.longitude)}"
        } else {
            params += "q" to destination.label
        }
        params += "navigate" to "yes"
        return WAZE_BASE + "?" + encodeParams(params)
    }

    /** The `destination`/`origin` text parameter: coordinates when known, else the full label. */
    private fun mapsEndpoint(d: NavDestination): String =
        if (d.latitude != null && d.longitude != null) {
            "${coord(d.latitude)},${coord(d.longitude)}"
        } else {
            d.label
        }

    private fun NavDestination.nonBlankPlaceId(): String? = placeId?.takeIf { it.isNotBlank() }

    /** Locale-independent so a device with a comma decimal separator does not corrupt coordinates. */
    fun coord(value: Double): String =
        String.format(Locale.US, "%.7f", value).trimEnd('0').trimEnd('.')

    private fun encodeParams(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (key, value) -> "${enc(key)}=${enc(value)}" }

    /**
     * RFC 3986 query-value encoder that additionally leaves `,` unescaped (coordinates and
     * waypoint separators) and never emits `+` for spaces.
     */
    private fun enc(value: String): String {
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder(value.length)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            val keep = v < 128 && (
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                    c == '-' || c == '_' || c == '.' || c == '~' || c == ','
                )
            if (keep) {
                sb.append(c)
            } else {
                sb.append('%').append(hex[v shr 4]).append(hex[v and 0xF])
            }
        }
        return sb.toString()
    }
}