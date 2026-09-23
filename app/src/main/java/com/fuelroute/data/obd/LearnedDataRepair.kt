package com.fuelroute.data.obd

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.fuelroute.data.backup.SpeedBinSnapshot
import com.fuelroute.data.backup.TripSnapshot
import com.fuelroute.data.backup.VehicleSnapshot
import com.fuelroute.data.backup.toSnapshot
import com.fuelroute.data.db.AppDatabase
import com.fuelroute.data.db.ObdSampleDao
import com.fuelroute.data.db.ObdSampleEntity
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.data.db.SpeedBinStatsEntity
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.TripSource
import com.fuelroute.data.db.VehicleDao
import com.fuelroute.data.db.VehicleEntity
import com.fuelroute.domain.learning.EngineDisplacement
import com.fuelroute.domain.learning.LearnedDataRebuilder
import com.fuelroute.domain.learning.LearnedDataRepairPlanner
import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.ObdSample
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.obd.SampleSanitizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time, conservative repair of learned data corrupted before the 0.7 OBD-data fixes
 * (see `docs/changes/0.7-obd-data.md`). Runs in the background at app start and is idempotent:
 * it only acts when there is evidence of corruption, and a repaired vehicle no longer matches.
 *
 * Evidence (per vehicle):
 *  - the stored engine displacement is not a plausible litre value (e.g. `1800` typed in cc),
 *    which made the speed-density fallback 1000x too high; and/or
 *  - a speed bin or closed real trip whose numbers are physically impossible.
 *
 * What it does (pure rules in [LearnedDataRepairPlanner]):
 *  1. streams the vehicle's raw `obd_sample` rows (kept 90 days; demo-trip windows excluded) through
 *     the exact live pipeline to rebuild bins and re-integrate trip fuel;
 *  2. writes a JSON archive of every row it is about to change to
 *     `files/repairs/learned-data-<vehicle>-<time>.json` (nothing is lost silently);
 *  3. in one Room transaction: replaces/deletes the affected bins, updates the affected trips'
 *     `fuelL` / `actualCost`, and stores the normalized displacement.
 *
 * Plausible rows are never touched unless the displacement was wrong (then rows the samples
 * cover are recomputed, because they may be partially inflated while still looking plausible).
 */
