package com.fuelroute.data.db

import com.fuelroute.domain.learning.SpeedBinAggregator
import com.fuelroute.domain.model.ObdSample
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.mergeSpeedBins
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory [SpeedBinDao]: the abstract Room queries are emulated, while the real
 * [SpeedBinDao.addDeltas] (update-then-insert) logic runs unchanged.
 */
class FakeSpeedBinDao : SpeedBinDao() {
    val rows = linkedMapOf<Pair<String, Int>, SpeedBinStatsEntity>()

    override suspend fun overwrite(bins: List<SpeedBinStatsEntity>) {
        bins.forEach { rows[it.vehicleId to it.binIndex] = it }
    }

    override suspend fun getForVehicle(vehicleId: String) =
        rows.values.filter { it.vehicleId == vehicleId }.sortedBy { it.binIndex }

    override suspend fun getAll() = rows.values.toList()

    override suspend fun totalFuelForVehicle(vehicleId: String) =
        rows.values.filter { it.vehicleId == vehicleId }.sumOf { it.fuelL }

    override suspend fun resetForVehicle(vehicleId: String) {
        rows.keys.removeAll { it.first == vehicleId }
    }

    override suspend fun deleteBin(vehicleId: String, binIndex: Int) {
        rows.remove(vehicleId to binIndex)
    }

    override suspend fun repointOrphans(newVehicleId: String) = Unit

    override suspend fun addToExisting(
        vehicleId: String,
        binIndex: Int,
        distanceKm: Double,
        fuelL: Double,
        seconds: Double,
        samples: Int,
    ): Int {
        val row = rows[vehicleId to binIndex] ?: return 0
        rows[vehicleId to binIndex] = row.copy(
            distanceKm = row.distanceKm + distanceKm,
            fuelL = row.fuelL + fuelL,
            seconds = row.seconds + seconds,
            samples = row.samples + samples,
        )
        return 1
    }

    override suspend fun insertNew(bin: SpeedBinStatsEntity) {
        check(rows[bin.vehicleId to bin.binIndex] == null) { "UNIQUE constraint failed" }
        rows[bin.vehicleId to bin.binIndex] = bin
    }
}

/** Bug B: learned-curve reset / backup import silently undone while OBD is logging. */
class SpeedBinDaoDeltaTest {

    private fun entity(bin: Int, km: Double, fuel: Double, s: Double = km * 60.0, n: Int = 10) =
        SpeedBinStatsEntity("v", bin, km, fuel, s, n)

    private fun SpeedBinStats.toEntity() = SpeedBinStatsEntity(vehicleId, binIndex, distanceKm, fuelL, seconds, samples)

    /** Mimics ObdEngine: accumulate deltas from 90 km/h samples at 6 L/h. */
    private fun collectDeltas(seconds: Int): MutableMap<Int, SpeedBinStats> {
        val deltas = mutableMapOf<Int, SpeedBinStats>()
        val aggregator = SpeedBinAggregator()
        repeat(seconds) {
            aggregator.accumulate(
                deltas,
                ObdSample(timestampMs = it * 1000L, speedKmh = 90.0, rpm = 2000.0, coolantTempC = 85.0),
                dtSec = 1.0,
                fuelRateLph = 6.0,
                vehicleId = "v",
            )
        }
        return deltas
    }

    @Test
    fun `addDeltas inserts missing rows and adds to existing ones`() = runTest {
        val dao = FakeSpeedBinDao()
        dao.addDeltas(listOf(entity(19, 1.0, 0.07)))
        dao.addDeltas(listOf(entity(19, 2.0, 0.14), entity(20, 1.0, 0.08)))

        val rows = dao.getForVehicle("v")
        assertEquals(2, rows.size)
        assertEquals(3.0, rows[0].distanceKm, 1e-9)
        assertEquals(0.21, rows[0].fuelL, 1e-9)
        assertEquals(20, rows[0].samples)
        assertEquals(1.0, rows[1].distanceKm, 1e-9)
    }

    @Test
    fun `empty deltas are skipped`() = runTest {
        val dao = FakeSpeedBinDao()
        dao.addDeltas(listOf(SpeedBinStatsEntity("v", 3, 0.0, 0.0, 0.0, 0)))
        assertTrue(dao.getForVehicle("v").isEmpty())
    }

    @Test
    fun `reset during a logging session is not undone by the next flush`() = runTest {
        val dao = FakeSpeedBinDao()
        dao.overwrite(listOf(entity(19, 500.0, 35.0))) // 500 km learned before

        // Session starts, collects 20 s of deltas...
        val deltas = collectDeltas(20)
        // ...the user taps "reset learning" (CurveViewModel.resetLearning)...
        dao.resetForVehicle("v")
        // ...then the 30 s flush runs.
        dao.addDeltas(deltas.values.map { it.toEntity() })
        deltas.clear()

        val row = dao.getForVehicle("v").single()
        // Only the 20 s driven in this session (0.5 km) — the 500 km are NOT resurrected
        // (the old absolute upsert of the session snapshot wrote 500.5 km back).
        assertEquals(0.5, row.distanceKm, 1e-9)

        // A second flush with nothing new must not change anything (no double counting).
        dao.addDeltas(deltas.values.map { it.toEntity() })
        assertEquals(0.5, dao.getForVehicle("v").single().distanceKm, 1e-9)
    }

    @Test
    fun `backup import during a logging session is kept and the session is added on top`() = runTest {
        val dao = FakeSpeedBinDao()
        dao.overwrite(listOf(entity(19, 100.0, 7.0)))
        val deltas = collectDeltas(40) // 1 km

        // BackupRepository.mergeSpeedBins now uses the same additive path.
        dao.addDeltas(listOf(entity(19, 50.0, 3.5), entity(25, 10.0, 0.9)))
        dao.addDeltas(deltas.values.map { it.toEntity() })

        val rows = dao.getForVehicle("v").associateBy { it.binIndex }
        assertEquals(151.0, rows.getValue(19).distanceKm, 1e-9)
        assertEquals(10.0, rows.getValue(25).distanceKm, 1e-9)
    }

    @Test
    fun `adopt learned then reset leaves only post-reset data`() = runTest {
        val dao = FakeSpeedBinDao()
        dao.overwrite(listOf(entity(19, 300.0, 21.0)))
        val before = collectDeltas(10)
        dao.resetForVehicle("v") // adoptLearned() copies the curve into manual, then resets
        dao.addDeltas(before.values.map { it.toEntity() })
        // The copied curve is not double counted: the learned table does not get 300 km back.
        assertEquals(0.25, dao.getForVehicle("v").sumOf { it.distanceKm }, 1e-9)
        assertNull(dao.getForVehicle("v").firstOrNull { it.distanceKm > 1.0 })
    }

    @Test
    fun `live display merges the DB snapshot with pending deltas`() {
        val snapshot = listOf(SpeedBinStats("v", 19, 10.0, 0.7, 400.0, 400))
        val pending = listOf(SpeedBinStats("v", 19, 1.0, 0.07, 40.0, 40), SpeedBinStats("v", 5, 0.2, 0.04, 30.0, 30))
        val merged = mergeSpeedBins(snapshot, pending)
        assertEquals(listOf(5, 19), merged.map { it.binIndex })
        assertEquals(11.0, merged[1].distanceKm, 1e-9)
        assertEquals(440, merged[1].samples)
    }
}
