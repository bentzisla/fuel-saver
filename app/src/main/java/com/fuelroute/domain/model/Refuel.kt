package com.fuelroute.domain.model

data class Refuel(
    val id: Long = 0,
    val timestampMs: Long,
    val liters: Double,
    val totalPrice: Double,
    val isFull: Boolean,
    val vehicleId: String,
) {
    val pricePerLiter: Double
        get() = if (liters > 0.0) totalPrice / liters else 0.0
}