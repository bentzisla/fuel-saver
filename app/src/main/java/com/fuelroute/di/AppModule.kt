package com.fuelroute.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.WorkManager
import com.fuelroute.data.backup.BackupRepository
import com.fuelroute.data.backup.DefaultBackupRepository
import com.fuelroute.data.learning.ColdStartRepository
import com.fuelroute.data.learning.DefaultColdStartRepository
import com.fuelroute.data.location.FusedLocationRepository
import com.fuelroute.data.location.LocationRepository
import com.fuelroute.data.obd.BluetoothDevicesRepository
import com.fuelroute.data.obd.DefaultBluetoothDevicesRepository
import com.fuelroute.data.obd.DefaultLearnedCurveRepository
import com.fuelroute.data.obd.DefaultTripRepository
import com.fuelroute.data.obd.LearnedCurveRepository
import com.fuelroute.data.obd.TripRepository
import com.fuelroute.data.places.DataStorePlacesHistoryRepository
import com.fuelroute.data.places.GooglePlacesRepository
import com.fuelroute.data.places.PlacesHistoryRepository
import com.fuelroute.data.places.PlacesRepository
import com.fuelroute.data.price.DefaultFuelPriceRepository
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.refuel.DefaultRefuelRepository
import com.fuelroute.data.refuel.RefuelRepository
import com.fuelroute.data.routes.CachingRoutesRepository
import com.fuelroute.data.routes.DefaultRouteSearchRepository
import com.fuelroute.data.routes.RouteSearchRepository
import com.fuelroute.data.routes.RoutesRepository
import com.fuelroute.data.settings.DataStoreSettingsRepository
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.DefaultVehicleRepository
import com.fuelroute.data.vehicle.VehicleRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

private val Context.vehicleDataStore: DataStore<Preferences> by preferencesDataStore(name = "vehicle")
private val Context.placesDataStore: DataStore<Preferences> by preferencesDataStore(name = "places")
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val Context.priceDataStore: DataStore<Preferences> by preferencesDataStore(name = "price")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @Named("vehicle")
    fun provideVehicleDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.vehicleDataStore

    @Provides
    @Singleton
    @Named("places")
    fun providePlacesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.placesDataStore

    @Provides
    @Singleton
    @Named("settings")
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore

    @Provides
    @Singleton
    @Named("price")
    fun providePriceDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.priceDataStore

    /**
     * WorkManager is default-initialized by `androidx.startup` before `Application.onCreate`;
     * exposing it via Hilt lets [com.fuelroute.service.RetentionScheduler] enqueue the daily
     * retention job without a custom Configuration.
     */
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindVehicleRepository(impl: DefaultVehicleRepository): VehicleRepository

    @Binds
    @Singleton
    abstract fun bindRoutesRepository(impl: CachingRoutesRepository): RoutesRepository

    @Binds
    @Singleton
    abstract fun bindPlacesRepository(impl: GooglePlacesRepository): PlacesRepository

    @Binds
    @Singleton
    abstract fun bindPlacesHistoryRepository(impl: DataStorePlacesHistoryRepository): PlacesHistoryRepository

    @Binds
    @Singleton
    abstract fun bindLocationRepository(impl: FusedLocationRepository): LocationRepository

    @Binds
    @Singleton
    abstract fun bindBluetoothDevicesRepository(impl: DefaultBluetoothDevicesRepository): BluetoothDevicesRepository

    @Binds
    @Singleton
    abstract fun bindLearnedCurveRepository(impl: DefaultLearnedCurveRepository): LearnedCurveRepository

    @Binds
    @Singleton
    abstract fun bindColdStartRepository(impl: DefaultColdStartRepository): ColdStartRepository

    @Binds
    @Singleton
    abstract fun bindTripRepository(impl: DefaultTripRepository): TripRepository

    @Binds
    @Singleton
    abstract fun bindRefuelRepository(impl: DefaultRefuelRepository): RefuelRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindFuelPriceRepository(impl: DefaultFuelPriceRepository): FuelPriceRepository

    @Binds
    @Singleton
    abstract fun bindRouteSearchRepository(impl: DefaultRouteSearchRepository): RouteSearchRepository

    @Binds
    @Singleton
    abstract fun bindBackupRepository(impl: DefaultBackupRepository): BackupRepository
}