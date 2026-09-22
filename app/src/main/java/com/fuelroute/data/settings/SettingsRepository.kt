package com.fuelroute.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fuelroute.domain.fuel.FuelModelOverrides
import com.fuelroute.domain.fuel.ModelConstants
import com.fuelroute.domain.retention.RetentionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

const val NAV_GOOGLE = "google"
const val NAV_WAZE = "waze"

data class AppSettings(
    val valuePerMinute: Double = ModelConstants.DEFAULT_VALUE_PER_MINUTE,
    val navigationApp: String = NAV_GOOGLE,
    val autoConnect: Boolean = true,
    /**
     * Sticky "the user explicitly disconnected" latch (card 34). While true, no auto-connect
     * path (the ACL/STATE_ON receiver or `StatsViewModel.autoConnect`) may re-arm logging.
     * Cleared by an explicit connect/demo/auto-connect/retry.
     */
    val manualDisconnect: Boolean = false,
    val showOverlay: Boolean = false,
    val keepScreenOn: Boolean = false,
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    val lastAutoStartMs: Long? = null,
    val lastObdError: String? = null,
    val autoConnectIntroSeen: Boolean = false,
    /**
     * Sticky "the user finished the first-run setup screen" flag. When false, the onboarding
     * screen is shown before the tabs; it gates nothing and can be dismissed with "done".
     */
    val onboardingSeen: Boolean = false,
    val retentionDays: Int = RetentionPolicy.DEFAULT_RETENTION_DAYS,
    /** Last time the Android Auto / Automotive host bound to the car app, or null if never (card 42). */
    val carLastSeenMs: Long? = null,
    /** Package name of the car host that bound last, for the Settings diagnostic (card 42). */
    val carLastHost: String? = null,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>

    /** Runtime fuel-model calibration overrides; all-null (i.e. defaults) when unset. */
    val modelOverrides: Flow<FuelModelOverrides>

    suspend fun saveValuePerMinute(value: Double)
    suspend fun saveNavigationApp(value: String)
    suspend fun saveAutoConnect(value: Boolean)

    /**
     * Sets the sticky manual-disconnect latch (card 34). Defaulted to a no-op so lightweight
     * test fakes that do not model the latch still compile; [DataStoreSettingsRepository]
     * overrides it.
     */
    suspend fun saveManualDisconnect(value: Boolean) = Unit

    suspend fun saveShowOverlay(value: Boolean)
    suspend fun saveKeepScreenOn(value: Boolean)
    suspend fun saveLastDeviceAddress(value: String?)
    suspend fun saveLastDeviceName(value: String?)
    suspend fun saveLastAutoStart(value: Long?)
    suspend fun saveLastObdError(value: String?)
    suspend fun saveAutoConnectIntroSeen(value: Boolean)

    /**
     * Marks the first-run onboarding as seen. Defaulted to a no-op so lightweight test fakes
     * that do not model onboarding still compile; [DataStoreSettingsRepository] overrides it.
     */
    suspend fun saveOnboardingSeen(value: Boolean) = Unit

    suspend fun saveRetentionDays(value: Int)

    /**
     * Car-host "last seen" diagnostic (card 42). Default no-op so lightweight test fakes that do not
     * model car usage still compile; [DataStoreSettingsRepository] overrides both.
     */
    suspend fun saveCarLastSeen(value: Long?) = Unit
    suspend fun saveCarLastHost(value: String?) = Unit

    suspend fun saveModelOverrides(value: FuelModelOverrides)
}

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @Named("settings") private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val modelOverrides: Flow<FuelModelOverrides> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs[Keys.MODEL_OVERRIDES]
                ?.takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { json.decodeFromString<FuelModelOverrides>(raw) }.getOrNull() }
                ?: FuelModelOverrides.DEFAULT
        }

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            AppSettings(
                valuePerMinute = prefs[Keys.VALUE_PER_MINUTE] ?: ModelConstants.DEFAULT_VALUE_PER_MINUTE,
                navigationApp = prefs[Keys.NAVIGATION_APP] ?: NAV_GOOGLE,
                autoConnect = prefs[Keys.AUTO_CONNECT] ?: true,
                manualDisconnect = prefs[Keys.MANUAL_DISCONNECT] ?: false,
                showOverlay = prefs[Keys.SHOW_OVERLAY] ?: false,
                keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: false,
                lastDeviceAddress = prefs[Keys.LAST_DEVICE_ADDRESS]?.takeIf { it.isNotBlank() },
                lastDeviceName = prefs[Keys.LAST_DEVICE_NAME]?.takeIf { it.isNotBlank() },
                lastAutoStartMs = prefs[Keys.LAST_AUTO_START_MS]?.takeIf { it > 0L },
                lastObdError = prefs[Keys.LAST_OBD_ERROR]?.takeIf { it.isNotBlank() },
                autoConnectIntroSeen = prefs[Keys.AUTO_CONNECT_INTRO_SEEN] ?: false,
                onboardingSeen = prefs[Keys.ONBOARDING_SEEN] ?: false,
                retentionDays = prefs[Keys.RETENTION_DAYS] ?: RetentionPolicy.DEFAULT_RETENTION_DAYS,
                carLastSeenMs = prefs[Keys.CAR_LAST_SEEN_MS]?.takeIf { it > 0L },
                carLastHost = prefs[Keys.CAR_LAST_HOST]?.takeIf { it.isNotBlank() },
            )
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

    override suspend fun saveManualDisconnect(value: Boolean) {
        dataStore.edit { it[Keys.MANUAL_DISCONNECT] = value }
    }

    override suspend fun saveShowOverlay(value: Boolean) {
        dataStore.edit { it[Keys.SHOW_OVERLAY] = value }
    }

    override suspend fun saveKeepScreenOn(value: Boolean) {
        dataStore.edit { it[Keys.KEEP_SCREEN_ON] = value }
    }

    override suspend fun saveLastDeviceAddress(value: String?) {
        dataStore.edit { it[Keys.LAST_DEVICE_ADDRESS] = value.orEmpty() }
    }

    override suspend fun saveLastDeviceName(value: String?) {
        dataStore.edit { it[Keys.LAST_DEVICE_NAME] = value.orEmpty() }
    }

    override suspend fun saveLastAutoStart(value: Long?) {
        dataStore.edit { it[Keys.LAST_AUTO_START_MS] = value ?: 0L }
    }

    override suspend fun saveLastObdError(value: String?) {
        dataStore.edit { it[Keys.LAST_OBD_ERROR] = value.orEmpty() }
    }

    override suspend fun saveAutoConnectIntroSeen(value: Boolean) {
        dataStore.edit { it[Keys.AUTO_CONNECT_INTRO_SEEN] = value }
    }

    override suspend fun saveOnboardingSeen(value: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_SEEN] = value }
    }

    override suspend fun saveRetentionDays(value: Int) {
        dataStore.edit { it[Keys.RETENTION_DAYS] = RetentionPolicy.applyRetentionDays(value) }
    }

    override suspend fun saveCarLastSeen(value: Long?) {
        dataStore.edit { it[Keys.CAR_LAST_SEEN_MS] = value ?: 0L }
    }

    override suspend fun saveCarLastHost(value: String?) {
        dataStore.edit { it[Keys.CAR_LAST_HOST] = value.orEmpty() }
    }

    override suspend fun saveModelOverrides(value: FuelModelOverrides) {
        dataStore.edit { it[Keys.MODEL_OVERRIDES] = json.encodeToString(value) }
    }

    private object Keys {
        val VALUE_PER_MINUTE = doublePreferencesKey("value_per_minute")
        val NAVIGATION_APP = stringPreferencesKey("navigation_app")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val MANUAL_DISCONNECT = booleanPreferencesKey("manual_disconnect")
        val SHOW_OVERLAY = booleanPreferencesKey("show_overlay")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val LAST_DEVICE_ADDRESS = stringPreferencesKey("last_device_address")
        val LAST_DEVICE_NAME = stringPreferencesKey("last_device_name")
        val LAST_AUTO_START_MS = longPreferencesKey("last_auto_start_ms")
        val LAST_OBD_ERROR = stringPreferencesKey("last_obd_error")
        val AUTO_CONNECT_INTRO_SEEN = booleanPreferencesKey("auto_connect_intro_seen")
        val ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
        val CAR_LAST_SEEN_MS = longPreferencesKey("car_last_seen_ms")
        val CAR_LAST_HOST = stringPreferencesKey("car_last_host")
        val MODEL_OVERRIDES = stringPreferencesKey("model_overrides")
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}