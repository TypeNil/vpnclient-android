package dev.typenil.vpnclient

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.typenil.vpnclient.core.subscription.ImportUrlExtractor
import dev.typenil.vpnclient.core.subscription.ImportUrlExtractor.ExtractedImport
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.data.settings.SettingsRepository
import dev.typenil.vpnclient.ui.VpnApp
import javax.inject.Inject
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var connectionManager: ConnectionManager

    @Inject
    lateinit var settings: SettingsRepository
    private val _importUrl = MutableStateFlow<ExtractedImport?>(null)
    val importUrl: StateFlow<ExtractedImport?> get() = _importUrl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VpnApp(
                connectionManager = connectionManager,
                importUrl = importUrl,
                onImportConsumed = { _importUrl.value = null },
            )
        }
        // Only on a fresh launch — the launch intent survives recreation, so
        // a process-death/config-change restore must not re-fire it.
        if (savedInstanceState == null) {
            maybeAutoConnect(intent)
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: a launcher reopen lands here, not in onCreate — the
        // same auto-connect check applies to MAIN intents only.
        maybeAutoConnect(intent)
        handleIntent(intent)
    }

    private fun maybeAutoConnect(intent: Intent?) {
        if (intent?.action != Intent.ACTION_MAIN) return
        // Auto-connect: consent flows through the existing
        // prepareIntent/PermissionRequired path — no silent start.
        lifecycleScope.launch {
            if (settings.autoConnectOnLaunch.first() &&
                connectionManager.state.value is VpnConnectionState.Idle
            ) {
                connectionManager.connect()
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // No connect extras here — this activity is exported, and honoring a
        // caller-supplied "connect" flag would let any installed app toggle
        // the VPN. The QS tile calls ConnectionManager directly instead.
        ImportUrlExtractor.extract(
            action = intent.action,
            data = intent.dataString,
            extraText = intent.getStringExtra(Intent.EXTRA_TEXT),
        )?.let {
            _importUrl.value = it
            // Consume: the same intent object is re-delivered to every future
            // onCreate — without this the import dialog re-opens on rotate.
            intent.action = Intent.ACTION_MAIN
            intent.data = null
            intent.removeExtra(Intent.EXTRA_TEXT)
        }
    }
}
