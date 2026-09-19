package com.fuelroute.data.places

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Serializable
data class RecentPlace(
    val label: String,
    val placeId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

interface PlacesHistoryRepository {
    val history: Flow<List<RecentPlace>>
    suspend fun add(place: RecentPlace)
    suspend fun clear()
}

@Singleton
class DataStorePlacesHistoryRepository @Inject constructor(
    @Named("places") private val dataStore: DataStore<Preferences>,
    private val json: Json,
) : PlacesHistoryRepository {

    override val history: Flow<List<RecentPlace>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> decode(prefs[Keys.HISTORY]) }

    override suspend fun add(place: RecentPlace) {
        dataStore.edit { prefs ->
            val current = decode(prefs[Keys.HISTORY])
            val deduped = current.filterNot { it.sameAs(place) }
            val updated = (listOf(place) + deduped).take(MAX_ENTRIES)
            prefs[Keys.HISTORY] = json.encodeToString(serializer, updated)
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(Keys.HISTORY) }
    }

    private fun decode(raw: String?): List<RecentPlace> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun RecentPlace.sameAs(other: RecentPlace): Boolean = when {
        placeId != null -> placeId == other.placeId
        else -> label == other.label
    }

    private object Keys {
        val HISTORY = stringPreferencesKey("recent_destinations")
    }

    private companion object {
        const val MAX_ENTRIES = 10
        val serializer = ListSerializer(RecentPlace.serializer())
    }
}