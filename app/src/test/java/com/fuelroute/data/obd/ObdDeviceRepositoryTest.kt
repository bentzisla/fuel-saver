package com.fuelroute.data.obd

import com.fuelroute.data.db.FavoriteObdDeviceDao
import com.fuelroute.data.db.FavoriteObdDeviceEntity
import com.fuelroute.data.settings.AppSettings
import com.fuelroute.data.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [ObdDeviceRepository]'s favorite toggling against a stateful fake DAO. The merge/ordering
 * itself is tested purely in `domain/obd/ObdDeviceOrderingTest`.
 */
class ObdDeviceRepositoryTest {

    private class FakeStore {
        val items = mutableListOf<FavoriteObdDeviceEntity>()

        /** Mimics `SELECT * ... ORDER BY sortOrder, createdAtMs`. */
        fun snapshot(): List<FavoriteObdDeviceEntity> =
            items.sortedWith(compareBy({ it.sortOrder }, { it.createdAtMs })).toList()
    }

    private fun fakeDao(store: FakeStore): FavoriteObdDeviceDao =
        mockk<FavoriteObdDeviceDao>(relaxed = true).apply {
            every { observeAll() } answers { flowOf(store.snapshot()) }
            coEvery { upsert(any()) } coAnswers {
                val incoming = args[0] as FavoriteObdDeviceEntity
                store.items.removeAll { it.address == incoming.address }
                store.items.add(incoming)
            }
            coEvery { deleteByAddress(any()) } coAnswers {
                val address = args[0] as String
                store.items.removeAll { it.address == address }
            }
        }

    private fun repository(store: FakeStore): ObdDeviceRepository = ObdDeviceRepository(
        bluetoothRepository = mockk<BluetoothDevicesRepository>(relaxed = true),
        favoritesDao = fakeDao(store),
        settingsRepository = mockk<SettingsRepository>(relaxed = true).apply {
            every { settings } returns flowOf(AppSettings())
        },
    )

    @Test
    fun `toggleFavorite stars an unknown adapter then clears it`() = runTest {
        val store = FakeStore()
        val repository = repository(store)

        assertFalse(repository.isFavorite("AA:BB:CC"))

        repository.toggleFavorite("AA:BB:CC", "ELM327")

        assertTrue(repository.isFavorite("AA:BB:CC"))
        assertEquals("ELM327", store.items.single().name)

        repository.toggleFavorite("AA:BB:CC", "ELM327")

        assertFalse(repository.isFavorite("AA:BB:CC"))
        assertTrue(store.items.isEmpty())
    }

    @Test
    fun `new favorites get an increasing sortOrder`() = runTest {
        val store = FakeStore()
        val repository = repository(store)

        repository.toggleFavorite("A", "A")
        repository.toggleFavorite("B", "B")

        assertEquals(listOf("A", "B"), store.snapshot().map { it.address })
        assertEquals(listOf(0, 1), store.snapshot().map { it.sortOrder })
    }

    @Test
    fun `isFavorite ignores address casing`() = runTest {
        val store = FakeStore()
        val repository = repository(store)

        repository.toggleFavorite("AA:BB:CC", "Dongle")

        assertTrue(repository.isFavorite("aa:bb:cc"))
    }

    @Test
    fun `blank name falls back to the address`() = runTest {
        val store = FakeStore()
        val repository = repository(store)

        repository.toggleFavorite("AA:BB:CC", "   ")

        assertEquals("AA:BB:CC", store.items.single().name)
    }
}