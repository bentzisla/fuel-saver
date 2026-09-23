package com.fuelroute.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDeviceOrderingTest {

    private fun bonded(address: String, name: String? = null) = BondedObdDevice(address, name)

    @Test
    fun `last used device is first even when it is not a favorite`() {
        val sorted = ObdDeviceOrdering.merge(
            bonded = listOf(
                bonded("AA:AA", "Alpha"),
                bonded("BB:BB", "Beta"),
                bonded("CC:CC", "Gamma"),
            ),
            favoriteAddresses = setOf("BB:BB"),
            lastDeviceAddress = "CC:CC",
        )

        assertEquals(listOf("CC:CC", "BB:BB", "AA:AA"), sorted.map { it.address })
        assertTrue(sorted.first().isLastUsed)
        assertTrue(sorted[1].isFavorite)
    }

    @Test
    fun `favorites come before non-favorites and each group is alphabetical by name`() {
        val sorted = ObdDeviceOrdering.merge(
            bonded = listOf(
                bonded("1", "Zeta"),
                bonded("2", "Alpha"),
                bonded("3", "Mike"),
                bonded("4", "Bravo"),
            ),
            favoriteAddresses = setOf("1", "3"),
            lastDeviceAddress = null,
        )

        // Favorites: Mike (3), Zeta (1); rest: Alpha (2), Bravo (4).
        assertEquals(listOf("3", "1", "2", "4"), sorted.map { it.address })
    }

    @Test
    fun `a last-used favorite is not duplicated and still ranks first`() {
        val sorted = ObdDeviceOrdering.merge(
            bonded = listOf(
                bonded("1", "Alpha"),
                bonded("2", "Beta"),
            ),
            favoriteAddresses = setOf("2"),
            lastDeviceAddress = "2",
        )

        assertEquals(2, sorted.size)
        assertEquals("2", sorted.first().address)
        assertTrue(sorted.first().isLastUsed)
        assertTrue(sorted.first().isFavorite)
    }

    @Test
    fun `address matching for favorite and last-used is case-insensitive`() {
        val sorted = ObdDeviceOrdering.merge(
            bonded = listOf(
                bonded("AA:BB:CC", "Dongle"),
                bonded("DD:EE:FF", "Other"),
            ),
            favoriteAddresses = setOf("aa:bb:cc"),
            lastDeviceAddress = "dd:ee:ff",
        )

        assertEquals("DD:EE:FF", sorted.first().address)
        assertTrue(sorted.first().isLastUsed)
        assertTrue(sorted.last().isFavorite)
    }

    @Test
    fun `blank name falls back to the address`() {
        val sorted = ObdDeviceOrdering.merge(
            bonded = listOf(bonded("AA:AA", "   ")),
            favoriteAddresses = emptySet(),
            lastDeviceAddress = null,
        )

        assertEquals("AA:AA", sorted.single().name)
    }

    @Test
    fun `sort is stable for equal names using the address as tie-break`() {
        val sorted = ObdDeviceOrdering.sort(
            listOf(
                ObdDevice("BB:BB", "Same"),
                ObdDevice("AA:AA", "same"),
                ObdDevice("CC:CC", "Same"),
            ),
        )

        assertEquals(listOf("AA:AA", "BB:BB", "CC:CC"), sorted.map { it.address })
    }

    @Test
    fun `non-favorite non-last devices keep alphabetical order`() {
        val sorted = ObdDeviceOrdering.merge(
            bonded = listOf(
                bonded("1", "Charlie"),
                bonded("2", "alpha"),
                bonded("3", "Bravo"),
            ),
            favoriteAddresses = emptySet(),
            lastDeviceAddress = null,
        )

        assertEquals(listOf("2", "3", "1"), sorted.map { it.address })
        assertTrue(sorted.none { it.isFavorite || it.isLastUsed })
        assertFalse(sorted.first().isLastUsed)
    }
}