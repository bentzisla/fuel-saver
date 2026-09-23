package com.fuelroute.data.vehicle

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fuelroute.data.db.RefuelDao
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.VehicleDao
import com.fuelroute.data.db.VehicleEntity
import com.fuelroute.domain.model.FuelType
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VehicleRepositoryTest {

    private val store = FakePreferencesStore()
    private val vehicleDao = mockk<VehicleDao>()
    private val speedBinDao = mockk<SpeedBinDao>(relaxed = true)
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val refuelDao = mockk<RefuelDao>(relaxed = true)
    private val repository = DefaultVehicleRepository(store, vehicleDao, speedBinDao, tripDao, refuelDao)

    @Test
    fun `bootstraps one vehicle from a legacy-empty datastore`() = runTest {
        val inserted = slot<List<VehicleEntity>>()
        coEvery { vehicleDao.count() } returns 0
        coEvery { vehicleDao.upsert(capture(inserted)) } just Runs
        coEvery { vehicleDao.getById(any()) } answers { inserted.captured.firstOrNull() }

        val profile = repository.active()

        assertEquals(1, inserted.captured.size)
        assertEquals(profile.id, inserted.captured.single().id)
        assertEquals(7.0, profile.ratedCombinedL100, 1e-9)
        assertEquals(FuelType.GASOLINE, profile.fuelType)

        // Orphaned OBD/trip/refuel rows are re-pointed to the bootstrap vehicle.
        coVerify(exactly = 1) { speedBinDao.repointOrphans(profile.id) }
        coVerify(exactly = 1) { tripDao.repointOrphans(profile.id) }
        coVerify(exactly = 1) { refuelDao.repointOrphans(profile.id) }
    }

    @Test
    fun `reuses an existing vehicle and repoints the active pointer`() = runTest {
        val entity = existingVehicle(id = "v1", rated = 6.5)
        coEvery { vehicleDao.count() } returns 1
        coEvery { vehicleDao.getAll() } returns flowOf(listOf(entity))
        coEvery { vehicleDao.getById(any()) } returns null

        val profile = repository.active()

        assertEquals("v1", profile.id)
        assertEquals(6.5, profile.ratedCombinedL100, 1e-9)
        // No bootstrap insert when the table is already populated.
        coVerify(exactly = 0) { vehicleDao.upsert(any()) }
    }

    @Test
    fun `upsert assigns an id and becomes active when the table was empty`() = runTest {
        val inserted = slot<List<VehicleEntity>>()
        coEvery { vehicleDao.count() } returns 0
        coEvery { vehicleDao.upsert(capture(inserted)) } just Runs
        coEvery { vehicleDao.getById(any()) } answers { inserted.captured.lastOrNull() }

        repository.upsert(
            com.fuelroute.domain.model.VehicleProfile(id = "", name = "New Car", ratedCombinedL100 = 8.0),
        )

        val saved = inserted.captured.last()
        assertNotNull(saved.id)
        assertEquals("New Car", saved.name)
        assertEquals("8.0", saved.ratedCombinedL100.toString())
    }

    @Test
    fun `delete removes the vehicle through the child-cascade helper`() = runTest {
        coEvery { vehicleDao.count() } returns 2
        coEvery { vehicleDao.deleteWithChildren(any()) } just Runs
        coEvery { vehicleDao.getAll() } returns flowOf(listOf(existingVehicle(id = "v2", rated = 6.5)))

        repository.delete("v2")

        coVerify(exactly = 1) { vehicleDao.deleteWithChildren("v2") }
        coVerify(exactly = 0) { vehicleDao.deleteById("v2") }
    }

    private fun existingVehicle(id: String, rated: Double) = VehicleEntity(
        id = id,
        name = "Car",
        fuelType = "GASOLINE",
        ratedCombinedL100 = rated,
        engineDisplacementL = null,
        tankCapacityL = null,
        fuelRateCorrection = 1.0,
        manualCurve = null,
        vin = null,
        createdAtMs = 1L,
    )

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