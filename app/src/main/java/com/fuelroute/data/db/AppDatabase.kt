package com.fuelroute.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ObdSampleEntity::class,
        SpeedBinStatsEntity::class,
        TripEntity::class,
        RefuelEntity::class,
        RouteSearchEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun obdSampleDao(): ObdSampleDao
    abstract fun speedBinDao(): SpeedBinDao
    abstract fun tripDao(): TripDao
    abstract fun refuelDao(): RefuelDao
    abstract fun routeSearchDao(): RouteSearchDao
}