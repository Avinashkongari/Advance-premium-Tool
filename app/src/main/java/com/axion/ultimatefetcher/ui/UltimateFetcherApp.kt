package com.axion.ultimatefetcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.axion.ultimatefetcher.AppContainer
import com.axion.ultimatefetcher.UltimateFetcherApplication
import com.axion.ultimatefetcher.data.UserSettings
import com.axion.ultimatefetcher.ui.apilab.ApiLabScreen
import com.axion.ultimatefetcher.ui.apilab.ApiLabViewModel
import com.axion.ultimatefetcher.ui.dashboard.DashboardScreen
import com.axion.ultimatefetcher.ui.dashboard.DashboardViewModel
import com.axion.ultimatefetcher.ui.fetchers.FetchersScreen
import com.axion.ultimatefetcher.ui.fetchers.FetchersViewModel
import com.axion.ultimatefetcher.ui.logs.LogsScreen
import com.axion.ultimatefetcher.ui.logs.LogsViewModel
import com.axion.ultimatefetcher.ui.settings.SettingsScreen
import com.axion.ultimatefetcher.ui.settings.SettingsViewModel
import com.axion.ultimatefetcher.ui.theme.LocalCyberColors
import com.axion.ultimatefetcher.ui.theme.UltimateFetcherTheme

object AppRoutes {
    const val ROUTE_DASHBOARD = "dashboard"
    const val ROUTE_FETCHERS = "fetchers"
    const val ROUTE_APILAB = "apilab"
    const val ROUTE_LOGS = "logs"
    const val ROUTE_SETTINGS = "settings"
}

private data class NavDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val TopLevelDestinations = listOf(
    NavDestination(AppRoutes.ROUTE_DASHBOARD, "Dashboard", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
    NavDestination(AppRoutes.ROUTE_FETCHERS, "Fetchers", Icons.Filled.Radar, Icons.Outlined.Radar),
    NavDestination(AppRoutes.ROUTE_APILAB, "API Lab", Icons.Filled.Api, Icons.Outlined.Api),
    NavDestination(AppRoutes.ROUTE_LOGS, "Logs", Icons.Filled.ReceiptLong, Icons.Outlined.ReceiptLong),
    NavDestination(AppRoutes.ROUTE_SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
)

class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(DashboardViewModel::class.java) -> DashboardViewModel(container) as T
            modelClass.isAssignableFrom(FetchersViewModel::class.java) -> FetchersViewModel(container) as T
            modelClass.isAssignableFrom(ApiLabViewModel::class.java) -> ApiLabViewModel(container) as T
            modelClass.isAssignableFrom(LogsViewModel::class.java) -> LogsViewModel(container) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(container) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

@Composable
fun UltimateFetcherApp() {
    val context = LocalContext.current
    val app = context.applicationContext as UltimateFetcherApplication
    val container = app.container
    val factory = remember(container) { AppViewModelFactory(container) }

    val settings by container.settingsDataStore.settingsFlow.collectAsStateWithLifecycle(initialValue = UserSettings())

    UltimateFetcherTheme(themeMode = settings.themeMode) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route ?: AppRoutes.ROUTE_DASHBOARD

        val dashboardVm: DashboardViewModel = viewModel(factory = factory)
        val fetchersVm: FetchersViewModel = viewModel(factory = factory)
        val apiLabVm: ApiLabViewModel = viewModel(factory = factory)
        val logsVm: LogsViewModel = viewModel(factory = factory)
        val settingsVm: SettingsViewModel = viewModel(factory = factory)

        val cs = MaterialTheme.colorScheme
        val cyber = LocalCyberColors.current

        fun navigateTopLevel(route: String) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            cs.background,
                            if (cyber.isDark) cs.surface.copy(alpha = 0.65f) else cs.background
                        )
                    )
                )
        ) {
            val useRail = maxWidth >= 680.dp

            Scaffold(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (!useRail) {
                        NavigationBar(
                            containerColor = cs.surface.copy(alpha = 0.96f),
                            tonalElevation = 4.dp
                        ) {
                            TopLevelDestinations.forEach { dest ->
                                val selected = currentRoute == dest.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label
                                        )
                                    },
                                    label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = cyber.cyan,
                                        selectedTextColor = cyber.cyan,
                                        indicatorColor = cs.primary.copy(alpha = 0.22f)
                                    ),
                                    modifier = Modifier.testTag("nav_${dest.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useRail) {
                        NavigationRail(containerColor = cs.surface.copy(alpha = 0.94f)) {
                            TopLevelDestinations.forEach { dest ->
                                val selected = currentRoute == dest.route
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label
                                        )
                                    },
                                    label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.testTag("nav_${dest.route}")
                                )
                            }
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = AppRoutes.ROUTE_DASHBOARD,
                        modifier = Modifier.weight(1f).fillMaxSize()
                    ) {
                        composable(AppRoutes.ROUTE_DASHBOARD) {
                            DashboardScreen(viewModel = dashboardVm)
                        }
                        composable(AppRoutes.ROUTE_FETCHERS) {
                            FetchersScreen(
                                viewModel = fetchersVm,
                                onOpenInApiLab = { method, url, body ->
                                    apiLabVm.loadDraftFromExternal(method, url, body)
                                    navigateTopLevel(AppRoutes.ROUTE_APILAB)
                                }
                            )
                        }
                        composable(AppRoutes.ROUTE_APILAB) {
                            ApiLabScreen(viewModel = apiLabVm)
                        }
                        composable(AppRoutes.ROUTE_LOGS) {
                            LogsScreen(viewModel = logsVm)
                        }
                        composable(AppRoutes.ROUTE_SETTINGS) {
                            SettingsScreen(viewModel = settingsVm)
                        }
                    }
                }
            }
        }
    }
}
