package com.fuelroute.di

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.fuelroute.data.backup.TransactionRunner
import com.fuelroute.data.db.AppDatabase
import com.fuelroute.data.db.FavoriteDestinationDao
import com.fuelroute.data.db.FavoriteObdDeviceDao
import com.fuelroute.data.db.LearningExtrasDao
import com.fuelroute.data.db.Migrations
import com.fuelroute.data.db.ObdSampleDao
import com.fuelroute.data.db.RefuelDao
import com.fuelroute.data.db.RouteSearchDao
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.VehicleDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "fuelroute.db")
            .addMigrations(
                Migrations.MIGRATION_3_4,
                Migrations.MIGRATION_4_5,
                Migrations.MIGRATION_5_6,
                Migrations.MIGRATION_6_7,
                Migrations.MIGRATION_7_8,
                Migrations.MIGRATION_8_9,
            )
            .build()

    @Provides
    @Singleton
    fun provideTransactionRunner(db: AppDatabase): TransactionRunner = RoomTransactionRunner(db)

    @Provides
    fun provideObdSampleDao(db: AppDatabase): ObdSampleDao = db.obdSampleDao()

    @Provides
    fun provideSpeedBinDao(db: AppDatabase): SpeedBinDao = db.speedBinDao()

    @Provides
    fun provideTripDao(db: AppDatabase): TripDao = db.tripDao()

    @Provides
    fun provideRefuelDao(db: AppDatabase): RefuelDao = db.refuelDao()

    @Provides
    fun provideRouteSearchDao(db: AppDatabase): RouteSearchDao = db.routeSearchDao()

    @Provides
    fun provideVehicleDao(db: AppDatabase): VehicleDao = db.vehicleDao()

    @Provides
    fun provideLearningExtrasDao(db: AppDatabase): LearningExtrasDao = db.learningExtrasDao()

    @Provides
    fun provideFavoriteDestinationDao(db: AppDatabase): FavoriteDestinationDao =
        db.favoriteDestinationDao()

    @Provides
    fun provideFavoriteObdDeviceDao(db: AppDatabase): FavoriteObdDeviceDao =
        db.favoriteObdDeviceDao()
}

/** Production [TransactionRunner] backed by Room's `withTransaction`. */
class RoomTransactionRunner(private val db: AppDatabase) : TransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R = db.withTransaction { block() }
}