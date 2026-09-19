package com.fuelroute.nav

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fuelroute.R
import com.fuelroute.ui.curve.CurveScreen
import com.fuelroute.ui.history.HistoryScreen
import com.fuelroute.ui.refuel.RefuelScreen
import com.fuelroute.ui.route.RouteScreen
import com.fuelroute.ui.settings.SettingsScreen
import com.fuelroute.ui.stats.StatsScreen
import com.fuelroute.ui.vehicle.VehicleScreen

private enum class TopDestination(
    val route: String,
    val icon: ImageVector,
    @param:StringRes val labelRes: Int,
) {
    ROUTE("route", Icons.Filled.Place, R.string.nav_route),
    VEHICLE("vehicle", Icons.Filled.Build, R.string.nav_vehicle),
    STATS("stats", Icons.AutoMirrored.Filled.List, R.string.nav_stats),
    SETTINGS("settings", Icons.Filled.Settings, R.string.nav_settings),
}

@Composable
fun FuelRouteNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.ROUTE.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopDestination.ROUTE.route) {
                RouteScreen(onOpenHistory = { navController.navigate("history") })
            }
            composable(TopDestination.VEHICLE.route) {
                VehicleScreen(onOpenRefuel = { navController.navigate("refuel") })
            }
            composable(TopDestination.STATS.route) {
                StatsScreen(onOpenCurve = { navController.navigate("curve") })
            }
            composable(TopDestination.SETTINGS.route) { SettingsScreen() }
            composable("curve") { CurveScreen() }
            composable("refuel") { RefuelScreen() }
            composable("history") { HistoryScreen() }
        }
    }
}