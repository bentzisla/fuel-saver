package com.fuelroute.domain.export

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TripRefuelCsvFormatterTest {

    private lateinit var previousZone: TimeZone

    @Before
    fun pinZone() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreZone() = TimeZone.setDefault(previousZone)

    private val start = 1_790_605_805_000L // 2026-09-28T14:30:05Z

    private fun trip(
        distanceKm: Double = 50.0,
        fuelL: Double = 4.0,
        costNis: Double? = 30.0,
        pricePerLiter: Double? = 7.5,
        source: String = "OBD",
        searchId: Long? = null,
        predicted: Double? = null,
        vehicle: String = "Civic",
    ) = TripCsvRow(
        startedAtMs = start,
        endedAtMs = start + 30 * 60_000L,
        distanceKm = distanceKm,
        fuelL = fuelL,
        costNis = costNis,
        pricePerLiter = pricePerLiter,
        source = source,
        linkedRouteSearchId = searchId,
        predictedCostNis = predicted,
        vehicleName = vehicle,
    )

    private fun lines(csv: String) = csv.removePrefix(Csv.BOM.toString()).split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun `trip row has every column with a dot decimal`() {
        val csv = TripCsvFormatter.format(listOf(trip(searchId = 12, predicted = 28.5)))
        val rows = lines(csv)
        assertEquals(TripCsvFormatter.HEADER.joinToString(","), rows[0])
        assertEquals(
            "2026-09-28T14:30:05,2026-09-28T15:00:05,30.0,50.00,4.000,8.0,30.00,7.50,OBD,12,28.50,Civic",
            rows[1],
        )
    }

    @Test
    fun `trip nulls become blanks and zero distance has no consumption`() {
        val csv = TripCsvFormatter.format(
            listOf(trip(distanceKm = 0.0, fuelL = 0.0, costNis = null, pricePerLiter = null, source = "manual")),
        )
        assertEquals(
            "2026-09-28T14:30:05,2026-09-28T15:00:05,30.0,0.00,0.000,,,,manual,,,Civic",
            lines(csv)[1],
        )
    }

    @Test
    fun `trip vehicle name with comma quote and hebrew is quoted`() {
        val csv = TripCsvFormatter.format(listOf(trip(vehicle = "הונדה, \"סיביק\"")))
        assertEquals(true, lines(csv)[1].endsWith(",\"הונדה, \"\"סיביק\"\"\""))
    }

    @Test
    fun `document with no rows is just a BOM and header`() {
        assertEquals(Csv.BOM + TripCsvFormatter.HEADER.joinToString(",") + "\r\n", TripCsvFormatter.format(emptyList()))
    }

    @Test
    fun `refuel row maps full flag grade and vehicle`() {
        val csv = RefuelCsvFormatter.format(
            listOf(
                RefuelCsvRow(start, 40.5, 300.0, 7.41, "95", true, "Civic"),
                RefuelCsvRow(start, 10.0, 80.0, 8.0, "diesel", false, "משאית"),
            ),
        )
        val rows = lines(csv)
        assertEquals(RefuelCsvFormatter.HEADER.joinToString(","), rows[0])
        assertEquals("2026-09-28T14:30:05,40.500,300.00,7.41,95,1,Civic", rows[1])
        assertEquals("2026-09-28T14:30:05,10.000,80.00,8.00,diesel,0,משאית", rows[2])
    }
}
