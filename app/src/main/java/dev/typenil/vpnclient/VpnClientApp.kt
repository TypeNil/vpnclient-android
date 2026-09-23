package dev.typenil.vpnclient

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp
import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.engine.singbox.LibboxRuntime
import dev.typenil.vpnclient.core.subscription.SubscriptionExpiryNotifier
import dev.typenil.vpnclient.core.subscription.SubscriptionRefreshScheduler
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.subscription.expiryAlertDue
import dev.typenil.vpnclient.core.subscription.model.SubscriptionUserInfo
import dev.typenil.vpnclient.core.vpn.VpnNotification
import dev.typenil.vpnclient.data.db.SubscriptionDao
import dev.typenil.vpnclient.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@HiltAndroidApp
class VpnClientApp : Application() {

    @Inject
    lateinit var applicationScope: CoroutineScope

    @Inject
    lateinit var subscriptionDao: SubscriptionDao

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var refreshScheduler: SubscriptionRefreshScheduler

    @Inject
    lateinit var subscriptionRepository: SubscriptionRepository

    @Inject
    lateinit var expiryNotifier: SubscriptionExpiryNotifier

    private val json = Json { ignoreUnknownKeys = true }
    override fun onCreate() {
        super.onCreate()
        SecureLog.debugEnabled = BuildConfig.DEBUG
        createNotificationChannels()
        LibboxRuntime.init(this)
        reconcileRefreshJobs()
        refreshUpdateAlwaysSubscriptions()
        checkSubscriptionExpiry()
    }

    /**
     * Reconcile scheduled refresh work with persisted subscriptions — covers
     * jobs lost to a reinstall/restore, and re-registers them whenever the
     * user override interval changes (DataStore emits on startup too).
     */
    private fun reconcileRefreshJobs() {
        applicationScope.launch {
            // distinctUntilChanged: unrelated DataStore writes re-emit the
            // flow — without it every settings write re-enqueues all jobs.
            settings.autoRefreshMinutes
                .distinctUntilChanged()
                .collect { userOverride ->
                    // A throw here must not kill the reconcile loop for the
                    // rest of the process lifetime.
                    runCatching {
                        val subs = subscriptionDao.getAll()
                        subs.forEach { sub ->
                            refreshScheduler.schedule(
                                subscriptionId = sub.id,
                                providerMinutes = sub.updateIntervalMinutes,
                                userOverrideMinutes = userOverride,
                                enabled = sub.enabled,
                            )
                        }
                        // Prune jobs whose subscription no longer exists.
                        refreshScheduler.reconcile(subs.map { it.id }.toSet())
                    }.onFailure {
                        SecureLog.w("VpnClientApp", "refresh reconcile failed", it)
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
            subscriptionDao.getAll()
                .filter { it.updateAlways && it.enabled }
                .forEach { sub ->
                    runCatching { subscriptionRepository.refresh(sub.id) }
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
            runCatching {
                subscriptionDao.getAll().forEach { sub ->
                    val info = sub.userInfoJson
                        ?.let { runCatching { json.decodeFromString<SubscriptionUserInfo>(it) }.getOrNull() }
                    val expire = info?.expireEpochSeconds ?: return@forEach
                    val alertKey = "expiry_alerted_${sub.id}_$expire"
                    if (expiryAlertDue(
                            expire,
                            System.currentTimeMillis(),
                            alreadyAlerted = alertKey in settings.expiryAlerted.first(),
                        )
                    ) {
                        if (expiryNotifier.notifyExpiring(sub.id, sub.name, expire)) {
                            settings.markExpiryAlerted(alertKey)
                        }
                    }
                }
            }.onFailure {
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
