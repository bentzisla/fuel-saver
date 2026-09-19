package com.fuelroute.ui.route

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fuelroute.data.routes.PolylineDecoder
import com.fuelroute.domain.model.Route
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState

@Composable
fun RouteMap(
    routes: List<Route>,
    modifier: Modifier = Modifier,
) {
    val cameraPositionState = rememberCameraPositionState()

    val polylines = remember(routes) {
        routes.mapNotNull { route ->
            route.encodedPolyline?.let { encoded ->
                PolylineDecoder.decode(encoded)
                    .map { LatLng(it.lat, it.lng) }
                    .takeIf { it.size >= 2 }
            }
        }
    }

    var mapReady by remember { mutableStateOf(false) }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        onMapLoaded = { mapReady = true },
    ) {
        polylines.forEachIndexed { index, points ->
            Polyline(
                points = points,
                color = if (index == 0) CheapestColor else OtherColor,
                width = if (index == 0) 9f else 6f,
            )
        }
    }

    LaunchedEffect(mapReady, polylines) {
        if (mapReady) {
            val all = polylines.flatten()
            if (all.isNotEmpty()) {
                val builder = LatLngBounds.Builder()
                all.forEach { builder.include(it) }
                cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(builder.build(), 80))
            }
        }
    }
}

private val CheapestColor = Color(0xFF1B6B4A)
private val OtherColor = Color(0xFF90A4AE)