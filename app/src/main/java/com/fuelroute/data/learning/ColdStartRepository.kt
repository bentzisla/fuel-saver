package com.fuelroute.data.learning

import com.fuelroute.data.db.LearningExtrasDao
import com.fuelroute.data.db.LearningExtrasEntity
import com.fuelroute.domain.learning.ColdStartStats
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the learned cold-start extra fuel per vehicle. The learning maths lives in
 * `domain/learning/ColdStartLearner`; this is only the thin storage wrapper.
 */
interface ColdStartRepository {
    /** Running cold-start stats for [vehicleId], defaulting when nothing is learned yet. */
    suspend fun stats(vehicleId: String): ColdStartStats

    /** Folds one observed cold-start trip extra into the running mean and persists it. */
    suspend fun record(vehicleId: String, tripExtraL: Double): ColdStartStats

    suspend fun reset(vehicleId: String)
}

@Singleton
class DefaultColdStartRepository @Inject constructor(
    private val dao: LearningExtrasDao,
) : ColdStartRepository {

    override suspend fun stats(vehicleId: String): ColdStartStats {
        val entity = dao.get(vehicleId) ?: return ColdStartStats.initial()
        return ColdStartStats(meanExtraL = entity.coldStartExtraL, count = entity.coldStartCount)
    }

    override suspend fun record(vehicleId: String, tripExtraL: Double): ColdStartStats {
        val updated = ColdStartStats.runningMean(stats(vehicleId), tripExtraL)
        dao.upsert(
            LearningExtrasEntity(
                vehicleId = vehicleId,
                coldStartExtraL = updated.meanExtraL,
                coldStartCount = updated.count,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        return updated
    }

    override suspend fun reset(vehicleId: String) = dao.reset(vehicleId)
}
