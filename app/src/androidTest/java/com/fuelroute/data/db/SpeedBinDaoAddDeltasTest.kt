package com.fuelroute.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented check of the real SQL behind [SpeedBinDao.addDeltas] (additive update-then-insert
 * in one transaction), which the JVM tests only exercise through a fake DAO.
 */
@RunWith(AndroidJUnit4::class)
class SpeedBinDaoAddDeltasTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun bin(index: Int, km: Double, fuel: Double) =
        SpeedBinStatsEntity(vehicleId = "v", binIndex = index, distanceKm = km, fuelL = fuel, seconds = km * 40.0, samples = 10)

    @Test
    fun addDeltasInsertsThenAdds() = runBlocking {
        val dao = db.speedBinDao()
        dao.addDeltas(listOf(bin(19, 1.0, 0.07)))
        dao.addDeltas(listOf(bin(19, 2.0, 0.14), bin(20, 1.0, 0.08)))

        val rows = dao.getForVehicle("v")
        assertEquals(2, rows.size)
        assertEquals(3.0, rows[0].distanceKm, 1e-9)
        assertEquals(0.21, rows[0].fuelL, 1e-9)
        assertEquals(20, rows[0].samples)
    }

    @Test
    fun resetIsNotUndoneByALaterDeltaFlush() = runBlocking {
        val dao = db.speedBinDao()
        dao.overwrite(listOf(bin(19, 500.0, 35.0)))
        dao.resetForVehicle("v")
        dao.addDeltas(listOf(bin(19, 0.5, 0.035)))

        val rows = dao.getForVehicle("v")
        assertEquals(1, rows.size)
        assertEquals(0.5, rows[0].distanceKm, 1e-9)
        assertTrue(rows.none { it.distanceKm > 1.0 })
    }
}
