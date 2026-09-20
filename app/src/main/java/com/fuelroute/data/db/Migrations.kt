package com.fuelroute.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// v3 -> v4: adds vehicle + learning_extras, shifts speed bins up, new columns on trip/route_search/refuel.
object Migrations {

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `vehicle` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`fuelType` TEXT NOT NULL, `ratedCombinedL100` REAL NOT NULL, `engineDisplacementL` REAL, " +
                    "`tankCapacityL` REAL, `fuelRateCorrection` REAL NOT NULL, `manualCurve` TEXT, `vin` TEXT, " +
                    "`grade` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `learning_extras` (`vehicleId` TEXT NOT NULL, " +
                    "`coldStartExtraL` REAL NOT NULL, `coldStartCount` INTEGER NOT NULL, " +
                    "`updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`vehicleId`))",
            )
            // Shift speed bins up by one to make room for the idle bin. A single in-place
            // "+1" update would collide with the next bin's (vehicleId, binIndex) key when
            // bins already exist, so shift through a negative intermediate range first.
            db.execSQL("UPDATE speed_bin_stats SET binIndex = -(binIndex + 1) WHERE binIndex > 0")
            db.execSQL("UPDATE speed_bin_stats SET binIndex = -binIndex WHERE binIndex < 0")
            // SQLite forbids adding a NOT NULL column without a DEFAULT when the table
            // already has rows, so provide defaults that match the entity defaults.
            db.execSQL("ALTER TABLE trip ADD COLUMN isOpen INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE trip ADD COLUMN routeSearchId INTEGER")
            db.execSQL("ALTER TABLE trip ADD COLUMN coldStartFuelL REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE route_search ADD COLUMN selectedRouteIndex INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE route_search ADD COLUMN departureTimeMs INTEGER")
            db.execSQL("ALTER TABLE route_search ADD COLUMN tollUnknown INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE refuel ADD COLUMN pricePerLiter REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE refuel ADD COLUMN grade TEXT NOT NULL DEFAULT '95'")
        }
    }

    // v4 -> v5: records the user's chosen route + actual trip outcome, and adds favorite destinations.
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE route_search ADD COLUMN selectedPredictedCost REAL NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE route_search ADD COLUMN selectedPredictedLiters REAL NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE route_search ADD COLUMN selectedPredictedMinutes REAL NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE route_search ADD COLUMN pricePerLiterAtSearch REAL NOT NULL DEFAULT 0",
            )
            db.execSQL("ALTER TABLE route_search ADD COLUMN destinationPlaceId TEXT")
            db.execSQL("ALTER TABLE route_search ADD COLUMN destinationLat REAL")
            db.execSQL("ALTER TABLE route_search ADD COLUMN destinationLng REAL")
            db.execSQL("ALTER TABLE trip ADD COLUMN actualCost REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE trip ADD COLUMN pricePerLiterAtTrip REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE trip ADD COLUMN linkedAtMs INTEGER")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `favorite_destination` (`id` INTEGER PRIMARY KEY " +
                    "AUTOINCREMENT NOT NULL, `label` TEXT NOT NULL, `placeId` TEXT, `latitude` REAL, " +
                    "`longitude` REAL, `sortOrder` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL)",
            )
        }
    }
}