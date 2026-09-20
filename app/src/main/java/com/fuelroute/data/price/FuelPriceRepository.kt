package com.fuelroute.data.price

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.fuelroute.domain.fuel.ModelConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Single source of truth for the fuel price, per grade. The old
 * `SettingsRepository.fuelPricePerLiter` value was migrated here.
 *
 * TODO(6.4): add a monthly WorkManager fetch of the data.gov.il price dataset
 * once the dataset (and its licence) is verified. Until then prices are
 * user-provided or observed from full refuels.
 */
interface FuelPriceRepository {
    /** Effective price + pin state for [grade], as a stream. */
    fun price(grade: String): Flow<FuelPrice>

    /** One-shot read of the effective price + pin state for [grade]. */
    suspend fun current(grade: String): FuelPrice

    /** Pin/unpin [grade] so refuels do or do not update it. */
    suspend fun setPinned(grade: String, pinned: Boolean)

    /** Stores a manually entered price for [grade]. Does not change the pin flag. */
    suspend fun saveManualPrice(grade: String, pricePerLiter: Double)

    /**
     * Applies the price observed at a full refuel for [grade], unless it is pinned.
     * Returns the effective price after the call.
     */
    suspend fun onFullRefuel(grade: String, pricePerLiter: Double): FuelPrice
}

@Singleton
class DefaultFuelPriceRepository @Inject constructor(
    @Named("price") private val dataStore: DataStore<Preferences>,
) : FuelPriceRepository {

    override fun price(grade: String): Flow<FuelPrice> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs.toFuelPrice(grade) }

    override suspend fun current(grade: String): FuelPrice = dataStore.data
        .catch { emit(emptyPreferences()) }
        .first()
        .toFuelPrice(grade)

    override suspend fun setPinned(grade: String, pinned: Boolean) {
        dataStore.edit { it[Keys.pinned(grade)] = pinned }
    }

    override suspend fun saveManualPrice(grade: String, pricePerLiter: Double) {
        dataStore.edit { it[Keys.price(grade)] = pricePerLiter }
    }

    override suspend fun onFullRefuel(grade: String, pricePerLiter: Double): FuelPrice {
        val before = current(grade)
        val after = PricePinning.applyFullRefuel(before, pricePerLiter)
        if (after.pricePerLiter != before.pricePerLiter) {
            dataStore.edit { it[Keys.price(grade)] = after.pricePerLiter }
        }
        return after
    }

    private fun Preferences.toFuelPrice(grade: String) = FuelPrice(
        pricePerLiter = this[Keys.price(grade)] ?: ModelConstants.DEFAULT_FUEL_PRICE,
        grade = grade,
        manuallyPinned = this[Keys.pinned(grade)] ?: false,
    )

    private object Keys {
        fun price(grade: String) = doublePreferencesKey("price_$grade")
        fun pinned(grade: String) = booleanPreferencesKey("pinned_$grade")
    }
}