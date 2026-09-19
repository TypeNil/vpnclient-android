package dev.typenil.vpnclient.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.typenil.vpnclient.core.subscription.SubscriptionSettings
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** App preferences (DataStore). */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SubscriptionSettings {

    private object Keys {
        val SELECTED_NODE_ID = stringPreferencesKey("selected_node_id")
        val HWID = stringPreferencesKey("remnawave_hwid")
        val RECONNECT_ON_NETWORK_CHANGE = booleanPreferencesKey("reconnect_on_network_change")
        val IPV6_ENABLED = booleanPreferencesKey("ipv6_enabled")
        /** "User wants the tunnel up" — survives process death; drives
         *  START_STICKY rebuilds. Cleared on every intentional/failed teardown. */
        val DESIRED_VPN_RUNNING = booleanPreferencesKey("desired_vpn_running")
        /** -1 = manual only, 0 = provider interval (default), >0 = user override. */
        val SUBSCRIPTION_REFRESH_MINUTES = intPreferencesKey("subscription_refresh_minutes")
    }

    override val selectedNodeId: Flow<String?> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.SELECTED_NODE_ID] }

    override suspend fun setSelectedNodeId(id: String?) {
        context.settingsStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.SELECTED_NODE_ID) else prefs[Keys.SELECTED_NODE_ID] = id
        }
    }

    /** Stable per-install device id for Remnawave HWID (never a hardware id). */
    val hwid: Flow<String?> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.HWID] }

    override suspend fun getOrCreateHwid(): String {
        var existing: String? = null
        context.settingsStore.edit { prefs ->
            existing = when (val stored = prefs[Keys.HWID]) {
                null -> java.util.UUID.randomUUID().toString().also { prefs[Keys.HWID] = it }
                // Legacy installs stored an undashed 32-hex id; panels that validate
                // HWID format reject it — rewrite to dashed UUID spelling (same id).
                else -> normalizeHwid(stored)?.also { prefs[Keys.HWID] = it } ?: stored
            }
        }
        return existing!!
    }

    val reconnectOnNetworkChange: Flow<Boolean> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.RECONNECT_ON_NETWORK_CHANGE] ?: true }

    suspend fun setReconnectOnNetworkChange(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.RECONNECT_ON_NETWORK_CHANGE] = enabled }
    }

    val ipv6Enabled: Flow<Boolean> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.IPV6_ENABLED] ?: true }

    suspend fun setIpv6Enabled(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.IPV6_ENABLED] = enabled }
    }

    /** Persisted "user wants the tunnel running" flag for service restarts. */
    val desiredVpnRunning: Flow<Boolean> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.DESIRED_VPN_RUNNING] ?: false }

    suspend fun setDesiredVpnRunning(running: Boolean) {
        context.settingsStore.edit { it[Keys.DESIRED_VPN_RUNNING] = running }
    }

    /** See [SubscriptionSettings.autoRefreshMinutes] for the encoding. */
    override val autoRefreshMinutes: Flow<Int> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.SUBSCRIPTION_REFRESH_MINUTES] ?: 0 }

    suspend fun setAutoRefreshMinutes(minutes: Int) {
        context.settingsStore.edit {
            it[Keys.SUBSCRIPTION_REFRESH_MINUTES] = minutes.coerceAtLeast(-1)
        }
    }

    internal companion object {
        private val HEX32 = Regex("[0-9a-fA-F]{32}")

        /** Reformats an undashed 32-hex HWID to dashed UUID form; null if already fine. */
        fun normalizeHwid(stored: String): String? {
            if (!HEX32.matches(stored)) return null
            return "${stored.substring(0, 8)}-${stored.substring(8, 12)}-" +
                "${stored.substring(12, 16)}-${stored.substring(16, 20)}-${stored.substring(20)}"
        }
    }
}
