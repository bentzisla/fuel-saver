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

    /**
     * Builds a Google Maps directions URL.
     *
     * When [encodedPolyline] is supplied it is handed off as a single pass-through waypoint:
     * `waypoints=via:enc:<polyline>:`. The `via:` prefix marks it as a pass-through (no tappable
     * stop), `enc:` marks an encoded polyline and the trailing `:` terminates it. This preserves
     * the route we chose instead of letting Maps pick its own. A coordinate list ([waypoints]) is
     * only a fallback for when no encoded polyline is available.
     *
     * `dir_action=navigate` asks Maps to start turn-by-turn navigation immediately (it degrades to
     * a route preview when the origin is far from the current location).
     *
     * Tradeoff: the consumer `google.com/maps/dir/?api=1` endpoint officially documents `waypoints`
     * as place names/addresses/coordinates. The `via:enc:<polyline>:` form is the Directions-API
     * encoded-polyline syntax; Maps accepts it in practice and it is the only way to pass a full
     * path, but if a given Maps build ignores it the trip falls back to the origin/destination
     * (i.e. Maps' own route). Waze has no equivalent, which is why Waze stays destination-only.
     */
    fun googleMaps(
        destination: NavDestination,
        origin: NavDestination? = null,
        encodedPolyline: String? = null,
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
        val viaPolyline = encodedPolyline?.takeIf { it.isNotBlank() }
        if (viaPolyline != null) {
            params += "waypoints" to "via:enc:$viaPolyline:"
        } else if (waypoints.isNotEmpty()) {
            params += "waypoints" to waypoints.joinToString("|") { (lat, lng) -> "${coord(lat)},${coord(lng)}" }
        }
        params += "dir_action" to "navigate"
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
     * Query-value encoder that keeps every printable ASCII character raw except the ones that
     * would break or alter the query string (`%`, `#`, `&`, `+`, `=`, `"`, `<`, `>`), spaces and
     * non-ASCII bytes (percent-encoded UTF-8). This is what lets the documented
     * `waypoints=via:enc:<polyline>:` form survive verbatim, since encoded polylines use the
     * printable range `?`..`~` which includes `:`, `|`, `~`, `@` and backtick.
     */
    private fun enc(value: String): String {
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder(value.length)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            val keep = v in 0x21..0x7E &&
                c != '%' && c != '#' && c != '&' && c != '+' && c != '=' && c != '"' && c != '<' && c != '>'
            if (keep) {
                sb.append(c)
            } else {
                sb.append('%').append(hex[v shr 4]).append(hex[v and 0xF])
            }
        }
        return sb.toString()
    }
}