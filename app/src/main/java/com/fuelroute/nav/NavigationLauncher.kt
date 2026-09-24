package com.fuelroute.nav

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

object NavigationLauncher {

    private const val MAPS_PACKAGE = "com.google.android.apps.maps"

    /**
     * Opens Google Maps navigation.
     *
     * - No [waypoints] and the trip starts at the current location: `google.navigation:`, which
     *   begins turn-by-turn immediately (no preview, no Start tap, no stops).
     * - Otherwise the directions URL with `dir_action=navigate`. The origin is omitted when the
     *   trip starts here, because Maps only starts navigating on its own from the current
     *   location. [waypoints] should be the few points from
     *   [com.fuelroute.data.routes.NavigationPlanner], never an evenly spaced sample: every one
     *   is shown to the driver as a stop.
     */
    fun openGoogleMaps(
        context: Context,
        destination: NavDestination,
        origin: NavDestination?,
        startsFromCurrentLocation: Boolean,
        waypoints: List<Pair<Double, Double>> = emptyList(),
    ) {
        if (waypoints.isEmpty() && startsFromCurrentLocation) {
            val direct = Intent(Intent.ACTION_VIEW, Uri.parse(NavigationUris.googleNavigation(destination)))
                .setPackage(MAPS_PACKAGE)
            try {
                context.startActivity(direct)
                return
            } catch (_: ActivityNotFoundException) {
                // Maps missing or not handling the scheme: fall through to the web URL.
            }
        }
        val uri = Uri.parse(
            NavigationUris.googleMaps(
                destination = destination,
                origin = if (startsFromCurrentLocation) null else origin,
                waypoints = waypoints,
            ),
        )
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    /** Waze cannot be given a route (its deep link takes a destination only). */
    fun openWaze(
        context: Context,
        destination: NavDestination,
        origin: NavDestination?,
        startsFromCurrentLocation: Boolean,
    ) {
        val uri = Uri.parse(NavigationUris.waze(destination))
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            openGoogleMaps(context, destination, origin, startsFromCurrentLocation)
        }
    }
}
