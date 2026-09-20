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
        val waypoints = encodedPolyline?.let { pickWaypoints(it) }.orEmpty()
        val uri = Uri.parse(NavigationUris.googleMaps(destination, origin, waypoints))
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

    private fun pickWaypoints(encoded: String, count: Int = 4): List<Pair<Double, Double>> {
        val points = PolylineDecoder.decode(encoded)
        if (points.size < count + 2) return emptyList()
        return (1..count).map { i ->
            val index = (points.size - 1) * i / (count + 1)
            points[index].lat to points[index].lng
        }
    }
}