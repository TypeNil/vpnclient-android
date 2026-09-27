package dev.typenil.vpnclient.ui

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Snackbar
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.common.ThemeMode
import dev.typenil.vpnclient.core.subscription.ImportUrlExtractor.ExtractedImport
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.data.settings.SettingsRepository
import dev.typenil.vpnclient.ui.appfilter.AppFilterScreen
import dev.typenil.vpnclient.ui.connections.ConnectionsScreen
import dev.typenil.vpnclient.ui.diagnostics.DiagnosticsScreen
import dev.typenil.vpnclient.ui.home.HomeScreen
import dev.typenil.vpnclient.ui.qrscan.QR_RESULT_KEY
import dev.typenil.vpnclient.ui.qrscan.QrScanScreen
import dev.typenil.vpnclient.ui.routing.RoutingScreen
import dev.typenil.vpnclient.ui.servers.ServersScreen
import dev.typenil.vpnclient.ui.settings.SettingsScreen
import dev.typenil.vpnclient.ui.subscriptions.SubscriptionsScreen
import dev.typenil.vpnclient.ui.theme.AfterglowNavigation
import dev.typenil.vpnclient.ui.theme.AfterglowTheme
import dev.typenil.vpnclient.ui.theme.AfterglowTokens
import dev.typenil.vpnclient.ui.theme.DarkAfterglow
import dev.typenil.vpnclient.ui.theme.VPNClientTheme
import kotlinx.coroutines.flow.StateFlow

object Routes {
    const val HOME = "home"
    const val SERVERS = "servers"
    const val SUBSCRIPTIONS = "subscriptions"
    const val SETTINGS = "settings"
    const val ROUTING = "routing"
    const val APP_FILTER = "app_filter"
    const val QR_SCAN = "qr_scan"
    const val CONNECTIONS = "connections"
    const val DIAGNOSTICS = "diagnostics"
}

/** savedStateHandle flag: the SUBSCRIPTIONS entry should open its add
 *  dialog. Posted by cross-tab entry points (e.g. the Servers empty
 *  state) — same handoff mechanism QR results use. */
private const val ADD_DIALOG_OPEN_KEY = "open_add_dialog"

private data class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val serverRackIcon = ImageVector.Builder("Server rack", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        listOf(3f, 10f, 17f).forEach { top ->
            moveTo(3f, top); lineTo(21f, top); lineTo(21f, top + 5f)
            lineTo(3f, top + 5f); close()
        }
    }
}.build()

private val articleIcon = ImageVector.Builder("Article", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
        moveTo(5f, 2f); lineTo(15f, 2f); lineTo(20f, 7f)
        lineTo(20f, 22f); lineTo(5f, 22f); close()
        moveTo(15f, 2f); lineTo(15f, 7f); lineTo(20f, 7f)
        moveTo(8f, 12f); lineTo(17f, 12f)
        moveTo(8f, 16f); lineTo(17f, 16f)
    }
}.build()