@Singleton
class LearnedDataRepair @Inject constructor(
    private val db: AppDatabase,
    private val vehicleDao: VehicleDao,
    private val speedBinDao: SpeedBinDao,
    private val sampleDao: ObdSampleDao,
    private val tripDao: TripDao,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { prettyPrint = true }

    /** Fire-and-forget entry point for `Application.onCreate`. */
    fun launch() {
        scope.launch {
            try {
                repairAll()
            } catch (e: Exception) {
                Log.e(TAG, "learned-data repair failed; data left unchanged", e)
            }
        }
    }

    suspend fun repairAll() {
        for (vehicle in vehicleDao.getAll().first()) {
            repairVehicle(vehicle)
        }
    }

    private suspend fun repairVehicle(vehicle: VehicleEntity) {
        val displacementWasWrong = EngineDisplacement.needsRepair(vehicle.engineDisplacementL)
        val displacement = EngineDisplacement.normalizeLiters(vehicle.engineDisplacementL)
        val maxRate = SampleSanitizer.maxFuelRateLph(displacement) * vehicle.fuelRateCorrection.coerceAtLeast(1.0)

        val storedBins = speedBinDao.getForVehicle(vehicle.id).map { it.toDomain() }
        val allTrips = tripDao.closedForVehicle(vehicle.id)
        val realTrips = allTrips.filter { it.source == TripSource.REAL }
        val tripRows = realTrips.map {
            LearnedDataRepairPlanner.TripRow(it.id, it.distanceKm, it.fuelL, (it.endedAtMs - it.startedAtMs) / 1000.0)
        }

        val badBins = LearnedDataRepairPlanner.badBins(storedBins, maxRate)
        val badTrips = LearnedDataRepairPlanner.badTrips(tripRows, maxRate)
        if (!displacementWasWrong && badBins.isEmpty() && badTrips.isEmpty()) return

        Log.w(
            TAG,
            "learned-data repair for vehicle ${vehicle.id}: displacement=${vehicle.engineDisplacementL} " +
                "(normalized $displacement), ${badBins.size} implausible bin(s), ${badTrips.size} implausible trip(s)",
        )

        val rebuilder = LearnedDataRebuilder(
            fuelType = runCatching { FuelType.valueOf(vehicle.fuelType) }.getOrDefault(FuelType.GASOLINE),
            engineDisplacementL = displacement,
            fuelRateCorrection = vehicle.fuelRateCorrection,
            vehicleId = vehicle.id,
            excludedWindows = allTrips.filter { it.source == TripSource.DEMO }.map { it.startedAtMs..it.endedAtMs },
            tripWindows = realTrips.map { LearnedDataRebuilder.TripWindow(it.id, it.startedAtMs, it.endedAtMs) },
        )
        var afterId = 0L
        while (true) {
            val page = sampleDao.pageForVehicle(vehicle.id, afterId, PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { rebuilder.add(it.toDomain()) }
            afterId = page.last().id
        }

        val plan = LearnedDataRepairPlanner.plan(
            stored = storedBins,
            rebuilt = rebuilder.bins,
            trips = tripRows,
            tripFuel = rebuilder.tripFuel,
            maxFuelRateLph = maxRate,
            displacementWasWrong = displacementWasWrong,
        )

        val touchedBinIndexes = plan.replaceBins.map { it.binIndex }.toSet() + plan.deleteBins
        val archive = RepairArchive(
            createdAtMs = System.currentTimeMillis(),
            reason = "displacementWasWrong=$displacementWasWrong, samplesUsed=${rebuilder.sampleCount}",
            vehicle = vehicle.toSnapshot(),
            oldBins = storedBins.filter { it.binIndex in touchedBinIndexes }.map { it.toEntity().toSnapshot() },
            newBins = plan.replaceBins.map { it.toEntity().toSnapshot() },
            deletedBinIndexes = plan.deleteBins,
            oldTrips = realTrips.filter { it.id in plan.tripFuel.keys }.map { it.toSnapshot() },
            newTripFuelL = plan.tripFuel.mapKeys { it.key.toString() },
        )
        if (!plan.isEmpty && !writeArchive(vehicle.id, archive)) {
            // Never change data we could not archive first.
            Log.e(TAG, "learned-data repair aborted for ${vehicle.id}: archive could not be written")
            return
        }

        db.withTransaction {
            if (plan.replaceBins.isNotEmpty()) speedBinDao.overwrite(plan.replaceBins.map { it.toEntity() })
            plan.deleteBins.forEach { speedBinDao.deleteBin(vehicle.id, it) }
            for (trip in realTrips) {
                val fuel = plan.tripFuel[trip.id] ?: continue
                tripDao.update(
                    trip.copy(
                        fuelL = fuel,
                        actualCost = if (trip.pricePerLiterAtTrip > 0.0) fuel * trip.pricePerLiterAtTrip else trip.actualCost,
                    ),
                )
            }
            if (displacementWasWrong) {
                vehicleDao.upsert(listOf(vehicle.copy(engineDisplacementL = displacement)))
            }
        }
        Log.w(
            TAG,
            "learned-data repair done for ${vehicle.id}: ${plan.replaceBins.size} bin(s) rebuilt, " +
                "${plan.deleteBins.size} bin(s) removed, ${plan.tripFuel.size} trip(s) re-integrated " +
                "from ${rebuilder.sampleCount} raw sample(s)",
        )
    }

    private fun writeArchive(vehicleId: String, archive: RepairArchive): Boolean = try {
        val dir = File(context.filesDir, "repairs").apply { mkdirs() }
        val safeId = vehicleId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "learned-data-$safeId-${archive.createdAtMs}.json")
        file.writeText(json.encodeToString(RepairArchive.serializer(), archive))
        Log.i(TAG, "learned-data repair archive written: ${file.absolutePath}")
        true
    } catch (e: Exception) {
        Log.e(TAG, "could not write repair archive", e)
        false
    }

    @Serializable
    data class RepairArchive(
        val createdAtMs: Long,
        val reason: String,
        val vehicle: VehicleSnapshot,
        val oldBins: List<SpeedBinSnapshot>,
        val newBins: List<SpeedBinSnapshot>,
        val deletedBinIndexes: List<Int>,
        val oldTrips: List<TripSnapshot>,
        val newTripFuelL: Map<String, Double>,
    )

    private fun SpeedBinStatsEntity.toDomain() = SpeedBinStats(vehicleId, binIndex, distanceKm, fuelL, seconds, samples)

    private fun SpeedBinStats.toEntity() = SpeedBinStatsEntity(vehicleId, binIndex, distanceKm, fuelL, seconds, samples)

    private fun ObdSampleEntity.toDomain() = ObdSample(
        timestampMs = timestampMs,
        speedKmh = speedKmh,
        rpm = rpm,
        mafGps = mafGps,
        fuelRateLph = fuelRateLph,
        mapKpa = mapKpa,
        intakeTempC = intakeTempC,
        coolantTempC = coolantTempC,
        engineLoadPct = engineLoadPct,
        fuelLevelPct = fuelLevelPct,
    )

    private companion object {
        const val TAG = "FuelRoute"
        const val PAGE_SIZE = 2_000
    }
}
