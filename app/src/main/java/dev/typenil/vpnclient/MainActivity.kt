package dev.typenil.vpnclient

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.typenil.vpnclient.core.common.AppLanguage
import dev.typenil.vpnclient.core.common.LocaleSupport
import dev.typenil.vpnclient.core.subscription.ImportUrlExtractor
import dev.typenil.vpnclient.core.subscription.ImportUrlExtractor.ExtractedImport
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.data.settings.SettingsRepository
import dev.typenil.vpnclient.ui.VpnApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Below API 33 there is no system per-app locale — wrap the base context
     *  so resources resolve in the language the user picked in Settings. The
     *  synchronous SharedPreferences mirror exists because DataStore can't be
     *  read this early. On 33+ the platform applied the per-app locale already. */
    override fun attachBaseContext(newBase: Context) {
        val tag =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                LocaleSupport.storedTag(newBase)
            } else {
                null
            }
        super.attachBaseContext(LocaleSupport.wrap(newBase, tag))
    }

    /** Mirrors the DataStore language choice into the mechanism the platform
     *  layer consumes: LocaleManager on 33+ (system-driven recreation), the
     *  compat tag + explicit recreate below. Skips the first emission when it
     *  matches what the context already applies — otherwise process start
     *  would recreate the just-created activity. */
    private fun syncLanguage(language: AppLanguage) {
        val tag = language.tag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleSupport.applyToSystem(this, tag)
        } else {
            val previous = LocaleSupport.storedTag(this)
            if (previous != tag) {
                LocaleSupport.storeTag(this, tag)
                // Configuration change alone doesn't rebuild the composition
                // reliably across all API levels — recreate is the clean path.
                recreate()
            }
        }
    }

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
                settings = settings,
                importUrl = importUrl,
                onImportConsumed = { _importUrl.value = null },
            )
        }
        // Keep the platform locale store and the pre-33 wrap tag in sync with
        // the user's persisted choice. `distinctUntilChanged` guards against
        // DataStore re-emissions; on 33+ applyToSystem no-ops on equality.
        lifecycleScope.launch {
            var first = true
            settings.appLanguage.collect { language ->
                if (first) {
                    first = false
                    val appliedTag =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            LocaleSupport.currentSystemTag(this@MainActivity)
                        } else {
                            LocaleSupport.storedTag(this@MainActivity)
                        }
                    if (appliedTag == language.tag) return@collect
                }
                syncLanguage(language)
            }
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
        ImportUrlExtractor
            .extract(
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
