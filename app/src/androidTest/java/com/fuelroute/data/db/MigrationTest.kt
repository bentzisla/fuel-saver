package com.fuelroute.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the non-destructive migrations run cleanly on a real device, and that the [Migrations.MIGRATION_8_9]
 * index definitions match what Room expects from the entities (schema validation compares the
 * migrated DB against the exported `9.json`).
 *
 * Runs via `connectedDebugAndroidTest`; the schema JSONs under `app/schemas` are exposed to the
 * androidTest assets (see `app/build.gradle.kts`).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate4To5() {
        helper.createDatabase(DB_4_TO_5, 4).close()
        helper.runMigrationsAndValidate(DB_4_TO_5, 5, true, Migrations.MIGRATION_4_5)
    }

    @Test
    fun migrate5To6() {
        helper.createDatabase(DB_5_TO_6, 5).close()
        helper.runMigrationsAndValidate(DB_5_TO_6, 6, true, Migrations.MIGRATION_5_6)
    }

    @Test
    fun migrate6Through9() {
        helper.createDatabase(DB_6_TO_9, 6).close()
        helper.runMigrationsAndValidate(DB_6_TO_9, 7, true, Migrations.MIGRATION_6_7)
        helper.runMigrationsAndValidate(DB_6_TO_9, 8, true, Migrations.MIGRATION_7_8)
        helper.runMigrationsAndValidate(DB_6_TO_9, 9, true, Migrations.MIGRATION_8_9)
    }

    @Test
    fun migrate8To9CreatesEveryExpectedIndex() {
        helper.createDatabase(DB_8_TO_9, 8).close()
        val db = helper.runMigrationsAndValidate(DB_8_TO_9, 9, true, Migrations.MIGRATION_8_9)

        for (indexName in EXPECTED_INDICES) {
            val found = db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?",
                arrayOf(indexName),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            assertNotNull("MIGRATION_8_9 did not create index $indexName", found)
        }
    }

    @Test
    fun migrate9To10AddsVehicleMass() {
        helper.createDatabase(DB_9_TO_10, 9).close()
        helper.runMigrationsAndValidate(DB_9_TO_10, 10, true, Migrations.MIGRATION_9_10)
    }

    @Test
    fun migrate10To11AddsCalibrationProvenanceAsNull() {
        helper.createDatabase(DB_10_TO_11, 10).apply {
            execSQL(
                "INSERT INTO route_search (id, originLabel, destinationLabel, timestampMs, cheapestCost, " +
                    "fastestCost, savedAmount, predictedLiters, distanceKm, durationMin, selectedRouteIndex, " +
                    "tollUnknown, selectedPredictedCost, selectedPredictedLiters, selectedPredictedMinutes, " +
                    "pricePerLiterAtSearch) VALUES (1, 'a', 'b', 1000, 20.0, 22.0, 2.0, 3.0, 40.0, 30.0, 0, 0, " +
                    "20.0, 3.0, 30.0, 7.0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(DB_10_TO_11, 11, true, Migrations.MIGRATION_10_11)
        db.query("SELECT fuelCorrectionAtSearch FROM route_search WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("old searches have no stored correction", cursor.isNull(0))
        }
        db.query("SELECT obdCorrectionAtFill FROM refuel").use { cursor ->
            assertEquals(0, cursor.count)
        }
    }

    private companion object {
        const val DB_4_TO_5 = "migration-test-4-5"
        const val DB_5_TO_6 = "migration-test-5-6"
        const val DB_6_TO_9 = "migration-test-6-9"
        const val DB_10_TO_11 = "migration-test-10-11"
        const val DB_9_TO_10 = "migration-test-9-10"
        const val DB_8_TO_9 = "migration-test-8-9"

        val EXPECTED_INDICES = listOf(
            "index_obd_sample_timestampMs",
            "index_route_search_timestampMs",
            "index_route_search_departureTimeMs",
            "index_trip_vehicleId",
            "index_trip_routeSearchId",
            "index_trip_isOpen",
            "index_refuel_vehicleId",
        )
    }
}
