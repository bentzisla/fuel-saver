package com.fuelroute.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdRunGenerationTest {

    @Test
    fun `a fresh generation supersedes every prior token`() {
        val generation = ObdRunGeneration()
        val first = generation.next()
        assertTrue(generation.isCurrent(first))

        val second = generation.next()
        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
    }

    @Test
    fun `a newer start invalidates an older run's finally`() {
        val generation = ObdRunGeneration()
        val oldRun = generation.next()
        val newRun = generation.next()
        assertFalse(generation.isCurrent(oldRun))
        assertTrue(generation.isCurrent(newRun))
        assertEquals(newRun, generation.current())
    }
}