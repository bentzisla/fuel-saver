package com.fuelroute.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

const val NAV_GOOGLE = "google"
const val NAV_WAZE = "waze"

data class AppSettings(
    val fuelPricePerLiter: Double = 7.0,
    val valuePerMinute: Double = 0.0,
    val navigationApp: String = NAV_GOOGLE,
    val autoConnect: Boolean = true,
    val showOverlay: Boolean = false,
    val lastDeviceAddress: String? = null,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun saveFuelPrice(value: Double)
    suspend fun saveValuePerMinute(value: Double)
    suspend fun saveNavigationApp(value: String)
    suspend fun saveAutoConnect(value: Boolean)
    suspend fun saveShowOverlay(value: Boolean)
    suspend fun saveLastDeviceAddress(value: String?)
}

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @Named("settings") private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            AppSettings(
                fuelPricePerLiter = prefs[Keys.FUEL_PRICE] ?: 7.0,
                valuePerMinute = prefs[Keys.VALUE_PER_MINUTE] ?: 0.0,
                navigationApp = prefs[Keys.NAVIGATION_APP] ?: NAV_GOOGLE,
                autoConnect = prefs[Keys.AUTO_CONNECT] ?: true,
                showOverlay = prefs[Keys.SHOW_OVERLAY] ?: false,
                lastDeviceAddress = prefs[Keys.LAST_DEVICE_ADDRESS]?.takeIf { it.isNotBlank() },
            )
        }

    override suspend fun saveFuelPrice(value: Double) {
        dataStore.edit { it[Keys.FUEL_PRICE] = value }
    }

    override suspend fun saveValuePerMinute(value: Double) {
        dataStore.edit { it[Keys.VALUE_PER_MINUTE] = value }
    }

    override suspend fun saveNavigationApp(value: String) {
        dataStore.edit { it[Keys.NAVIGATION_APP] = value }
    }

    override suspend fun saveAutoConnect(value: Boolean) {
        dataStore.edit { it[Keys.AUTO_CONNECT] = value }
    }

    override suspend fun saveShowOverlay(value: Boolean) {
        dataStore.edit { it[Keys.SHOW_OVERLAY] = value }
    }

    override suspend fun saveLastDeviceAddress(value: String?) {
        dataStore.edit { it[Keys.LAST_DEVICE_ADDRESS] = value.orEmpty() }
    }

    private object Keys {
        val FUEL_PRICE = doublePreferencesKey("fuel_price_per_liter")
        val VALUE_PER_MINUTE = doublePreferencesKey("value_per_minute")
        val NAVIGATION_APP = stringPreferencesKey("navigation_app")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val SHOW_OVERLAY = booleanPreferencesKey("show_overlay")
        val LAST_DEVICE_ADDRESS = stringPreferencesKey("last_device_address")
    }
}