private val topLevelDestinations =
    listOf(
        TopLevelDestination(Routes.HOME, R.string.nav_home, Icons.Default.Home),
        TopLevelDestination(Routes.SERVERS, R.string.nav_servers, serverRackIcon),
        TopLevelDestination(Routes.SUBSCRIPTIONS, R.string.nav_subscriptions, articleIcon),
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
    settings: SettingsRepository,
    importUrl: StateFlow<ExtractedImport?>,
    onImportConsumed: () -> Unit,
) {
    // Theme settings read live from DataStore — a change recomposes the root
    // without an activity restart; the VPN session is untouched either way.
    val themeMode by settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
    val dynamicColor by settings.dynamicColor.collectAsStateWithLifecycle(initialValue = false)
    VPNClientTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
        val navController = rememberNavController()
        val snackbarHostState = remember { SnackbarHostState() }

        val pendingImport by importUrl.collectAsStateWithLifecycle()
        LaunchedEffect(pendingImport) {
            // Re-navigating to SUBSCRIPTIONS while already on it recreates the
            // back-stack entry — the dialog state the screen's import effect
            // sets is rememberSaveable to the *old* entry and dies with it.
            if (pendingImport != null &&
                navController.currentDestination?.route != Routes.SUBSCRIPTIONS
            ) {
                // Same options as bottom nav.
                navController.navigate(Routes.SUBSCRIPTIONS) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                }
            }
        }

        val prepareIntent by connectionManager.prepareIntent.collectAsStateWithLifecycle()
        val vpnConsentLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                connectionManager.onPermissionResult(result.resultCode == Activity.RESULT_OK)
            }
        LaunchedEffect(prepareIntent) {
            prepareIntent?.let { vpnConsentLauncher.launch(it) }
        }

        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val colors = AfterglowTheme.colors
        val darkAfterglow = colors == DarkAfterglow
        val activity = LocalActivity.current
        DisposableEffect(activity, colors) {
            val window = activity?.window
            val previousStatus = window?.statusBarColor
            val previousNavigation = window?.navigationBarColor
            val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
            val previousLightStatus = controller?.isAppearanceLightStatusBars
            val previousLightNavigation = controller?.isAppearanceLightNavigationBars
            if (window != null) {
                window.statusBarColor = colors.paper.toArgb()
                window.navigationBarColor = colors.paperSecondary.toArgb()
                controller?.isAppearanceLightStatusBars = !darkAfterglow
                controller?.isAppearanceLightNavigationBars = !darkAfterglow
            }
            onDispose {
                if (window != null) {
                    if (previousStatus != null) window.statusBarColor = previousStatus
                    if (previousNavigation != null) window.navigationBarColor = previousNavigation
                    if (previousLightStatus != null) controller?.isAppearanceLightStatusBars = previousLightStatus
                    if (previousLightNavigation != null) controller?.isAppearanceLightNavigationBars = previousLightNavigation
                }
            }
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = colors.paper,
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(data, containerColor = colors.ink, contentColor = colors.paper,
                        actionColor = colors.coralLight, shape = AfterglowTokens.cardShape)
                }
            },
            bottomBar = {
                Column {
                    HorizontalDivider(thickness = AfterglowTokens.border, color = colors.border)
                    NavigationBar(containerColor = colors.paperSecondary, tonalElevation = 0.dp) {
                    topLevelDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected =
                                currentDestination?.hierarchy?.any {
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
                                    modifier = Modifier.size(23.dp),
                                )
                            },
                            label = { Text(stringResource(destination.labelRes), style = AfterglowNavigation,
                                maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.onActionSurface,
                                selectedTextColor = colors.coral,
                                unselectedIconColor = colors.muted,
                                unselectedTextColor = colors.muted,
                                indicatorColor = colors.actionSurface,
                            ),
                        )
                    }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onOpenConnections = { navController.navigate(Routes.CONNECTIONS) },
                        onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                        onOpenRouting = { navController.navigate(Routes.ROUTING) },
                        // The picker's "all servers" escape hatch — the
                        // Servers tab owns filters, sorting and latency tests.
                        onOpenServers = {
                            navController.navigate(Routes.SERVERS) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        // Same cross-tab add funnel the Servers empty state
                        // uses — see the comment there.
                        onAddServer = {
                            navController.navigate(Routes.SUBSCRIPTIONS) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                            }
                            navController.currentBackStackEntry
                                ?.savedStateHandle
                                ?.set(ADD_DIALOG_OPEN_KEY, true)
                        },
                    )
                }
                composable(Routes.SERVERS) { entry ->
                    // Cross-tab add funnel: navigating here from the empty
                    // state's button lands on SUBSCRIPTIONS and posts the
                    // "open add dialog" signal into that entry's
                    // savedStateHandle — same slot the QR/deep-link funnel
                    // uses, no VM indirection.
                    ServersScreen(
                        onAddServer = {
                            navController.navigate(Routes.SUBSCRIPTIONS) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                            }
                            // Set after navigate(): savedStateHandle is
                            // fetched from the now-top entry — get() before
                            // the navigate would target the wrong entry.
                            navController.currentBackStackEntry
                                ?.savedStateHandle
                                ?.set(ADD_DIALOG_OPEN_KEY, true)
                        },
                    )
                }
                composable(Routes.SUBSCRIPTIONS) { entry ->
                    // A QR result arrives via the back-stack entry's
                    // savedStateHandle; it feeds the same prefilled-dialog
                    // funnel as deep links.
                    val qrResult by entry.savedStateHandle
                        .getStateFlow<String?>(QR_RESULT_KEY, null)
                        .collectAsStateWithLifecycle()
                    // "Open the add dialog" signal posted by cross-tab entry
                    // points (Servers empty state) — consumed below.
                    val openAddDialog by entry.savedStateHandle
                        .getStateFlow(ADD_DIALOG_OPEN_KEY, false)
                        .collectAsStateWithLifecycle()
                    SubscriptionsScreen(
                        snackbarHostState = snackbarHostState,
                        import = pendingImport ?: qrResult?.let { ExtractedImport(it, null) },
                        openAddDialog = openAddDialog,
                        onAddDialogSignalConsumed = {
                            entry.savedStateHandle.remove<Boolean>(ADD_DIALOG_OPEN_KEY)
                        },
                        onImportConsumed = {
                            if (pendingImport != null) onImportConsumed()
                            // Always drop a stale scan result too — it would
                            // otherwise surface as a second import once the
                            // ?: source flips back to it.
                            entry.savedStateHandle.remove<String>(QR_RESULT_KEY)
                        },
                        onScanQr = {
                            navController.navigate(Routes.QR_SCAN) { launchSingleTop = true }
                        },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onOpenRouting = { navController.navigate(Routes.ROUTING) },
                        onOpenAppFilter = { navController.navigate(Routes.APP_FILTER) },
                        onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                        snackbarHostState = snackbarHostState,
                    )
                }
                composable(Routes.ROUTING) {
                    RoutingScreen(
                        onBack = { navController.popBackStack() },
                        onOpenAppFilter = { navController.navigate(Routes.APP_FILTER) },
                        snackbarHostState = snackbarHostState,
                    )
                }
                composable(Routes.APP_FILTER) {
                    AppFilterScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.CONNECTIONS) {
                    ConnectionsScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.DIAGNOSTICS) {
                    DiagnosticsScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.QR_SCAN) {
                    QrScanScreen(
                        onBack = { navController.popBackStack() },
                        onResult = { url ->
                            // A decode can complete after the user already
                            // backed out — only deliver while the scan entry
                            // is still on top, else the URL lands on the
                            // wrong back-stack entry and pop() eats one too
                            // many screens.
                            if (navController.currentDestination?.route == Routes.QR_SCAN) {
                                navController.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set(QR_RESULT_KEY, url)
                                navController.popBackStack()
                            }
                        },
                    )
                }
            }
        }
    }
}
