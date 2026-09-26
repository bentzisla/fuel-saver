package com.fuelroute.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
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

    private companion object {
        const val DB_6_TO_9 = "migration-test-6-9"
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
