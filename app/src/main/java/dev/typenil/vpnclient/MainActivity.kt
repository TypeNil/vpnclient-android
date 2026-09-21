package dev.typenil.vpnclient

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.typenil.vpnclient.core.subscription.ImportUrlExtractor
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.ui.VpnApp
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var connectionManager: ConnectionManager

    private val _importUrl = MutableStateFlow<String?>(null)
    val importUrl: StateFlow<String?> get() = _importUrl

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
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
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
