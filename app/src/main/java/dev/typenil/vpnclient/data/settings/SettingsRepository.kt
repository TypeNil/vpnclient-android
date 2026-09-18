package dev.typenil.vpnclient.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
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
) {

    private object Keys {
        val SELECTED_NODE_ID = stringPreferencesKey("selected_node_id")
        val HWID = stringPreferencesKey("remnawave_hwid")
        val RECONNECT_ON_NETWORK_CHANGE = booleanPreferencesKey("reconnect_on_network_change")
        val IPV6_ENABLED = booleanPreferencesKey("ipv6_enabled")
    }

    val selectedNodeId: Flow<String?> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.SELECTED_NODE_ID] }

    suspend fun setSelectedNodeId(id: String?) {
        context.settingsStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.SELECTED_NODE_ID) else prefs[Keys.SELECTED_NODE_ID] = id
        }
    }

    /** Stable per-install device id for Remnawave HWID (never a hardware id). */
    val hwid: Flow<String?> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.HWID] }

    suspend fun getOrCreateHwid(): String {
        var existing: String? = null
        context.settingsStore.edit { prefs ->
            existing = prefs[Keys.HWID]
            if (existing == null) {
                // 32 hex chars — inside Remnawave's /^[a-zA-Z0-9=-]{10,64}$/ rule.
                val generated = java.util.UUID.randomUUID().toString().replace("-", "")
                prefs[Keys.HWID] = generated
                existing = generated
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
}
