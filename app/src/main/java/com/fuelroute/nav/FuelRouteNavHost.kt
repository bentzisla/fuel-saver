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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.LaunchedEffect
import com.fuelroute.R
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.ui.curve.CurveScreen
import com.fuelroute.ui.debug.CalibrationScreen
import com.fuelroute.ui.history.HistoryScreen
import com.fuelroute.ui.onboarding.OnboardingScreen
import com.fuelroute.ui.refuel.RefuelScreen
import com.fuelroute.ui.route.RouteScreen
import com.fuelroute.ui.settings.SettingsScreen
import com.fuelroute.ui.stats.StatsScreen
import com.fuelroute.ui.vehicle.VehicleScreen
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

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
fun FuelRouteNavHost(openStats: Boolean = false) {
    val context = LocalContext.current
    val settingsRepository = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            OnboardingEntryPoint::class.java,
        ).settingsRepository()
    }
    // null until the first DataStore emission, so existing users never see a one-frame flash
    // of the onboarding screen (the default AppSettings has onboardingSeen == false).
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var demoRequested by remember { mutableStateOf(false) }

    // First-run setup: shown once before the tabs, gated on the persisted flag. It blocks
    // nothing — "סיימתי" simply records the flag and reveals the tabs.
    val current = settings
    if (current == null) return
    if (!current.onboardingSeen) {
        OnboardingScreen(
            onDone = { scope.launch { settingsRepository.saveOnboardingSeen(true) } },
            onTryDemo = {
                demoRequested = true
                scope.launch { settingsRepository.saveOnboardingSeen(true) }
            },
        )
        return
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // Deep link from the OBD logging notification (or the onboarding "try demo" button)
    // straight to the live dashboard.
    LaunchedEffect(openStats, demoRequested) {
        if (openStats || demoRequested) {
            demoRequested = false
            navController.navigate(TopDestination.STATS.route) {
                launchSingleTop = true
            }
        }
    }

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
            composable(TopDestination.SETTINGS.route) {
                SettingsScreen(onOpenCalibration = { navController.navigate("calibration") })
            }
            composable("curve") { CurveScreen() }
            composable("calibration") { CalibrationScreen() }
            composable("refuel") { RefuelScreen() }
            composable("history") { HistoryScreen() }
        }
    }
}

/** Hilt access to the settings store for the first-run onboarding gate. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface OnboardingEntryPoint {
    fun settingsRepository(): SettingsRepository
}