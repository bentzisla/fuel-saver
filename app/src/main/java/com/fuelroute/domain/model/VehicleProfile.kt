package com.fuelroute.domain.model

data class VehicleProfile(
    val id: String,
    val name: String,
    val fuelType: FuelType = FuelType.GASOLINE,
    val ratedCombinedL100: Double = 7.0,
    val engineDisplacementL: Double? = null,
    val manualCurve: List<SpeedPoint>? = null,
    val fuelRateCorrection: Double = 1.0,
    val tankCapacityL: Double? = null,
) {
    val hasManualCurve: Boolean
        get() = !manualCurve.isNullOrEmpty()
}