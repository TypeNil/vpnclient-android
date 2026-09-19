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

    companion object {
        /** QS tile asks the activity to start a connect attempt — the UI owns
         *  the consent-dialog flow, so the tile delegates here. */
        const val EXTRA_CONNECT = "dev.typenil.vpnclient.extra.CONNECT"
    }

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
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_CONNECT, false)) {
            intent.removeExtra(EXTRA_CONNECT)
            connectionManager.connect()
            return
        }
        ImportUrlExtractor.extract(
            action = intent.action,
            data = intent.dataString,
            extraText = intent.getStringExtra(Intent.EXTRA_TEXT),
        )?.let { _importUrl.value = it }
    }
}
