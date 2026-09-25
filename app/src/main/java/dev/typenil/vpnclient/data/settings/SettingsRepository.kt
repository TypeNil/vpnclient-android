package dev.typenil.vpnclient.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
import kotlinx.coroutines.flow.onStart

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
        /** Legacy ordinal storage — read once by the v2 migration, then removed. */
        val PER_APP_MODE = intPreferencesKey("per_app_mode")
        /** PerAppMode.key — "all"/"include"/"exclude", never ordinal. */
        val PER_APP_MODE_V2 = stringPreferencesKey("per_app_mode_v2")
        val PER_APP_PACKAGES = stringSetPreferencesKey("per_app_packages")
        /** RouteMode.key — "all"/"bypass_ru"/"proxy_blocked", never ordinal. */
        val ROUTE_MODE = stringPreferencesKey("route_mode")
        /** Opt-in: pause the core in Doze (drops open TCP connections). */
        val DOZE_POWER_SAVE = booleanPreferencesKey("doze_power_save")
        /** Crash-loop guard: start of the current counting window (epoch ms). */
        val VPN_RESTART_WINDOW_START = longPreferencesKey("vpn_restart_window_start")
        /** Automatic tunnel starts counted inside the current window. */
        val VPN_RESTART_COUNT = intPreferencesKey("vpn_restart_count")
        /** Set when the restart guard disabled auto-start — persisted so the
         *  UI can warn even when notifications are denied. */
        val VPN_RESTART_GUARD_TRIPPED = booleanPreferencesKey("vpn_restart_guard_tripped")
        /** Expiry alerts already posted — `"$subscriptionId:$expireEpochSeconds"`. */
        val EXPIRY_ALERTED = stringSetPreferencesKey("expiry_alerted")
        /** Connect the tunnel automatically when the app is opened. */
        val AUTO_CONNECT_ON_LAUNCH = booleanPreferencesKey("auto_connect_on_launch")
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

    /**
     * Idempotent ordinal→string migration for `per_app_mode`. Runs inside a
     * single edit on the first read: when the v2 key is absent but the legacy
     * ordinal exists, the ordinal is rewritten as its [PerAppMode.key]; the
     * legacy key is always removed. After one pass this is a no-op.
     */
    private suspend fun migratePerAppModeIfNeeded() {
        context.settingsStore.edit { prefs ->
            val target = migratedPerAppModeKey(
                legacyOrdinal = prefs[Keys.PER_APP_MODE],
                currentKey = prefs[Keys.PER_APP_MODE_V2],
            )
            if (target != null) prefs[Keys.PER_APP_MODE_V2] = target
            // Skip the write entirely when nothing needed migrating — even
            // a no-op edit emits a new Preferences instance downstream.
            if (prefs.contains(Keys.PER_APP_MODE)) prefs.remove(Keys.PER_APP_MODE)
        }
    }

    /** Per-app routing mode — see [dev.typenil.vpnclient.core.vpn.PerAppMode]. */
    val perAppMode: Flow<PerAppMode> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        // runCatching: onStart sits downstream of catch — an IOException
        // from the migration edit would otherwise kill the collector.
        .onStart { runCatching { migratePerAppModeIfNeeded() } }
        .map { PerAppMode.fromKey(it[Keys.PER_APP_MODE_V2]) }

    suspend fun setPerAppMode(mode: PerAppMode) {
        context.settingsStore.edit {
            it[Keys.PER_APP_MODE_V2] = mode.key
            it.remove(Keys.PER_APP_MODE)
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

    /** Mode + package set from a single DataStore snapshot — reading the two
     *  keys separately could tear across a concurrent write. */
    val perAppPolicy: Flow<Pair<PerAppMode, Set<String>>> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .onStart { runCatching { migratePerAppModeIfNeeded() } }
        .map {
            PerAppMode.fromKey(it[Keys.PER_APP_MODE_V2]) to
                (it[Keys.PER_APP_PACKAGES] ?: emptySet())
        }

    suspend fun perAppPolicySnapshot(): Pair<PerAppMode, Set<String>> =
        perAppPolicy.first()

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

    /**
     * Crash-loop guard for automatic (sticky-restart / boot) tunnel starts.
     * A native crash kills the process before any catch block runs, so the
     * only observable symptom is repeated starts with no stable session in
     * between. Returns false once [RESTART_MAX_ATTEMPTS] automatic starts
     * land inside [RESTART_WINDOW_MS] — the caller must clear
     * `desiredVpnRunning` and stop instead of looping forever.
     */
    suspend fun registerVpnRestartAttempt(now: Long = System.currentTimeMillis()): Boolean {
        var allowed = true
        context.settingsStore.edit { prefs ->
            val (windowStart, count) = restartWindow(
                prevStart = prefs[Keys.VPN_RESTART_WINDOW_START] ?: 0L,
                prevCount = prefs[Keys.VPN_RESTART_COUNT] ?: 0,
                now = now,
            )
            allowed = count <= RESTART_MAX_ATTEMPTS
            if (allowed) {
                prefs[Keys.VPN_RESTART_WINDOW_START] = windowStart
                prefs[Keys.VPN_RESTART_COUNT] = count
            }
        }
        return allowed
    }

    /** Whether the restart guard disabled auto-start — the Home warning
     *  surfaces this; it survives notification-permission denial. */
    val restartGuardTripped: Flow<Boolean> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.VPN_RESTART_GUARD_TRIPPED] ?: false }

    suspend fun setRestartGuardTripped(tripped: Boolean) {
        context.settingsStore.edit { it[Keys.VPN_RESTART_GUARD_TRIPPED] = tripped }
    }

    /** Reset after a session stayed Connected long enough — LMK kills of a
     *  healthy tunnel must not accumulate toward the guard limit. Also reset
     *  on explicit user connects (the tap overrides a tripped guard). */
    suspend fun resetVpnRestartAttempts() {
        context.settingsStore.edit { prefs ->
            prefs.remove(Keys.VPN_RESTART_WINDOW_START)
            prefs.remove(Keys.VPN_RESTART_COUNT)
            prefs.remove(Keys.VPN_RESTART_GUARD_TRIPPED)
        }
    }

    override val expiryAlerted: Flow<Set<String>> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.EXPIRY_ALERTED] ?: emptySet() }

    override suspend fun markExpiryAlerted(key: String) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.EXPIRY_ALERTED] = (prefs[Keys.EXPIRY_ALERTED] ?: emptySet()) + key
        }
    }

    /** Start the VPN automatically when the app opens (consent still applies). */
    val autoConnectOnLaunch: Flow<Boolean> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.AUTO_CONNECT_ON_LAUNCH] ?: false }

    suspend fun setAutoConnectOnLaunch(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.AUTO_CONNECT_ON_LAUNCH] = enabled }
    }

    internal companion object {
        /** Automatic starts allowed inside one window before giving up. */
        const val RESTART_MAX_ATTEMPTS = 3
        const val RESTART_WINDOW_MS = 10 * 60 * 1000L

        private val HEX32 = Regex("[0-9a-fA-F]{32}")

        /** Reformats an undashed 32-hex HWID to dashed UUID form; null if already fine. */
        fun normalizeHwid(stored: String): String? {
            if (!HEX32.matches(stored)) return null
            return "${stored.substring(0, 8)}-${stored.substring(8, 12)}-" +
                "${stored.substring(12, 16)}-${stored.substring(16, 20)}-${stored.substring(20)}"
        }

        /**
         * Restart-window decision: the (windowStart, count) to store for this
         * attempt. A stale window starts a fresh one anchored at [now];
         * callers compare count against [RESTART_MAX_ATTEMPTS].
         */
        fun restartWindow(prevStart: Long, prevCount: Int, now: Long): Pair<Long, Int> =
            if (now - prevStart > RESTART_WINDOW_MS) {
                now to 1
            } else {
                prevStart to prevCount + 1
            }

        /**
         * Per-app-mode migration decision: the v2 key to write, or null when
         * nothing needs migrating. Only maps a legacy ordinal onto an absent
         * v2 value — a stored v2 key always wins, so the migration can never
         * overwrite a newer write.
         */
        fun migratedPerAppModeKey(legacyOrdinal: Int?, currentKey: String?): String? =
            if (legacyOrdinal != null && currentKey == null) {
                PerAppMode.fromOrdinal(legacyOrdinal).key
            } else {
                null
            }
    }
}
