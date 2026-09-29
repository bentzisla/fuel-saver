package com.fuelroute.data.obd

import android.util.Log
import com.fuelroute.data.backup.TransactionRunner
import com.fuelroute.data.db.ObdSampleDao
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.VehicleDao
import com.fuelroute.data.db.VehicleEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The start-up repair never races a live OBD session and writes only what it repairs. */
class LearnedDataRepairSessionTest {

    private val vehicle = VehicleEntity(
        id = "civic",
        name = "Civic",
        fuelType = "GASOLINE",
        ratedCombinedL100 = 7.5,
        engineDisplacementL = 1800.0, // typed in cc: needs repair
        tankCapacityL = 50.0,
        fuelRateCorrection = 1.07,
        manualCurve = null,
        vin = null,
        createdAtMs = 0L,
    )

    private val vehicleDao = mockk<VehicleDao>(relaxed = true)
    private val speedBinDao = mockk<SpeedBinDao>(relaxed = true)
    private val sampleDao = mockk<ObdSampleDao>(relaxed = true)
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val engine = mockk<ObdEngine>()
    private val inline = object : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = block()
    }

    private fun repair() = LearnedDataRepair(
        transaction = inline,
        vehicleDao = vehicleDao,
        speedBinDao = speedBinDao,
        sampleDao = sampleDao,
        tripDao = tripDao,
        context = mockk(relaxed = true),
        engine = engine,
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { vehicleDao.getAll() } returns flowOf(listOf(vehicle))
        coEvery { sampleDao.pageForVehicle(any(), any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    @Test
    fun `skipped entirely while an OBD session is running`() = runTest {
        every { engine.isRunning } returns true

        repair().repairAll()

        coVerify(exactly = 0) { speedBinDao.getForVehicle(any()) }
        coVerify(exactly = 0) { speedBinDao.overwrite(any()) }
        coVerify(exactly = 0) { vehicleDao.updateEngineDisplacement(any(), any()) }
    }

    @Test
    fun `repairs only the displacement column, never upserting a stale vehicle snapshot`() = runTest {
        every { engine.isRunning } returns false

        repair().repairAll()

        coVerify(exactly = 1) { vehicleDao.updateEngineDisplacement("civic", 1.8) }
        // A full-row upsert of the start-up snapshot would clobber a fuelRateCorrection written
        // by a refuel calibration meanwhile.
        coVerify(exactly = 0) { vehicleDao.upsert(any()) }
        coVerify(exactly = 0) { tripDao.update(any()) }
    }

    @Test
    fun `a session starting during the rebuild defers the write`() = runTest {
        // Not running at the per-vehicle check, running by the time the transaction is due.
        every { engine.isRunning } returnsMany listOf(false, true)

        repair().repairAll()

        coVerify(exactly = 0) { vehicleDao.updateEngineDisplacement(any(), any()) }
    }
}
