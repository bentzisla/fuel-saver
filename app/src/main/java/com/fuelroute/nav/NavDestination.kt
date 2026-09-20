package com.fuelroute.nav

/**
 * The identity of a route endpoint handed off to an external navigation app.
 *
 * [label] is the full human-readable display text ("street, city") and is only a last-resort
 * fallback query. [placeId] (Google only) and [latitude]/[longitude] are the unambiguous forms
 * that stop Maps/Waze from re-geocoding an ambiguous street name into the wrong city.
 */
data class NavDestination(
    val label: String,
    val placeId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)