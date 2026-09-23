package com.fuelroute.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fuelroute.data.routes.PolylineDecoder

object NavigationLauncher {

    fun openGoogleMaps(
        context: Context,
        destination: NavDestination,
        origin: NavDestination? = null,
        encodedPolyline: String? = null,
    ) {
        // Pass a handful of evenly spaced coordinates from the chosen route as explicit
        // `waypoints`, which the consumer `google.com/maps/dir/?api=1` endpoint officially
        // supports (name/address/coordinate list). We deliberately avoid the `via:enc:<polyline>:`
        // form: it is Directions-API syntax that the `api=1` URL does not document, and on many
        // Maps builds it is ignored or — worse — causes Maps to drop the destination and not start
        // navigation. Reliability of reaching the destination beats forcing the exact path.
        val waypoints = pickWaypoints(encodedPolyline)
        val uri = Uri.parse(
            NavigationUris.googleMaps(
                destination = destination,
                origin = origin,
                waypoints = waypoints,
            ),
        )
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    fun openWaze(
        context: Context,
        destination: NavDestination,
        origin: NavDestination? = null,
    ) {
        val uri = Uri.parse(NavigationUris.waze(destination))
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            openGoogleMaps(context, destination, origin, null)
        }
    }

    private fun pickWaypoints(encoded: String?, count: Int = 4): List<Pair<Double, Double>> {
        if (encoded.isNullOrBlank()) return emptyList()
        val points = PolylineDecoder.decode(encoded)
        if (points.size < count + 2) return emptyList()
        return (1..count).map { i ->
            val index = (points.size - 1) * i / (count + 1)
            points[index].lat to points[index].lng
        }
    }
}