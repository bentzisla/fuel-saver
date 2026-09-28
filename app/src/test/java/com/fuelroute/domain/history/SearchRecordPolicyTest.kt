package com.fuelroute.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRecordPolicyTest {

    private val minute = 60_000L

    private fun key(
        id: Long,
        atMin: Long,
        origin: String = "המיקום הנוכחי",
        destination: String = "Work",
        placeId: String? = null,
        km: Double = 40.0,
        linked: Boolean = false,
        tripEndMin: Long? = null,
    ) = SearchKey(
        id = id,
        timestampMs = atMin * minute,
        originLabel = origin,
        destinationLabel = destination,
        destinationPlaceId = placeId,
        distanceKm = km,
        linked = linked,
        tripEndedAtMs = tripEndMin?.let { it * minute },
    )

    @Test
    fun `the first search is inserted`() {
        assertEquals(SearchRecordPolicy.Action.INSERT, SearchRecordPolicy.decide(null, key(1, 0), driveUnderway = false))
    }

    @Test
    fun `a refresh before departing replaces the previous row`() {
        assertEquals(
            SearchRecordPolicy.Action.REPLACE,
            SearchRecordPolicy.decide(key(1, 0), key(2, 5), driveUnderway = false),
        )
    }

    @Test
    fun `a refresh while the ride is being driven records nothing`() {
        assertEquals(
            SearchRecordPolicy.Action.SKIP,
            SearchRecordPolicy.decide(key(1, 0), key(2, 20), driveUnderway = true),
        )
    }

    @Test
    fun `a refresh from part-way along the route does not replace the full prediction`() {
        assertEquals(
            SearchRecordPolicy.Action.SKIP,
            SearchRecordPolicy.decide(key(1, 0, km = 40.0), key(2, 15, km = 20.0), driveUnderway = false),
        )
    }

    @Test
    fun `a search after the previous one was driven is a new ride`() {
        assertEquals(
            SearchRecordPolicy.Action.INSERT,
            SearchRecordPolicy.decide(key(1, 0, linked = true), key(2, 30), driveUnderway = false),
        )
    }

    @Test
    fun `another destination or an old search is a new ride`() {
        assertEquals(
            SearchRecordPolicy.Action.INSERT,
            SearchRecordPolicy.decide(key(1, 0), key(2, 5, destination = "Home"), driveUnderway = false),
        )
        assertEquals(
            SearchRecordPolicy.Action.INSERT,
            SearchRecordPolicy.decide(key(1, 0), key(2, 60), driveUnderway = false),
        )
    }

    @Test
    fun `place ids decide over labels when both searches have one`() {
        assertTrue(SearchRecordPolicy.isSameRide(key(1, 0, placeId = "p1"), key(2, 5, destination = "Other", placeId = "p1")))
        assertFalse(SearchRecordPolicy.isSameRide(key(1, 0, placeId = "p1"), key(2, 5, placeId = "p2")))
    }

    @Test
    fun `labels compare trimmed and case-insensitively`() {
        assertTrue(SearchRecordPolicy.isSameRide(key(1, 0, destination = " work "), key(2, 5, destination = "Work")))
    }

    @Test
    fun `older refreshes of a ride are hidden, the driven one stays`() {
        // The field case: two searches 5 minutes apart, the drive linked to the second one.
        val hidden = SearchRecordPolicy.hiddenDuplicates(
            listOf(key(5, 0), key(6, 5, linked = true, tripEndMin = 40)),
        )
        assertEquals(setOf(5L), hidden)
    }

    @Test
    fun `with nothing driven only the newest refresh stays`() {
        assertEquals(setOf(1L, 2L), SearchRecordPolicy.hiddenDuplicates(listOf(key(1, 0), key(2, 10), key(3, 20))))
    }

    @Test
    fun `a refresh made while the linked drive was underway is hidden`() {
        // Linked to the 08:00 search (drive until 08:40); the 08:20 refresh came from the road.
        val hidden = SearchRecordPolicy.hiddenDuplicates(
            listOf(key(1, 0, linked = true, tripEndMin = 40), key(2, 20)),
        )
        assertEquals(setOf(2L), hidden)
    }

    @Test
    fun `a search after the drive ended is kept as the next ride`() {
        val hidden = SearchRecordPolicy.hiddenDuplicates(
            listOf(key(1, 0, linked = true, tripEndMin = 20), key(2, 30)),
        )
        assertTrue(hidden.isEmpty())
    }

    @Test
    fun `different rides are never hidden`() {
        val hidden = SearchRecordPolicy.hiddenDuplicates(
            listOf(key(1, 0), key(2, 5, destination = "Home"), key(3, 10, origin = "Work", destination = "Gym")),
        )
        assertTrue(hidden.isEmpty())
    }

    @Test
    fun `linked searches are never hidden`() {
        val hidden = SearchRecordPolicy.hiddenDuplicates(
            listOf(key(1, 0, linked = true, tripEndMin = 5), key(2, 10, linked = true, tripEndMin = 30)),
        )
        assertTrue(hidden.isEmpty())
    }
}
