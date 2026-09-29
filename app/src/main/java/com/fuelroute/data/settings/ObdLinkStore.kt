package com.fuelroute.data.settings

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.fuelroute.data.obd.ObdProtocolMemory
import com.fuelroute.domain.obd.ObdProbePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Durable OBD link state kept next to the app settings (same DataStore, separate keys):
 *  - the bus protocol each dongle last locked (`ATDPN`), mirrored into [ObdProtocolMemory];
 *  - the background probe's streak counters that drive its backoff ([ObdProbePolicy]).
 */
@Singleton
class ObdLinkStore @Inject constructor(
    @Named("settings") private val dataStore: DataStore<Preferences>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Probe streaks: consecutive quiet (absent / engine off) and consecutive engine-off results. */
    data class ProbeStreaks(val quiet: Int = 0, val off: Int = 0)

    /**
     * App start: loads the remembered protocols into [ObdProtocolMemory] and makes every later
     * change persist. Idempotent.
     */
    fun attach() {
        ObdProtocolMemory.persister = { address, protocol ->
            scope.launch { runCatching { saveProtocol(address, protocol) }.onFailure { Log.w(TAG, "saving OBD protocol failed", it) } }
        }
        scope.launch {
            runCatching { ObdProtocolMemory.load(protocols()) }
                .onFailure { Log.w(TAG, "loading OBD protocols failed", it) }
        }
    }

    private suspend fun prefs(): Preferences = dataStore.data.catch { emit(emptyPreferences()) }.first()

    /** Every remembered protocol, keyed by upper-case dongle address. */
    suspend fun protocols(): Map<String, Int> =
        prefs().asMap().mapNotNull { (key, value) ->
            val address = key.name.removePrefix(PROTOCOL_PREFIX).takeIf { key.name.startsWith(PROTOCOL_PREFIX) }
            val protocol = value as? Int
            if (address.isNullOrBlank() || protocol == null) null else address to protocol
        }.toMap()

    suspend fun saveProtocol(address: String, protocol: Int?) {
        val key = protocolKey(address)
        dataStore.edit { if (protocol == null) it.remove(key) else it[key] = protocol }
    }

    suspend fun probeStreaks(): ProbeStreaks {
        val prefs = prefs()
        return ProbeStreaks(quiet = prefs[QUIET_STREAK] ?: 0, off = prefs[OFF_STREAK] ?: 0)
    }

    suspend fun saveProbeStreaks(streaks: ProbeStreaks) {
        dataStore.edit {
            it[QUIET_STREAK] = streaks.quiet.coerceAtLeast(0)
            it[OFF_STREAK] = streaks.off.coerceAtLeast(0)
        }
    }

    private companion object {
        const val TAG = "FuelRoute"
        const val PROTOCOL_PREFIX = "obd_protocol_"
        val QUIET_STREAK = intPreferencesKey("probe_quiet_streak")
        val OFF_STREAK = intPreferencesKey("probe_off_streak")

        fun protocolKey(address: String) = intPreferencesKey(PROTOCOL_PREFIX + address.trim().uppercase())
    }
}
