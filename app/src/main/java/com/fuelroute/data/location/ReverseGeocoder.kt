package com.fuelroute.data.location

import android.content.Context
import android.location.Geocoder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reverse geocodes a coordinate into a human-readable address.
 *
 * Uses the platform [Geocoder] rather than the legacy
 * `maps.googleapis.com/maps/api/geocode/json` web API. The app's restricted API key only enables
 * Routes, Maps SDK for Android and Places API New, so the legacy web geocoder returns HTTP 403 and
 * would silently yield null. The platform Geocoder is backed by Play services / the Maps SDK for
 * Android, needs no separate key, and keeps this purely in the data layer.
 *
 * Returns null when the device has no geocoder backend or the lookup fails, so callers fall back to
 * a saved label. [Geocoder.getFromLocation] performs blocking I/O, so it runs on [Dispatchers.IO].
 */
@Singleton
class ReverseGeocoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun reverseGeocode(latitude: Double, longitude: Double): String? =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext null
            runCatching {
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                addresses?.firstOrNull()?.let { address ->
                    address.getAddressLine(0)?.takeIf { it.isNotBlank() }
                        ?: address.locality?.takeIf { it.isNotBlank() }
                        ?: address.subAdminArea?.takeIf { it.isNotBlank() }
                        ?: address.countryName?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }
}