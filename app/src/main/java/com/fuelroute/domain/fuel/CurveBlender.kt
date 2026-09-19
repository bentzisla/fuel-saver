package com.fuelroute.domain.fuel

import com.fuelroute.domain.learning.LearnedCurve
import com.fuelroute.domain.model.SpeedPoint

/**
 * Merges the learned (OBD) curve with a fallback curve, weighting each speed by
 * how many kilometers were actually measured in that bin:
 *
 *     w = km / (km + K)
 *
 * With K = 20 km, 20 measured km give the learned value a 50% weight, 80 km give 80%.
 * Speeds with no measurements simply use the fallback curve.
 */
object CurveBlender {

    const val CONFIDENCE_K_KM = 20.0

    private val blendSpeeds = listOf(
        10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0,
        80.0, 90.0, 100.0, 110.0, 120.0, 130.0,
    )

    fun blend(learned: LearnedCurve?, fallback: ConsumptionCurve): ConsumptionCurve {
        if (learned == null || learned.isEmpty) return fallback

        val points = blendSpeeds.map { speed ->
            val fallbackValue = fallback.litersPer100Km(speed)
            val learnedValue = learned.litersPer100Km(speed)
            val value = if (learnedValue == null) {
                fallbackValue
            } else {
                val w = weight(learned.confidenceKm(speed))
                w * learnedValue + (1.0 - w) * fallbackValue
            }
            SpeedPoint(speed, value)
        }
        return ConsumptionCurve(points)
    }

    fun weight(distanceKm: Double): Double {
        if (distanceKm <= 0.0) return 0.0
        return distanceKm / (distanceKm + CONFIDENCE_K_KM)
    }
}