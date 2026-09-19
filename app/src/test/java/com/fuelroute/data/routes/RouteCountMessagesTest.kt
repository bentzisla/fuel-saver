package com.fuelroute.data.routes

import com.fuelroute.R
import com.fuelroute.ui.route.RouteCountMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteCountMessagesTest {

    @Test
    fun `no routes maps to the no routes message`() {
        assertEquals(R.string.route_no_routes, RouteCountMessages.message(0))
    }

    @Test
    fun `single route maps to the single route message`() {
        assertEquals(R.string.route_single, RouteCountMessages.message(1))
    }

    @Test
    fun `multiple routes map to no message`() {
        assertNull(RouteCountMessages.message(2))
    }
}