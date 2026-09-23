package com.fuelroute.domain.model

import com.fuelroute.domain.learning.LearnedDataPlausibility

data class Trip(
    val id: Long = 0,
    val vehicleId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceKm: Double,
    val fuelL: Double,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val idleSeconds: Double,
) {
    val durationSeconds: Double
        get() = (endedAtMs - startedAtMs) / 1000.0

    /**
     * Average L/100 km, or null when it is not meaningful: the trip is shorter than
     * [LearnedDataPlausibility.MIN_TRIP_DISTANCE_KM] (fuel / tiny distance explodes) or its
     * stored fuel is physically implausible (e.g. a trip recorded before the displacement /
     * sanity fixes that could not be repaired from raw samples).
     */
    val litersPer100Km: Double?
        get() = LearnedDataPlausibility.tripLitersPer100Km(distanceKm, fuelL, durationSeconds)

    /** False when the stored fuel figure cannot be real (see [LearnedDataPlausibility.isTripPlausible]). */
    val isFuelPlausible: Boolean
        get() = LearnedDataPlausibility.isTripPlausible(distanceKm, fuelL, durationSeconds)
}
