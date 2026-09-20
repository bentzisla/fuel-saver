package com.fuelroute.data.backup

import com.fuelroute.data.db.FavoriteDestinationDao
import com.fuelroute.data.db.FavoriteDestinationEntity
import com.fuelroute.data.db.LearningExtrasDao
import com.fuelroute.data.db.LearningExtrasEntity
import com.fuelroute.data.db.RefuelDao
import com.fuelroute.data.db.RefuelEntity
import com.fuelroute.data.db.RouteSearchDao
import com.fuelroute.data.db.RouteSearchEntity
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.data.db.SpeedBinStatsEntity
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.TripEntity
import com.fuelroute.data.db.VehicleDao
import com.fuelroute.data.db.VehicleEntity
import com.fuelroute.data.price.FuelPrice
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.price.PricePinning
import com.fuelroute.data.settings.AppSettings
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.domain.fuel.ModelConstants
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `export then import into a fresh store round-trips the dataset`() = runTest {
        val source = Harness().apply { seed() }
        val exported = source.repo.export()

        val dest = Harness()
        val result = dest.repo.import(exported)

        assertTrue(result.success)
        val a = decode(exported)
        val b = decode(dest.repo.export())
        assertEquals(a.vehicles.sortedBy { it.id }, b.vehicles.sortedBy { it.id })
        assertEquals(
            a.speedBins.sortedWith(compareBy({ it.vehicleId }, { it.binIndex })),
            b.speedBins.sortedWith(compareBy({ it.vehicleId }, { it.binIndex })),
        )
        assertEquals(
            a.trips.sortedWith(compareBy({ it.vehicleId }, { it.startedAtMs })),
            b.trips.sortedWith(compareBy({ it.vehicleId }, { it.startedAtMs })),
        )
        assertEquals(
            a.refuels.sortedWith(compareBy({ it.vehicleId }, { it.timestampMs })),
            b.refuels.sortedWith(compareBy({ it.vehicleId }, { it.timestampMs })),
        )
        assertEquals(
            a.routeSearches.sortedBy { it.timestampMs },
            b.routeSearches.sortedBy { it.timestampMs },
        )
        assertEquals(a.learningExtras.sortedBy { it.vehicleId }, b.learningExtras.sortedBy { it.vehicleId })
        assertEquals(a.favorites.sortedBy { it.createdAtMs }, b.favorites.sortedBy { it.createdAtMs })
        assertEquals(a.settings, b.settings)
        assertEquals(a.prices.sortedBy { it.grade }, b.prices.sortedBy { it.grade })
    }

    @Test
    fun `importing twice dedupes rows and sums bins`() = runTest {
        val source = Harness().apply { seed() }
        val exported = source.repo.export()
        val expected = decode(exported)

        val dest = Harness()
        assertTrue(dest.repo.import(exported).success)
        val second = dest.repo.import(exported)

        assertTrue(second.success)
        assertEquals(0, second.tripsAdded)
        assertEquals(expected.trips.size, second.tripsSkipped)
        assertEquals(0, second.refuelsAdded)
        assertEquals(expected.refuels.size, second.refuelsSkipped)
        assertEquals(0, second.favoritesAdded)
        assertEquals(expected.favorites.size, second.favoritesSkipped)

        // No duplicate rows.
        assertEquals(expected.vehicles.size, dest.vehicles.size)
        assertEquals(expected.trips.size, dest.trips.size)
        assertEquals(expected.refuels.size, dest.refuels.size)
        assertEquals(expected.routeSearches.size, dest.searches.size)
        assertEquals(expected.favorites.size, dest.favorites.size)

        // Bins are summed (imported twice => 2x the source values).
        assertEquals(expected.speedBins.size, dest.bins.size)
        dest.bins.forEach { bin ->
            val original = expected.speedBins.first {
                it.vehicleId == bin.vehicleId && it.binIndex == bin.binIndex
            }
            assertEquals(original.distanceKm * 2, bin.distanceKm, 1e-9)
            assertEquals(original.fuelL * 2, bin.fuelL, 1e-9)
            assertEquals(original.seconds * 2, bin.seconds, 1e-9)
            assertEquals(original.samples * 2, bin.samples)
        }
    }

    @Test
    fun `corrupt json returns an error without writing anything`() = runTest {
        val dest = Harness()

        val result = dest.repo.import("{ this is not valid json")

        assertFalse(result.success)
        assertNotNull(result.error)
        assertNothingWritten(dest)
    }

    @Test
    fun `empty json returns an error without writing anything`() = runTest {
        val dest = Harness()

        val result = dest.repo.import("   ")

        assertFalse(result.success)
        assertNotNull(result.error)
        assertNothingWritten(dest)
    }

    private fun assertNothingWritten(harness: Harness) {
        assertTrue(harness.vehicles.isEmpty())
        assertTrue(harness.bins.isEmpty())
        assertTrue(harness.trips.isEmpty())
        assertTrue(harness.refuels.isEmpty())
        assertTrue(harness.searches.isEmpty())
        assertTrue(harness.extras.isEmpty())
        assertTrue(harness.favorites.isEmpty())
        assertEquals(AppSettings(), harness.settings.value)
    }

    private fun decode(payload: String): BackupPayload =
        json.decodeFromString(BackupPayload.serializer(), payload)

    /**
     * A [DefaultBackupRepository] wired to stateful in-memory stand-ins for every DAO plus the two
     * preference repositories, so import/export behaviour can be asserted on the raw rows.
     */
    private class Harness {
        val vehicles = mutableListOf<VehicleEntity>()
        val bins = mutableListOf<SpeedBinStatsEntity>()
        val trips = mutableListOf<TripEntity>()
        val refuels = mutableListOf<RefuelEntity>()
        val searches = mutableListOf<RouteSearchEntity>()
        val extras = mutableListOf<LearningExtrasEntity>()
        val favorites = mutableListOf<FavoriteDestinationEntity>()

        private var nextTripId = 1L
        private var nextRefuelId = 1L
        private var nextSearchId = 1L
        private var nextFavoriteId = 1L

        val settings = FakeSettingsRepository()
        val prices = FakeFuelPriceRepository()

        val repo: BackupRepository = DefaultBackupRepository(
            vehicleDao = mockk<VehicleDao>(relaxed = true).also { dao ->
                every { dao.getAll() } answers { flowOf(vehicles.toList()) }
                coEvery { dao.upsert(any()) } coAnswers {
                    (args[0] as List<VehicleEntity>).forEach { vehicle ->
                        vehicles.removeAll { it.id == vehicle.id }
                        vehicles.add(vehicle)
                    }
                }
            },
            speedBinDao = mockk<SpeedBinDao>(relaxed = true).also { dao ->
                coEvery { dao.getAll() } answers { bins.toList() }
                coEvery { dao.upsertAll(any()) } coAnswers {
                    (args[0] as List<SpeedBinStatsEntity>).forEach { bin ->
                        bins.removeAll { it.vehicleId == bin.vehicleId && it.binIndex == bin.binIndex }
                        bins.add(bin)
                    }
                }
            },
            tripDao = mockk<TripDao>(relaxed = true).also { dao ->
                coEvery { dao.getAll() } answers { trips.toList() }
                coEvery { dao.insert(any()) } coAnswers {
                    val trip = args[0] as TripEntity
                    val id = nextTripId++
                    trips.add(trip.copy(id = id))
                    id
                }
            },
            refuelDao = mockk<RefuelDao>(relaxed = true).also { dao ->
                coEvery { dao.getAll() } answers { refuels.toList() }
                coEvery { dao.insert(any()) } coAnswers {
                    val refuel = args[0] as RefuelEntity
                    val id = nextRefuelId++
                    refuels.add(refuel.copy(id = id))
                    id
                }
            },
            routeSearchDao = mockk<RouteSearchDao>(relaxed = true).also { dao ->
                coEvery { dao.getAll() } answers { searches.toList() }
                coEvery { dao.insert(any()) } coAnswers {
                    val search = args[0] as RouteSearchEntity
                    val id = nextSearchId++
                    searches.add(search.copy(id = id))
                    id
                }
            },
            learningExtrasDao = mockk<LearningExtrasDao>(relaxed = true).also { dao ->
                coEvery { dao.getAll() } answers { extras.toList() }
                coEvery { dao.upsert(any()) } coAnswers {
                    val entity = args[0] as LearningExtrasEntity
                    extras.removeAll { it.vehicleId == entity.vehicleId }
                    extras.add(entity)
                }
            },
            favoriteDao = mockk<FavoriteDestinationDao>(relaxed = true).also { dao ->
                every { dao.observeAll() } answers { flowOf(favorites.toList()) }
                coEvery { dao.upsert(any()) } coAnswers {
                    val entity = args[0] as FavoriteDestinationEntity
                    val stored = if (entity.id == 0L) entity.copy(id = nextFavoriteId++) else entity
                    favorites.removeAll { it.id == stored.id }
                    favorites.add(stored)
                }
            },
            settingsRepository = settings,
            fuelPriceRepository = prices,
        )

        fun seed() {
            vehicles += VehicleEntity(
                id = "v1",
                name = "Car",
                fuelType = "gasoline",
                ratedCombinedL100 = 7.5,
                engineDisplacementL = 1.6,
                tankCapacityL = 50.0,
                fuelRateCorrection = 1.05,
                manualCurve = "6.0,5.5,5.8",
                vin = "VIN1",
                grade = "95",
                createdAtMs = 1_000L,
            )
            bins += SpeedBinStatsEntity(
                vehicleId = "v1",
                binIndex = 2,
                distanceKm = 10.0,
                fuelL = 0.8,
                seconds = 100.0,
                samples = 5,
            )
            bins += SpeedBinStatsEntity(
                vehicleId = "v1",
                binIndex = 3,
                distanceKm = 20.0,
                fuelL = 1.4,
                seconds = 90.0,
                samples = 4,
            )
            trips += TripEntity(
                vehicleId = "v1",
                startedAtMs = 2_000L,
                endedAtMs = 2_600L,
                distanceKm = 12.0,
                fuelL = 1.0,
                avgSpeedKmh = 45.0,
                maxSpeedKmh = 90.0,
                idleSeconds = 5.0,
            )
            trips += TripEntity(
                vehicleId = "v1",
                startedAtMs = 3_000L,
                endedAtMs = 3_400L,
                distanceKm = 8.0,
                fuelL = 0.6,
                avgSpeedKmh = 40.0,
                maxSpeedKmh = 70.0,
                idleSeconds = 3.0,
            )
            refuels += RefuelEntity(
                vehicleId = "v1",
                timestampMs = 4_000L,
                liters = 30.0,
                totalPrice = 210.0,
                isFull = true,
                pricePerLiter = 7.0,
                grade = "95",
            )
            refuels += RefuelEntity(
                vehicleId = "v1",
                timestampMs = 5_000L,
                liters = 20.0,
                totalPrice = 140.0,
                isFull = false,
                pricePerLiter = 7.0,
                grade = "95",
            )
            searches += RouteSearchEntity(
                originLabel = "Home",
                destinationLabel = "Work",
                timestampMs = 6_000L,
                cheapestCost = 10.0,
                fastestCost = 12.0,
                savedAmount = 2.0,
                predictedLiters = 1.5,
                distanceKm = 15.0,
                durationMin = 22.0,
            )
            extras += LearningExtrasEntity(
                vehicleId = "v1",
                coldStartExtraL = 0.25,
                coldStartCount = 8,
                updatedAtMs = 7_000L,
            )
            favorites += FavoriteDestinationEntity(
                label = "Home",
                placeId = "place-home",
                latitude = 32.1,
                longitude = 34.8,
                sortOrder = 0,
                createdAtMs = 8_000L,
            )
            favorites += FavoriteDestinationEntity(
                label = "Work",
                placeId = null,
                latitude = 32.2,
                longitude = 34.9,
                sortOrder = 1,
                createdAtMs = 9_000L,
            )
            settings.set(
                AppSettings(
                    valuePerMinute = 0.7,
                    navigationApp = "waze",
                    autoConnect = false,
                    showOverlay = true,
                    keepScreenOn = true,
                    lastDeviceAddress = "AA:BB",
                    lastDeviceName = "ELM327",
                    lastAutoStartMs = 10_000L,
                    lastObdError = "timeout",
                    autoConnectIntroSeen = true,
                    retentionDays = 90,
                ),
            )
            prices.put("95", FuelPrice(pricePerLiter = 7.25, grade = "95", manuallyPinned = true))
        }
    }

    private class FakeSettingsRepository : SettingsRepository {
        private val state = MutableStateFlow(AppSettings())
        val value: AppSettings get() = state.value

        fun set(appSettings: AppSettings) {
            state.value = appSettings
        }

        override val settings: Flow<AppSettings> = state.asStateFlow()

        override suspend fun saveValuePerMinute(value: Double) = update { it.copy(valuePerMinute = value) }
        override suspend fun saveNavigationApp(value: String) = update { it.copy(navigationApp = value) }
        override suspend fun saveAutoConnect(value: Boolean) = update { it.copy(autoConnect = value) }
        override suspend fun saveShowOverlay(value: Boolean) = update { it.copy(showOverlay = value) }
        override suspend fun saveKeepScreenOn(value: Boolean) = update { it.copy(keepScreenOn = value) }
        override suspend fun saveLastDeviceAddress(value: String?) = update { it.copy(lastDeviceAddress = value) }
        override suspend fun saveLastDeviceName(value: String?) = update { it.copy(lastDeviceName = value) }
        override suspend fun saveLastAutoStart(value: Long?) = update { it.copy(lastAutoStartMs = value) }
        override suspend fun saveLastObdError(value: String?) = update { it.copy(lastObdError = value) }
        override suspend fun saveAutoConnectIntroSeen(value: Boolean) =
            update { it.copy(autoConnectIntroSeen = value) }
        override suspend fun saveRetentionDays(value: Int) = update { it.copy(retentionDays = value) }

        private fun update(transform: (AppSettings) -> AppSettings) {
            state.update(transform)
        }
    }

    private class FakeFuelPriceRepository : FuelPriceRepository {
        private val stored = mutableMapOf<String, FuelPrice>()

        fun put(grade: String, price: FuelPrice) {
            stored[grade] = price
        }

        override fun price(grade: String): Flow<FuelPrice> = flowOf(currentSync(grade))

        override suspend fun current(grade: String): FuelPrice = currentSync(grade)

        override suspend fun setPinned(grade: String, pinned: Boolean) {
            stored[grade] = currentSync(grade).copy(manuallyPinned = pinned)
        }

        override suspend fun saveManualPrice(grade: String, pricePerLiter: Double) {
            stored[grade] = currentSync(grade).copy(pricePerLiter = pricePerLiter)
        }

        override suspend fun onFullRefuel(grade: String, pricePerLiter: Double): FuelPrice {
            val after = PricePinning.applyFullRefuel(currentSync(grade), pricePerLiter)
            stored[grade] = after
            return after
        }

        private fun currentSync(grade: String): FuelPrice =
            stored[grade] ?: FuelPrice(ModelConstants.DEFAULT_FUEL_PRICE, grade, false)
    }
}