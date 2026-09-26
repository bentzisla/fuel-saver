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
    val vin: String? = null,
    val grade: String = "95",
    /**
     * Curb weight used for the elevation/grade fuel term ([com.fuelroute.domain.fuel.GradeModel]).
     * Defaults to a typical private car (PLAN.md §4.4); vehicles persisted before this field
     * existed read back at the same default via the DB migration / backup import.
     */
    val massKg: Double = com.fuelroute.domain.fuel.GradeModel.DEFAULT_VEHICLE_MASS_KG,
) {
    val hasManualCurve: Boolean
        get() = !manualCurve.isNullOrEmpty()
}