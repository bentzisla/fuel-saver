package com.fuelroute.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented proof that deleting a vehicle through [VehicleDao.deleteWithChildren] removes every
 * child row (OBD samples, speed bins, trips, refuels, learning extras) even though the schema has
 * no `ON DELETE CASCADE` foreign keys.
 */
@RunWith(AndroidJUnit4::class)
class VehicleCascadeTest {

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

    @Test
    fun deletingVehicleRemovesAllChildRows() = runBlocking {
        val vehicleId = "v-cascade"
        val otherId = "v-keep"

        db.vehicleDao().upsert(
            listOf(
                vehicle(vehicleId),
                vehicle(otherId),
            ),
        )
        db.obdSampleDao().insert(sample(vehicleId, timestampMs = 1_000L))
        db.obdSampleDao().insert(sample(otherId, timestampMs = 1_000L))
        db.speedBinDao().upsertAll(
            listOf(bin(vehicleId, binIndex = 3), bin(otherId, binIndex = 3)),
        )
        db.tripDao().insert(trip(vehicleId))
        db.tripDao().insert(trip(otherId))
        db.refuelDao().insert(refuel(vehicleId))
        db.refuelDao().insert(refuel(otherId))
        db.learningExtrasDao().upsert(learningExtras(vehicleId))
        db.learningExtrasDao().upsert(learningExtras(otherId))

        db.vehicleDao().deleteWithChildren(vehicleId)

        assertEquals(1, db.vehicleDao().count())
        assertEquals(otherId, db.vehicleDao().getAll().first().single().id)
        assertEquals(1, db.obdSampleDao().count())
        assertEquals(1, db.speedBinDao().getForVehicle(otherId).size)
        assertEquals(0, db.speedBinDao().getForVehicle(vehicleId).size)
        assertEquals(1, db.tripDao().recentForVehicle(otherId, 10).size)
        assertEquals(0, db.tripDao().recentForVehicle(vehicleId, 10).size)
        assertEquals(1, db.refuelDao().recentForVehicle(otherId, 10).size)
        assertEquals(0, db.refuelDao().recentForVehicle(vehicleId, 10).size)
        assertEquals(null, db.learningExtrasDao().get(vehicleId))
    }

    private fun vehicle(id: String) = VehicleEntity(
        id = id,
        name = "Car $id",
        fuelType = "GASOLINE",
        ratedCombinedL100 = 7.0,
        engineDisplacementL = null,
        tankCapacityL = null,
        fuelRateCorrection = 1.0,
        manualCurve = null,
        vin = null,
        createdAtMs = 1L,
    )

    private fun sample(vehicleId: String, timestampMs: Long) = ObdSampleEntity(
        vehicleId = vehicleId,
        timestampMs = timestampMs,
        speedKmh = 50.0,
        rpm = 2000.0,
        mafGps = null,
        fuelRateLph = null,
        mapKpa = null,
        intakeTempC = null,
        coolantTempC = null,
        engineLoadPct = null,
        fuelLevelPct = null,
    )

    private fun bin(vehicleId: String, binIndex: Int) = SpeedBinStatsEntity(
        vehicleId = vehicleId,
        binIndex = binIndex,
        distanceKm = 1.0,
        fuelL = 0.1,
        seconds = 60.0,
        samples = 10,
    )

    private fun trip(vehicleId: String) = TripEntity(
        vehicleId = vehicleId,
        startedAtMs = 1_000L,
        endedAtMs = 2_000L,
        distanceKm = 1.0,
        fuelL = 0.1,
        avgSpeedKmh = 30.0,
        maxSpeedKmh = 50.0,
        idleSeconds = 0.0,
    )

    private fun refuel(vehicleId: String) = RefuelEntity(
        vehicleId = vehicleId,
        timestampMs = 1_000L,
        liters = 30.0,
        totalPrice = 200.0,
        isFull = true,
    )

    private fun learningExtras(vehicleId: String) = LearningExtrasEntity(
        vehicleId = vehicleId,
        coldStartExtraL = 0.05,
        coldStartCount = 2,
        updatedAtMs = 1_000L,
    )
}
