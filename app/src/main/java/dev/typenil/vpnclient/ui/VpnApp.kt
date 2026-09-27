package dev.typenil.vpnclient.ui

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

/** savedStateHandle flag for the Subscriptions add dialog, posted by entry points. */
private const val ADD_DIALOG_OPEN_KEY = "open_add_dialog"

/**
 * Root composable: theme + NavHost.
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
                navController.navigate(Routes.SUBSCRIPTIONS) { launchSingleTop = true }
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

        val currentEntry by navController.currentBackStackEntryAsState()
        val topLevelTitle = when (currentEntry?.destination?.route) {
            Routes.SERVERS -> R.string.nav_servers
            Routes.SUBSCRIPTIONS -> R.string.nav_subscriptions
            Routes.SETTINGS -> R.string.nav_settings
            else -> null
        }
        val colors = AfterglowTheme.colors
        val darkAfterglow = colors == DarkAfterglow
        val activity = LocalActivity.current
        DisposableEffect(activity, colors) {
            val window = activity?.window
            val previousStatus = window?.statusBarColor
            val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
            val previousLightStatus = controller?.isAppearanceLightStatusBars
            val previousLightNavigation = controller?.isAppearanceLightNavigationBars
            if (window != null) {
                window.statusBarColor = colors.paper.toArgb()
                controller?.isAppearanceLightStatusBars = !darkAfterglow
                controller?.isAppearanceLightNavigationBars = !darkAfterglow
            }
            onDispose {
                if (window != null) {
                    if (previousStatus != null) window.statusBarColor = previousStatus
                    if (previousLightStatus != null) controller?.isAppearanceLightStatusBars = previousLightStatus
                    if (previousLightNavigation != null) controller?.isAppearanceLightNavigationBars = previousLightNavigation
                }
            }
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = colors.paper,
            // Home scrolls beneath gesture navigation; other screens keep their existing safe insets.
            contentWindowInsets = if (currentEntry?.destination?.route == Routes.HOME)
                WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            else ScaffoldDefaults.contentWindowInsets,
            topBar = {
                if (topLevelTitle != null) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back), tint = colors.ink)
                        }
                        Text(stringResource(topLevelTitle), color = colors.ink,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(data, containerColor = colors.ink, contentColor = colors.paper,
                        actionColor = colors.coralLight, shape = AfterglowTokens.cardShape)
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
                        onOpenSubscriptions = { navController.navigate(Routes.SUBSCRIPTIONS) { launchSingleTop = true } },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                        onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                        onOpenRouting = { navController.navigate(Routes.ROUTING) },
                        // The full Servers screen owns filters, sorting and latency tests.
                        onOpenServers = { navController.navigate(Routes.SERVERS) { launchSingleTop = true } },
                        onAddServer = {
                            navController.navigate(Routes.SUBSCRIPTIONS) { launchSingleTop = true }
                            navController.currentBackStackEntry
                                ?.savedStateHandle
                                ?.set(ADD_DIALOG_OPEN_KEY, true)
                        },
                    )
                }
                composable(Routes.SERVERS) {
                    ServersScreen(
                        onAddServer = {
                            navController.navigate(Routes.SUBSCRIPTIONS) { launchSingleTop = true }
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
