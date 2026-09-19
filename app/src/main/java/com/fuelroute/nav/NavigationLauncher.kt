package com.fuelroute.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fuelroute.data.routes.PolylineDecoder

object NavigationLauncher {

    fun openGoogleMaps(context: Context, origin: String, destination: String, encodedPolyline: String?) {
        val waypoints = encodedPolyline?.let { pickWaypoints(it) }.orEmpty()

        val builder = Uri.Builder()
            .scheme("https")
            .authority("www.google.com")
            .path("/maps/dir/")
            .appendQueryParameter("api", "1")
            .appendQueryParameter("origin", origin)
            .appendQueryParameter("destination", destination)
            .appendQueryParameter("travelmode", "driving")
        if (waypoints.isNotEmpty()) {
            builder.appendQueryParameter("waypoints", waypoints.joinToString("|") { "${it.first},${it.second}" })
        }

        context.startActivity(Intent(Intent.ACTION_VIEW, builder.build()))
    }

    fun openWaze(context: Context, destination: String) {
        val uri = Uri.parse("waze://?q=${Uri.encode(destination)}&navigate=yes")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            openGoogleMaps(context, "", destination, null)
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