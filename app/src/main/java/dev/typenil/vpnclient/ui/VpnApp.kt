package dev.typenil.vpnclient.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.typenil.vpnclient.R
import kotlinx.coroutines.flow.StateFlow
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.ui.appfilter.AppFilterScreen
import dev.typenil.vpnclient.ui.home.HomeScreen
import dev.typenil.vpnclient.ui.servers.ServersScreen
import dev.typenil.vpnclient.ui.settings.SettingsScreen
import dev.typenil.vpnclient.ui.subscriptions.SubscriptionsScreen
import dev.typenil.vpnclient.ui.theme.VPNClientTheme

object Routes {
    const val HOME = "home"
    const val SERVERS = "servers"
    const val SUBSCRIPTIONS = "subscriptions"
    const val SETTINGS = "settings"
    const val APP_FILTER = "app_filter"
}

private data class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(Routes.HOME, R.string.nav_home, Icons.Default.Home),
    TopLevelDestination(Routes.SERVERS, R.string.nav_servers, Icons.Default.List),
    TopLevelDestination(Routes.SUBSCRIPTIONS, R.string.nav_subscriptions, Icons.Default.Share),
    TopLevelDestination(Routes.SETTINGS, R.string.nav_settings, Icons.Default.Settings),
)

/**
 * Root composable: theme + bottom nav + NavHost.
 *
 * The VPN consent launcher lives here so it fires from whichever screen is
 * visible when [ConnectionManager.prepareIntent] is posted.
 */
@Composable
fun VpnApp(
    connectionManager: ConnectionManager,
    importUrl: StateFlow<String?>,
    onImportConsumed: () -> Unit,
) {
    VPNClientTheme {
        val navController = rememberNavController()
        val snackbarHostState = remember { SnackbarHostState() }

        val pendingImport by importUrl.collectAsStateWithLifecycle()
        LaunchedEffect(pendingImport) {
            if (pendingImport != null) {
                // Same options as bottom nav — a second link while already on
                // Subscriptions must not stack a duplicate destination.
                navController.navigate(Routes.SUBSCRIPTIONS) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                }
            }
        }

        val prepareIntent by connectionManager.prepareIntent.collectAsStateWithLifecycle()
        val vpnConsentLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            connectionManager.onPermissionResult(result.resultCode == Activity.RESULT_OK)
        }
        LaunchedEffect(prepareIntent) {
            prepareIntent?.let { vpnConsentLauncher.launch(it) }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    val backStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = backStackEntry?.destination
                    topLevelDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentDestination?.hierarchy?.any {
                                it.route == destination.route
                            } == true,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    destination.icon,
                                    contentDescription = stringResource(destination.labelRes),
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Routes.HOME) { HomeScreen() }
                composable(Routes.SERVERS) {
                    ServersScreen(snackbarHostState = snackbarHostState)
                }
                composable(Routes.SUBSCRIPTIONS) {
                    SubscriptionsScreen(
                        snackbarHostState = snackbarHostState,
                        importUrl = pendingImport,
                        onImportConsumed = onImportConsumed,
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onOpenAppFilter = { navController.navigate(Routes.APP_FILTER) },
                    )
                }
                composable(Routes.APP_FILTER) {
                    AppFilterScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
