package com.fuelroute.data.obd

import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.domain.learning.LearnedCurve
import com.fuelroute.domain.model.SpeedBinStats
import javax.inject.Inject
import javax.inject.Singleton

interface LearnedCurveRepository {
    suspend fun learnedCurve(vehicleId: String): LearnedCurve
    suspend fun reset(vehicleId: String)
}

@Singleton
class DefaultLearnedCurveRepository @Inject constructor(
    private val speedBinDao: SpeedBinDao,
) : LearnedCurveRepository {

    override suspend fun learnedCurve(vehicleId: String): LearnedCurve {
        val entities = speedBinDao.getForVehicle(vehicleId)
        val bins = entities.map {
            SpeedBinStats(
                vehicleId = it.vehicleId,
                binIndex = it.binIndex,
                distanceKm = it.distanceKm,
                fuelL = it.fuelL,
                seconds = it.seconds,
                samples = it.samples,
            )
        }
        return LearnedCurve(bins)
    }

    override suspend fun reset(vehicleId: String) {
        speedBinDao.resetForVehicle(vehicleId)
    }
}