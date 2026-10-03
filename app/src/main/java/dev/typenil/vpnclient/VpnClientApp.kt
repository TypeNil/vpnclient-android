package dev.typenil.vpnclient

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp
import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.engine.singbox.LibboxRuntime
import dev.typenil.vpnclient.core.engine.singbox.RuleSetStore
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.core.vpn.VpnNotification
import dev.typenil.vpnclient.data.db.SubscriptionDao
import dev.typenil.vpnclient.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltAndroidApp
class VpnClientApp : Application() {

    @Inject
    lateinit var applicationScope: CoroutineScope

    @Inject
    lateinit var subscriptionDao: SubscriptionDao

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var subscriptionRepository: SubscriptionRepository

    @Inject
    lateinit var connectionManager: ConnectionManager

    @Inject
    lateinit var ruleSetStore: RuleSetStore

    override fun onCreate() {
        super.onCreate()
        SecureLog.debugEnabled = BuildConfig.DEBUG
        createNotificationChannels()
        LibboxRuntime.init(this)
        reconcileRefreshJobs()
        // Expiry scan is DB-only but serializes behind refresh() inside the
        // repository — launch it before the update-always network refreshes
        // so N x fetch-timeout can't delay a due alert.
        checkSubscriptionExpiry()
        refreshUpdateAlwaysSubscriptions()
        refreshRuleSetsWhileConnected()
    }

    /**
     * Rule sets older than a day are refreshed once a tunnel is up — never on
     * the connect path. Failures are logged by the store and the working copy
     * is kept; the new file is used by the next connect.
     */
    private fun refreshRuleSetsWhileConnected() {
        applicationScope.launch {
            connectionManager.state
                .map { it is VpnConnectionState.Connected }
                .distinctUntilChanged()
                .filter { it }
                .collect { ruleSetStore.refreshStale(settings.routeMode.first()) }
        }
    }

    /**
     * Reconcile scheduled refresh work with persisted subscriptions — covers
     * jobs lost to a reinstall/restore, and re-resolves them whenever the
     * global override changes (DataStore emits on startup too).
     *
     * The startup pass touches every row; later passes mean the global value
     * changed, which only inheriting rows can observe — explicit per-sub
     * policies don't reference it, so they're left alone. The repository does
     * the reconcile: every row is re-read under its per-subscription lock, so
     * an entity snapshot can never schedule over a newer override.
     *
     * The full-pass flag is retained until a pass reports complete — a
     * partial reconcile (a row that failed to reschedule, a failed orphan
     * prune) means the NEXT emission retries a full pass instead of
     * switching to inheriting-only on top of missing jobs. No timer retry
     * is invented: the DataStore flow re-emits on the next settings change.
     */
    private fun reconcileRefreshJobs() {
        applicationScope.launch {
            // distinctUntilChanged: unrelated DataStore writes re-emit the
            // flow — without it every settings write re-enqueues all jobs.
            var needsFullPass = true
            settings.autoRefreshMinutes
                .distinctUntilChanged()
                .collect {
                    try {
                        if (subscriptionRepository.reconcileRefreshSchedules(
                                inheritingOnly = !needsFullPass,
                            )
                        ) {
                            needsFullPass = false
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A failure must not kill the reconcile loop for the
                        // rest of the process lifetime.
                        SecureLog.w("VpnClientApp", "refresh reconcile failed", e)
                    }
                }
        }
    }

    /**
     * `update-always` subscriptions are refreshed on every process start —
     * the provider asked for fresh data on launch. Sequential and
     * mutex-serialized inside refresh(); failures are logged, not fatal.
     */
    private fun refreshUpdateAlwaysSubscriptions() {
        applicationScope.launch {
            // The filter is a cheap pre-pass only — refreshOnLaunch re-checks
            // eligibility on the CURRENT row at fetch entry, so a subscription
            // disabled after this snapshot can never fetch.
            subscriptionDao.getAll()
                .filter { it.updateAlways }
                .forEach { sub ->
                    runCatching { subscriptionRepository.refreshOnLaunch(sub.id) }
                        .onFailure {
                            SecureLog.w("VpnClientApp", "update-always refresh failed sub=${sub.id}")
                        }
                }
        }
    }

    /**
     * The refresh path only evaluates expiry after a successful fetch — a
     * manual-only or long-interval subscription would never alert. Re-check
     * persisted expiry on every process start so the 3-day window still
     * fires without a refresh. (A scheduled boundary worker is future work.)
     */
    private fun checkSubscriptionExpiry() {
        applicationScope.launch {
            // Serialized with refresh() inside the repository — the
            // check→notify→mark sequence can't race a startup refresh.
            runCatching { subscriptionRepository.checkPersistedExpiryAlerts() }
                .onFailure {
                    SecureLog.w("VpnClientApp", "expiry check failed", it)
                }
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                VpnNotification.CHANNEL_ID,
                getString(R.string.notification_channel_vpn),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                VpnNotification.ALERT_CHANNEL_ID,
                getString(R.string.notification_channel_vpn_alerts),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }
}
