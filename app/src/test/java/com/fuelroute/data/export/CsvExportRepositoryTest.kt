package com.fuelroute.data.export

import com.fuelroute.data.db.RefuelDao
import com.fuelroute.data.db.RefuelEntity
import com.fuelroute.data.db.RouteSearchDao
import com.fuelroute.data.db.RouteSearchEntity
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.TripEntity
import com.fuelroute.data.db.VehicleDao
import com.fuelroute.data.db.VehicleEntity
import com.fuelroute.domain.export.Csv
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.TimeZone
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class CsvExportRepositoryTest {

    private val tripDao = mockk<TripDao>()
    private val refuelDao = mockk<RefuelDao>()
    private val vehicleDao = mockk<VehicleDao>()
    private val routeSearchDao = mockk<RouteSearchDao>()
    private val repo = DefaultCsvExportRepository(tripDao, refuelDao, vehicleDao, routeSearchDao)
    private lateinit var previousZone: TimeZone

    private val t0 = 1_790_605_805_000L // 2026-09-28T14:30:05Z

    @Before
    fun setUp() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        every { vehicleDao.getAll() } returns flowOf(listOf(vehicle("v1", "Civic"), vehicle("v2", "Golf")))
    }

    @After
    fun tearDown() = TimeZone.setDefault(previousZone)

    private fun vehicle(id: String, name: String) = VehicleEntity(
        id = id, name = name, fuelType = "GASOLINE", ratedCombinedL100 = 7.0,
        engineDisplacementL = null, tankCapacityL = null, fuelRateCorrection = 1.0,
        manualCurve = null, vin = null, createdAtMs = 0L,
    )

    private fun trip(vehicleId: String, startOffsetMin: Long, block: (TripEntity) -> TripEntity = { it }) = block(
        TripEntity(
            vehicleId = vehicleId,
            startedAtMs = t0 + startOffsetMin * 60_000L,
            endedAtMs = t0 + (startOffsetMin + 30) * 60_000L,
            distanceKm = 50.0,
            fuelL = 4.0,
            avgSpeedKmh = 60.0,
            maxSpeedKmh = 100.0,
            idleSeconds = 0.0,
            actualCost = 30.0,
            pricePerLiterAtTrip = 7.5,
        ),
    )

    private fun body(csv: String) = csv.removePrefix(Csv.BOM.toString()).split("\r\n").filter { it.isNotEmpty() }.drop(1)

    @Test
    fun `trips cover all vehicles, skip open trips, sort oldest first and mark manual and linked`() = runTest(timeout = 5.minutes) {
        coEvery { tripDao.getAll() } returns listOf(
            trip("v2", 60),
            trip("v1", 0) { it.copy(routeSearchId = 5) },
            trip("v1", 30) { it.copy(manualCost = 41.0, manualDistanceKm = 50.0, manualLitersPer100Km = 8.0) },
            trip("v1", 90) { it.copy(isOpen = 1) },
        )
        coEvery { routeSearchDao.getAll() } returns listOf(
            RouteSearchEntity(
                id = 5, originLabel = "a", destinationLabel = "b", timestampMs = t0, cheapestCost = 25.0,
                fastestCost = 30.0, savedAmount = 5.0, predictedLiters = 3.5, distanceKm = 50.0, durationMin = 30.0,
                selectedPredictedCost = 27.5,
            ),
        )

        val rows = body(repo.exportTripsCsv())

        assertEquals(3, rows.size)
        assertEquals("2026-09-28T14:30:05,2026-09-28T15:00:05,30.0,50.00,4.000,8.0,30.00,7.50,OBD,5,27.50,Civic", rows[0])
        assertEquals("2026-09-28T15:00:05,2026-09-28T15:30:05,30.0,50.00,4.000,8.0,41.00,7.50,manual,,,Civic", rows[1])
        assertEquals("2026-09-28T15:30:05,2026-09-28T16:00:05,30.0,50.00,4.000,8.0,30.00,7.50,OBD,,,Golf", rows[2])
    }

    @Test
    fun `refuels cover all vehicles oldest first and fall back for a deleted vehicle`() = runTest(timeout = 5.minutes) {
        coEvery { refuelDao.getAll() } returns listOf(
            RefuelEntity(vehicleId = "v2", timestampMs = t0 + 60_000L, liters = 10.0, totalPrice = 80.0, isFull = false, pricePerLiter = 8.0, grade = "98"),
            RefuelEntity(vehicleId = "gone", timestampMs = t0, liters = 40.0, totalPrice = 300.0, isFull = true, pricePerLiter = 7.5, grade = "95"),
        )

        val rows = body(repo.exportRefuelsCsv())

        assertEquals("2026-09-28T14:30:05,40.000,300.00,7.50,95,1,?", rows[0])
        assertEquals("2026-09-28T14:31:05,10.000,80.00,8.00,98,0,Golf", rows[1])
    }
}
