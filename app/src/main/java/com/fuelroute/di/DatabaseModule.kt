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
                Migrations.MIGRATION_9_10,
            )
            // DATA-SAFETY GUARD: destructive migration is intentionally NOT enabled.
            //
            //   Room's `fallbackToDestructiveMigration()` recreates the database from scratch,
            //   silently deleting every trip, refuel, learned curve and setting when the
            //   on-device DB version does not resolve to a registered migration. That is how a
            //   previous release wiped a user's data.
            //
            //   With no destructive fallback, a version mismatch instead throws a loud
            //   `IllegalStateException` on open — the app force-closes rather than silently
            //   destroying data, and the mismatch can be diagnosed and fixed (or recovered from
            //   backup) without loss. Every supported version (4..10) is covered by the
            //   non-destructive chain above, so this only triggers on an unsupported/hand-edited
            //   database, which must NEVER be silently thrown away.
            //
            //   Do NOT add fallbackToDestructiveMigration() here.
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