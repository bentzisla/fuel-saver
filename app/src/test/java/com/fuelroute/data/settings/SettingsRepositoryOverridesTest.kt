package com.fuelroute.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fuelroute.domain.fuel.FuelModelOverrides
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryOverridesTest {

    @Test
    fun `unset overrides read back as defaults`() = runTest {
        val repository = DataStoreSettingsRepository(FakePreferencesStore())

        assertEquals(FuelModelOverrides.DEFAULT, repository.modelOverrides.first())
    }

    @Test
    fun `model overrides round-trip through the datastore`() = runTest {
        val repository = DataStoreSettingsRepository(FakePreferencesStore())
        val overrides = FuelModelOverrides(
            slowFactor = 0.6,
            jamFactor = 0.3,
            stopGoWeight = 0.7,
            coldStartDefaultL = 0.2,
            idleLphDefault = 0.9,
            fuelCorrection = 1.2,
        )

        repository.saveModelOverrides(overrides)

        assertEquals(overrides, repository.modelOverrides.first())
    }

    private class FakePreferencesStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> get() = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}