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
        // Preferred: hand the full chosen path to Maps as a pass-through `via:enc:<polyline>:`
        // waypoint. pickWaypoints (a few evenly spaced stops) is kept only as a fallback when the
        // route has no encoded polyline.
        val waypoints = if (encodedPolyline.isNullOrBlank()) pickWaypoints(encodedPolyline) else emptyList()
        val uri = Uri.parse(
            NavigationUris.googleMaps(
                destination = destination,
                origin = origin,
                encodedPolyline = encodedPolyline,
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
        val points = PolylineDecoder.decode(encoded)
        if (points.size < count + 2) return emptyList()
        return (1..count).map { i ->
            val index = (points.size - 1) * i / (count + 1)
            points[index].lat to points[index].lng
        }
    }
}