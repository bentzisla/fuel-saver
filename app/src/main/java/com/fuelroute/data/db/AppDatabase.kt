package com.fuelroute.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        VehicleEntity::class,
        LearningExtrasEntity::class,
        ObdSampleEntity::class,
        SpeedBinStatsEntity::class,
        TripEntity::class,
        RefuelEntity::class,
        RouteSearchEntity::class,
        FavoriteDestinationEntity::class,
        FavoriteObdDeviceEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun obdSampleDao(): ObdSampleDao
    abstract fun speedBinDao(): SpeedBinDao
    abstract fun tripDao(): TripDao
    abstract fun refuelDao(): RefuelDao
    abstract fun routeSearchDao(): RouteSearchDao
    abstract fun vehicleDao(): VehicleDao
    abstract fun learningExtrasDao(): LearningExtrasDao
    abstract fun favoriteDestinationDao(): FavoriteDestinationDao
    abstract fun favoriteObdDeviceDao(): FavoriteObdDeviceDao
}