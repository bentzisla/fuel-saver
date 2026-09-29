package com.fuelroute.data.obd

import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.TripEntity
import com.fuelroute.data.db.TripSource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder writes the provenance once at insert and afterwards only touches the columns it
 * owns (card 46 + targeted updates), and continues a trip that closed moments ago.
 */
class TripRecorderSourceTest {

    private fun dao(recent: List<TripEntity> = emptyList()): TripDao = mockk<TripDao>().also { dao ->
        coEvery { dao.insert(any()) } returns 42L
        coEvery { dao.recentForVehicle(any(), any()) } returns recent
        coEvery { dao.updateRecordedProgress(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { dao.closeRecorded(any(), any(), any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { dao.reopen(any()) } just Runs
    }

    private fun trip(
        id: Long = 7L,
        endedAtMs: Long,
        source: String = TripSource.REAL,
        manual: Boolean = false,
        routeSearchId: Int? = null,
    ) = TripEntity(
        id = id,
        vehicleId = "v1",
        startedAtMs = 0L,
        endedAtMs = endedAtMs,
        distanceKm = 10.0,
        fuelL = 0.8,
        avgSpeedKmh = 40.0,
        maxSpeedKmh = 90.0,
        idleSeconds = 30.0,
        isOpen = 0,
        source = source,
        routeSearchId = routeSearchId,
        manualEnteredAtMs = if (manual) 1L else null,
    )

    @Test
    fun `demo source is written at insert and checkpoints never rewrite whole rows`() = runTest {
        val dao = dao()
        val inserted = slot<TripEntity>()
        coEvery { dao.insert(capture(inserted)) } returns 42L

        val recorder = TripRecorder(dao)
        recorder.startOrContinue("v1", startedAtMs = 1_000L, source = TripSource.DEMO)
        recorder.checkpoint("v1", nowMs = 2_000L, distanceKm = 1.0, fuelL = 0.1, maxSpeedKmh = 50.0, idleSeconds = 0.0)
        val id = recorder.end("v1", endedAtMs = 3_000L, distanceKm = 2.0, fuelL = 0.2, maxSpeedKmh = 60.0, idleSeconds = 0.0, pricePerLiter = 7.0)

        assertEquals(TripSource.DEMO, inserted.captured.source)
        assertEquals(42L, id)
        coVerify(exactly = 1) { dao.updateRecordedProgress(42L, 2_000L, 1.0, 0.1, any(), 50.0, 0.0) }
        coVerify(exactly = 1) { dao.closeRecorded(42L, 3_000L, 2.0, 0.2, any(), 60.0, 0.0, 0.2 * 7.0, 7.0) }
        // No full-row @Update: a route link / manual entry / cold-start figure is never reset.
        coVerify(exactly = 0) { dao.update(any()) }
        assertFalse(recorder.isOpen)
    }

    @Test
    fun `a recorder started without an explicit source stays real`() = runTest {
        val dao = dao()
        val inserted = slot<TripEntity>()
        coEvery { dao.insert(capture(inserted)) } returns 7L

        TripRecorder(dao).start("v1", 1_000L)

        assertEquals(TripSource.REAL, inserted.captured.source)
    }

    @Test
    fun `a trip closed less than 3 minutes ago is continued instead of a new row`() = runTest {
        val dao = dao(listOf(trip(endedAtMs = 100_000L, routeSearchId = 5)))
        val recorder = TripRecorder(dao)

        val continued = recorder.startOrContinue("v1", startedAtMs = 100_000L + 20_000L)

        assertNotNull(continued)
        assertEquals(10.0, continued!!.distanceKm, 1e-9)
        assertEquals(0.8, continued.fuelL, 1e-9)
        assertEquals(0L, recorder.tripStartedAtMs)
        assertTrue(recorder.isOpen)
        assertTrue(recorder.continuedLinkedTrip)
        coVerify { dao.reopen(7L) }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `an old, manual or other-source trip is never continued`() = runTest {
        for (last in listOf(
            trip(endedAtMs = 0L),
            trip(endedAtMs = 100_000L, manual = true),
            trip(endedAtMs = 100_000L, source = TripSource.DEMO),
        )) {
            val dao = dao(listOf(last))
            val continued = TripRecorder(dao).startOrContinue("v1", startedAtMs = 200_000L)
            assertNull(continued)
            coVerify(exactly = 1) { dao.insert(any()) }
            coVerify(exactly = 0) { dao.reopen(any()) }
        }
    }

    @Test
    fun `end forgets the trip even when the write fails`() = runTest {
        val dao = dao()
        coEvery { dao.closeRecorded(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("disk full")
        val recorder = TripRecorder(dao)
        recorder.start("v1", 1_000L)

        runCatching { recorder.end("v1", 2_000L, 1.0, 0.1, 50.0, 0.0) }

        assertFalse(recorder.isOpen)
    }
}
