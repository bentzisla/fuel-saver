package com.fuelroute.ui.route

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fuelroute.data.routes.PolylineDecoder
import com.fuelroute.domain.model.Route
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState

/**
 * Draws every route and highlights the user's [selectedIndex] one.
 *
 * The selected polyline is drawn last (on top) with a white casing so it stands out from the
 * muted alternatives, and the camera re-fits to the selected route whenever the selection
 * changes. Each polyline gets a numbered badge so it can be matched to its result card.
 */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun RouteMap(
    routes: List<Route>,
    modifier: Modifier = Modifier,
    selectedIndex: Int = 0,
) {
    val cameraPositionState = rememberCameraPositionState()

    val polylines: List<List<LatLng>?> = remember(routes) {
        routes.map { route ->
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
        // Muted alternatives first, so the selected route is painted on top of them.
        polylines.forEachIndexed { index, points ->
            if (points != null && index != selectedIndex) {
                Polyline(
                    points = points,
                    color = OtherColor,
                    width = 5f,
                )
            }
        }

        polylines.getOrNull(selectedIndex)?.let { points ->
            Polyline(points = points, color = SelectedCasing, width = 13f)
            Polyline(points = points, color = SelectedColor, width = 9f)
        }

        polylines.forEachIndexed { index, points ->
            if (points != null) {
                val labelPoint = points[points.size / 2]
                MarkerComposable(
                    state = rememberUpdatedMarkerState(labelPoint),
                    anchor = Offset(0.5f, 0.5f),
                ) {
                    RouteNumberBadge(number = index + 1, selected = index == selectedIndex)
                }
            }
        }
    }

    LaunchedEffect(mapReady, polylines, selectedIndex) {
        if (!mapReady) return@LaunchedEffect
        val selected = polylines.getOrNull(selectedIndex)
        val target = selected ?: polylines.filterNotNull().flatten()
        if (target.size < 2) return@LaunchedEffect
        val builder = LatLngBounds.Builder()
        target.forEach { builder.include(it) }
        cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(builder.build(), 80))
    }
}

@Composable
private fun RouteNumberBadge(number: Int, selected: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(if (selected) SelectedColor else OtherColor, CircleShape)
            .border(width = if (selected) 2.dp else 1.dp, color = Color.White, shape = CircleShape)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

private val SelectedColor = Color(0xFF1B6B4A)
private val SelectedCasing = Color(0xFFFFFFFF)
private val OtherColor = Color(0xFF607D8B)
