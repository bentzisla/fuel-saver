package com.fuelroute.domain.model

data class ObdSample(
    val timestampMs: Long,
    val speedKmh: Double? = null,
    val rpm: Double? = null,
    val mafGps: Double? = null,
    val fuelRateLph: Double? = null,
    val mapKpa: Double? = null,
    val intakeTempC: Double? = null,
    val coolantTempC: Double? = null,
    val engineLoadPct: Double? = null,
    val fuelLevelPct: Double? = null,
) {
    val engineRunning: Boolean
        get() = (rpm ?: 0.0) > 0.0
}