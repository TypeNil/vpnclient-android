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

    /** Push the user's choice into the mechanism the platform consumes.
     *  On 33+ the system store is authoritative — the in-app picker writes to
     *  it via [LocaleSupport.applyToSystem], and we deliberately don't push a
     *  stale DataStore value back over a system-level pick (that would undo
     *  a change made in Android Settings). Below 33 our own SharedPreferences
     *  tag is the only store, so it stays the source of truth there. */
    private fun syncLanguage(language: AppLanguage) {
        val tag = language.tag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The Settings screen writes the same system store directly —
            // this call is only a no-op re-assert, not a restore.
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
        // Below 33 our SharedPreferences tag is the only locale store, so
        // the DataStore → shared-pref sync keeps the wrap tag current.
        // On 33+ we don't run this at all: LocaleManager is authoritative
        // and is written directly by the in-app picker (SettingsViewModel),
        // while a system-level pick (Android Settings → Language) must not
        // be clobbered by a stale DataStore value on activity recreation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            lifecycleScope.launch {
                var first = true
                settings.appLanguage.collect { language ->
                    if (first) {
                        first = false
                        if (LocaleSupport.storedTag(this@MainActivity) == language.tag) {
                            return@collect
                        }
                    }
                    syncLanguage(language)
                }
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
