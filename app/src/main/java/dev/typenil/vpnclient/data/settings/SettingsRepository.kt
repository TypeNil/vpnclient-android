package dev.typenil.vpnclient.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.subscription.SubscriptionSettings
import dev.typenil.vpnclient.core.vpn.PerAppMode
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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
        /** Last user-entered override — survives toggling auto-refresh off/on. */
        val AUTO_REFRESH_OVERRIDE = intPreferencesKey("auto_refresh_override_minutes")
        /** PerAppMode.ordinal: 0 = all, 1 = include selected, 2 = exclude selected. */
        val PER_APP_MODE = intPreferencesKey("per_app_mode")
        val PER_APP_PACKAGES = stringSetPreferencesKey("per_app_packages")
        /** RouteMode.key — "all"/"bypass_ru"/"proxy_blocked", never ordinal. */
        val ROUTE_MODE = stringPreferencesKey("route_mode")
        /** Opt-in: pause the core in Doze (drops open TCP connections). */
        val DOZE_POWER_SAVE = booleanPreferencesKey("doze_power_save")
    }

    override val selectedNodeId: Flow<String?> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.SELECTED_NODE_ID] }

    override suspend fun setSelectedNodeId(id: String?) {
        context.settingsStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.SELECTED_NODE_ID) else prefs[Keys.SELECTED_NODE_ID] = id
        }
    }

    override suspend fun clearSelectedNodeIdIf(expected: String) {
        context.settingsStore.edit { prefs ->
            if (prefs[Keys.SELECTED_NODE_ID] == expected) {
                prefs.remove(Keys.SELECTED_NODE_ID)
            }
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
            val value = minutes.coerceAtLeast(0)
            it[Keys.SUBSCRIPTION_REFRESH_MINUTES] = value
            // Keep the override so toggling auto-refresh off/on restores it.
            it[Keys.AUTO_REFRESH_OVERRIDE] = value
        }
    }

    /** Enable follows the remembered override; disable preserves it. */
    suspend fun setAutoRefreshEnabled(enabled: Boolean) {
        context.settingsStore.edit {
            it[Keys.SUBSCRIPTION_REFRESH_MINUTES] =
                if (enabled) it[Keys.AUTO_REFRESH_OVERRIDE] ?: 0 else -1
        }
    }

    /** Per-app routing mode — see [dev.typenil.vpnclient.core.vpn.PerAppMode]. */
    val perAppMode: Flow<Int> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.PER_APP_MODE] ?: 0 }

    suspend fun setPerAppMode(mode: Int) {
        context.settingsStore.edit {
            it[Keys.PER_APP_MODE] = mode.coerceIn(0, PerAppMode.entries.lastIndex)
        }
    }

    /** Packages the include/exclude list applies to. */
    val perAppPackages: Flow<Set<String>> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.PER_APP_PACKAGES] ?: emptySet() }

    suspend fun setPerAppPackages(packages: Set<String>) {
        context.settingsStore.edit { it[Keys.PER_APP_PACKAGES] = packages }
    }

    /** Atomic toggle — DataStore serializes edits, so rapid taps can't
     *  overwrite each other's read-modify-write. */
    suspend fun togglePerAppPackage(packageName: String) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.PER_APP_PACKAGES] ?: emptySet()
            prefs[Keys.PER_APP_PACKAGES] =
                if (packageName in current) current - packageName else current + packageName
        }
    }

    /** Both per-app keys from a single DataStore snapshot — reading the two
     *  flows separately could tear across a concurrent write. */
    suspend fun perAppPolicySnapshot(): Pair<PerAppMode, Set<String>> {
        val prefs = context.settingsStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .first()
        return PerAppMode.fromOrdinal(prefs[Keys.PER_APP_MODE] ?: 0) to
            (prefs[Keys.PER_APP_PACKAGES] ?: emptySet())
    }

    /** Region/domain routing — see [RouteMode]. Applies on the next connect. */
    val routeMode: Flow<RouteMode> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { RouteMode.fromKey(it[Keys.ROUTE_MODE]) }

    suspend fun setRouteMode(mode: RouteMode) {
        context.settingsStore.edit { it[Keys.ROUTE_MODE] = mode.key }
    }

    /** Pause the engine in Doze — saves battery but drops open connections. */
    val dozePowerSave: Flow<Boolean> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.DOZE_POWER_SAVE] ?: false }

    suspend fun setDozePowerSave(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.DOZE_POWER_SAVE] = enabled }
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
