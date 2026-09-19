package com.fuelroute.domain.model

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

    val litersPer100Km: Double?
        get() = if (distanceKm > 0.0001) fuelL / distanceKm * 100.0 else null
}