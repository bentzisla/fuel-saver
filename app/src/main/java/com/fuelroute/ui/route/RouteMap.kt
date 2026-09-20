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
 * The selected polyline is drawn last (on top) with a white casing and a thicker stroke, while
 * every alternative keeps its own distinct colour so all routes stay legible at a glance; the
 * camera re-fits to the selected route whenever the selection changes. Each polyline gets a
 * numbered badge (matching its colour) so it can be matched to its result card.
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
        // Alternatives first with distinct colours, selected route painted on top.
        polylines.forEachIndexed { index, points ->
            if (points != null && index != selectedIndex) {
                Polyline(
                    points = points,
                    color = routeColor(index),
                    width = 6f,
                )
            }
        }

        polylines.getOrNull(selectedIndex)?.let { points ->
            Polyline(points = points, color = SelectedCasing, width = 14f)
            Polyline(points = points, color = routeColor(selectedIndex), width = 10f)
        }

        polylines.forEachIndexed { index, points ->
            if (points != null) {
                val labelPoint = points[points.size / 2]
                MarkerComposable(
                    state = rememberUpdatedMarkerState(labelPoint),
                    anchor = Offset(0.5f, 0.5f),
                ) {
                    RouteNumberBadge(
                        number = index + 1,
                        selected = index == selectedIndex,
                        color = routeColor(index),
                    )
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
private fun RouteNumberBadge(number: Int, selected: Boolean, color: Color) {
    Box(
        modifier = Modifier
            .size(if (selected) 24.dp else 22.dp)
            .background(color, CircleShape)
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

private val SelectedCasing = Color(0xFFFFFFFF)

private val RouteColors = listOf(
    Color(0xFF1B6B4A), // green
    Color(0xFF1565C0), // blue
    Color(0xFFEF6C00), // orange
    Color(0xFF6A1B9A), // purple
    Color(0xFF00838F), // teal
    Color(0xFFAD1457), // pink
)

private fun routeColor(index: Int): Color =
    RouteColors[((index % RouteColors.size) + RouteColors.size) % RouteColors.size]
