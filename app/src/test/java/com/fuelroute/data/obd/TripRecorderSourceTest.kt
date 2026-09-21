package com.fuelroute.data.obd

import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.TripEntity
import com.fuelroute.data.db.TripSource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trip row is fully rebuilt on every checkpoint/end, so a demo marker must be carried
 * through [TripRecorder] instead of being written once at insert (card 46).
 */
class TripRecorderSourceTest {

    @Test
    fun `demo source is written at insert and survives checkpoint and end`() = runTest {
        val dao = mockk<TripDao>()
        val inserted = slot<TripEntity>()
        val updates = mutableListOf<TripEntity>()
        coEvery { dao.insert(capture(inserted)) } returns 42L
        coEvery { dao.update(capture(updates)) } just Runs

        val recorder = TripRecorder(dao)
        recorder.start("v1", startedAtMs = 1_000L, source = TripSource.DEMO)
        recorder.checkpoint(
            vehicleId = "v1",
            nowMs = 2_000L,
            distanceKm = 1.0,
            fuelL = 0.1,
            maxSpeedKmh = 50.0,
            idleSeconds = 0.0,
        )
        recorder.end(
            vehicleId = "v1",
            endedAtMs = 3_000L,
            distanceKm = 2.0,
            fuelL = 0.2,
            maxSpeedKmh = 60.0,
            idleSeconds = 0.0,
        )

        assertEquals(TripSource.DEMO, inserted.captured.source)
        assertEquals(2, updates.size)
        assertTrue(updates.all { it.source == TripSource.DEMO })
        assertEquals(42L, updates.first().id)
    }

    @Test
    fun `a recorder started without an explicit source stays real`() = runTest {
        val dao = mockk<TripDao>()
        val inserted = slot<TripEntity>()
        val updates = mutableListOf<TripEntity>()
        coEvery { dao.insert(capture(inserted)) } returns 7L
        coEvery { dao.update(capture(updates)) } just Runs

        val recorder = TripRecorder(dao)
        recorder.start("v1", 1_000L)
        recorder.checkpoint("v1", 2_000L, 1.0, 0.1, 50.0, 0.0)

        assertEquals(TripSource.REAL, inserted.captured.source)
        assertEquals(TripSource.REAL, updates.single().source)
    }
}
