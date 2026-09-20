package com.fuelroute.data.places

import com.fuelroute.data.db.FavoriteDestinationDao
import com.fuelroute.data.db.FavoriteDestinationEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesRepositoryTest {

    private class FakeStore {
        val items = mutableListOf<FavoriteDestinationEntity>()
        private var nextId = 1L

        fun assignId(): Long = nextId++

        /** Mimics `SELECT * ... ORDER BY sortOrder, createdAtMs`. */
        fun snapshot(): List<FavoriteDestinationEntity> =
            items.sortedWith(compareBy({ it.sortOrder }, { it.createdAtMs })).toList()
    }

    /** Stateful MockK DAO: `upsert` assigns ids and `updateSortOrder` mutates the in-memory rows. */
    private fun fakeDao(store: FakeStore): FavoriteDestinationDao =
        mockk<FavoriteDestinationDao>(relaxed = true).apply {
            every { observeAll() } answers { flowOf(store.snapshot()) }
            coEvery { upsert(any()) } coAnswers {
                val incoming = args[0] as FavoriteDestinationEntity
                val stored = if (incoming.id == 0L) incoming.copy(id = store.assignId()) else incoming
                store.items.removeAll { it.id == stored.id }
                store.items.add(stored)
            }
            coEvery { delete(any()) } coAnswers {
                val target = args[0] as FavoriteDestinationEntity
                store.items.removeAll { it.id == target.id }
            }
            coEvery { updateSortOrder(any(), any()) } coAnswers {
                val id = args[0] as Long
                val order = args[1] as Int
                val index = store.items.indexOfFirst { it.id == id }
                if (index >= 0) store.items[index] = store.items[index].copy(sortOrder = order)
            }
            coEvery { count() } answers { store.items.size }
        }

    @Test
    fun `add dedupes by placeId`() = runTest {
        val store = FakeStore()
        val repository = FavoritesRepository(fakeDao(store))

        repository.add("A", "p1", 1.0, 2.0)
        repository.add("A2", "p1", 3.0, 4.0)

        assertEquals(1, store.items.size)
        assertEquals("p1", store.items.single().placeId)
        assertEquals(3.0, store.items.single().latitude!!, 0.0)
    }

    @Test
    fun `add dedupes by normalized label when placeId is null`() = runTest {
        val store = FakeStore()
        val repository = FavoritesRepository(fakeDao(store))

        repository.add("Home", null, null, null)
        repository.add("  home  ", null, null, null)

        assertEquals(1, store.items.size)
    }

    @Test
    fun `add assigns an increasing sortOrder`() = runTest {
        val store = FakeStore()
        val repository = FavoritesRepository(fakeDao(store))

        repository.add("A", null, null, null)
        repository.add("B", null, null, null)

        val ordered = store.snapshot()
        assertEquals(listOf("A", "B"), ordered.map { it.label })
        assertEquals(listOf(0, 1), ordered.map { it.sortOrder })
    }

    @Test
    fun `move reorders deterministically`() = runTest {
        val store = FakeStore()
        val repository = FavoritesRepository(fakeDao(store))

        repository.add("A", null, null, null)
        repository.add("B", null, null, null)
        repository.add("C", null, null, null)
        val c = store.snapshot().first { it.label == "C" }

        repository.move(c.id, 0)

        val ordered = store.snapshot()
        assertEquals(listOf("C", "A", "B"), ordered.map { it.label })
        assertEquals(listOf(0, 1, 2), ordered.map { it.sortOrder })
    }

    @Test
    fun `rename keeps the coordinates intact`() = runTest {
        val store = FakeStore()
        val repository = FavoritesRepository(fakeDao(store))

        repository.add("Raw address", "p1", 32.1, 34.7)
        val id = store.items.single().id

        repository.rename(id, "בית")

        val saved = store.items.single()
        assertEquals("בית", saved.label)
        assertEquals("p1", saved.placeId)
        assertEquals(32.1, saved.latitude!!, 0.0)
        assertEquals(34.7, saved.longitude!!, 0.0)
    }

    @Test
    fun `isFavorite matches on placeId even when the label differs`() = runTest {
        val store = FakeStore()
        val repository = FavoritesRepository(fakeDao(store))

        repository.add("Raw address", "p1", 32.1, 34.7)
        val id = store.items.single().id
        repository.rename(id, "בית")

        assertTrue(repository.isFavorite("p1", "completely different label"))
        assertFalse(repository.isFavorite("p2", "completely different label"))
    }

    @Test
    fun `isFavorite falls back to the normalized label without a placeId`() = runTest {
        val store = FakeStore()
        val repository = FavoritesRepository(fakeDao(store))

        repository.add("Home", null, null, null)

        assertTrue(repository.isFavorite(null, "  home "))
        assertFalse(repository.isFavorite(null, "work"))
    }
}