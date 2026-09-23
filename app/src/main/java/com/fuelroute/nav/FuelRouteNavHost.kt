package com.fuelroute.nav

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
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

/**
 * The four tabs, in reading order (rightmost first under RTL): plan a route, watch the live
 * drive, manage the car, settings. Everything else is a pushed screen with a back arrow.
 */
private enum class TopDestination(
    val route: String,
    @param:DrawableRes val icon: Int,
    @param:StringRes val labelRes: Int,
) {
    ROUTE("route", R.drawable.ic_navigation, R.string.nav_route),
    STATS("stats", R.drawable.ic_speed, R.string.nav_stats),
    VEHICLE("vehicle", R.drawable.ic_car, R.string.nav_vehicle),
    SETTINGS("settings", R.drawable.ic_tune, R.string.nav_settings),
}

private object SubRoute {
    const val CURVE = "curve"
    const val CALIBRATION = "calibration"
    const val REFUEL = "refuel"
    const val HISTORY = "history"
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

    val back: () -> Unit = { navController.popBackStack() }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.navigateToTab(destination.route) },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
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
                RouteScreen(onOpenHistory = { navController.navigate(SubRoute.HISTORY) })
            }
            composable(TopDestination.STATS.route) {
                StatsScreen(onOpenCurve = { navController.navigate(SubRoute.CURVE) })
            }
            composable(TopDestination.VEHICLE.route) {
                VehicleScreen(
                    onOpenRefuel = { navController.navigate(SubRoute.REFUEL) },
                    onOpenCurve = { navController.navigate(SubRoute.CURVE) },
                )
            }
            composable(TopDestination.SETTINGS.route) {
                SettingsScreen(onOpenCalibration = { navController.navigate(SubRoute.CALIBRATION) })
            }
            composable(SubRoute.CURVE) { CurveScreen(onBack = back) }
            composable(SubRoute.CALIBRATION) { CalibrationScreen(onBack = back) }
            composable(SubRoute.REFUEL) { RefuelScreen(onBack = back) }
            composable(SubRoute.HISTORY) { HistoryScreen(onBack = back) }
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/** Hilt access to the settings store for the first-run onboarding gate. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface OnboardingEntryPoint {
    fun settingsRepository(): SettingsRepository
}
