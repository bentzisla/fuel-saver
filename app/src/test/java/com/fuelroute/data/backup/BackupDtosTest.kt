package com.fuelroute.data.backup

import com.fuelroute.data.db.TripEntity
import com.fuelroute.data.db.TripSource
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupDtosTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `TripEntity toSnapshot and back preserves all fields including source and manual cost fields`() {
        val original = TripEntity(
            id = 42L,
            vehicleId = "v1",
            startedAtMs = 1000L,
            endedAtMs = 2000L,
            distanceKm = 15.5,
            fuelL = 1.2,
            avgSpeedKmh = 50.0,
            maxSpeedKmh = 100.0,
            idleSeconds = 10.0,
            isOpen = 0,
            routeSearchId = 5,
            coldStartFuelL = 0.1,
            actualCost = 8.5,
            pricePerLiterAtTrip = 7.0,
            linkedAtMs = 1500L,
            source = TripSource.MANUAL,
            manualCost = 9.0,
            manualDistanceKm = 16.0,
            manualLitersPer100Km = 7.5,
            manualEnteredAtMs = 1800L,
        )

        val snapshot = original.toSnapshot()
        val restored = snapshot.toEntity()

        assertEquals(original.vehicleId, restored.vehicleId)
        assertEquals(original.startedAtMs, restored.startedAtMs)
        assertEquals(original.endedAtMs, restored.endedAtMs)
        assertEquals(original.distanceKm, restored.distanceKm, 1e-9)
        assertEquals(original.fuelL, restored.fuelL, 1e-9)
        assertEquals(original.avgSpeedKmh, restored.avgSpeedKmh, 1e-9)
        assertEquals(original.maxSpeedKmh, restored.maxSpeedKmh, 1e-9)
        assertEquals(original.idleSeconds, restored.idleSeconds, 1e-9)
        assertEquals(original.isOpen, restored.isOpen)
        assertEquals(original.routeSearchId, restored.routeSearchId)
        assertEquals(original.coldStartFuelL, restored.coldStartFuelL, 1e-9)
        assertEquals(original.actualCost, restored.actualCost, 1e-9)
        assertEquals(original.pricePerLiterAtTrip, restored.pricePerLiterAtTrip, 1e-9)
        assertEquals(original.linkedAtMs, restored.linkedAtMs)
        assertEquals(original.source, restored.source)
        assertEquals(original.manualCost, restored.manualCost, 1e-9)
        assertEquals(original.manualDistanceKm, restored.manualDistanceKm, 1e-9)
        assertEquals(original.manualLitersPer100Km, restored.manualLitersPer100Km, 1e-9)
        assertEquals(original.manualEnteredAtMs, restored.manualEnteredAtMs)
    }

    @Test
    fun `TripEntity with default source round-trips correctly`() {
        val original = TripEntity(
            id = 1L,
            vehicleId = "v2",
            startedAtMs = 5000L,
            endedAtMs = 6000L,
            distanceKm = 20.0,
            fuelL = 1.5,
            avgSpeedKmh = 60.0,
            maxSpeedKmh = 120.0,
            idleSeconds = 5.0,
            // source defaults to TripSource.REAL
        )

        val snapshot = original.toSnapshot()
        val restored = snapshot.toEntity()

        assertEquals(original.source, restored.source)
        assertEquals(TripSource.REAL, restored.source)
        assertNull(restored.manualCost)
        assertNull(restored.manualDistanceKm)
        assertNull(restored.manualLitersPer100Km)
        assertNull(restored.manualEnteredAtMs)
    }

    @Test
    fun `TripSnapshot deserializes from old JSON without source and manual fields`() {
        // Simulate a backup file created before the source and manual fields were added
        val oldJson = """
            {
                "vehicleId": "v3",
                "startedAtMs": 3000,
                "endedAtMs": 4000,
                "distanceKm": 18.0,
                "fuelL": 1.3,
                "avgSpeedKmh": 55.0,
                "maxSpeedKmh": 110.0,
                "idleSeconds": 8.0,
                "isOpen": 0,
                "routeSearchId": 3,
                "coldStartFuelL": 0.05,
                "actualCost": 9.0,
                "pricePerLiterAtTrip": 6.9,
                "linkedAtMs": 3500
            }
        """.trimIndent()

        val snapshot = json.decodeFromString(TripSnapshot.serializer(), oldJson)

        assertEquals("v3", snapshot.vehicleId)
        assertEquals(3000L, snapshot.startedAtMs)
        // Verify defaults are used for missing fields
        assertEquals("real", snapshot.source)
        assertNull(snapshot.manualCost)
        assertNull(snapshot.manualDistanceKm)
        assertNull(snapshot.manualLitersPer100Km)
        assertNull(snapshot.manualEnteredAtMs)
    }

    @Test
    fun `TripSnapshot with manual fields deserializes correctly`() {
        val jsonWithManualFields = """
            {
                "vehicleId": "v4",
                "startedAtMs": 7000,
                "endedAtMs": 8000,
                "distanceKm": 25.0,
                "fuelL": 1.8,
                "avgSpeedKmh": 70.0,
                "maxSpeedKmh": 130.0,
                "idleSeconds": 12.0,
                "isOpen": 0,
                "routeSearchId": null,
                "coldStartFuelL": 0.0,
                "actualCost": 0.0,
                "pricePerLiterAtTrip": 0.0,
                "linkedAtMs": null,
                "source": "manual",
                "manualCost": 15.5,
                "manualDistanceKm": 26.0,
                "manualLitersPer100Km": 6.9,
                "manualEnteredAtMs": 7500
            }
        """.trimIndent()

        val snapshot = json.decodeFromString(TripSnapshot.serializer(), jsonWithManualFields)

        assertEquals("v4", snapshot.vehicleId)
        assertEquals("manual", snapshot.source)
        assertEquals(15.5, snapshot.manualCost!!, 1e-9)
        assertEquals(26.0, snapshot.manualDistanceKm!!, 1e-9)
        assertEquals(6.9, snapshot.manualLitersPer100Km!!, 1e-9)
        assertEquals(7500L, snapshot.manualEnteredAtMs!!)
    }
}
