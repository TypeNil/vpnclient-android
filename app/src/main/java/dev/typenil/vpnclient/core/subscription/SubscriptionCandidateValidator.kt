package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import kotlinx.coroutines.flow.Flow

/**
 * Validates a candidate node set against the engine without starting it.
 * Implemented in the engine-aware layer (`ConfigCompiler` + native
 * `checkConfig`); the subscription layer only sees the contract.
 */
interface SubscriptionCandidateValidator {
    /** Throws when the candidate node set cannot produce a runnable config. */
    suspend fun validate(nodes: List<ProxyNode>)
}

/**
 * The slice of app settings the subscription pipeline needs. Implemented by
 * `SettingsRepository`; kept narrow so the repository is JVM-testable.
 */
interface SubscriptionSettings {
    suspend fun getOrCreateHwid(): String
    val selectedNodeId: Flow<String?>
    suspend fun setSelectedNodeId(id: String?)
    /**
     * Auto-refresh override in minutes: `-1` = manual only, `0` = follow the
     * provider's `profile-update-interval`, `>0` = fixed user interval.
     */
    val autoRefreshMinutes: Flow<Int>
}

/**
 * Schedules per-subscription background refresh work. Implemented with
 * WorkManager in `data.work`; the repository only sees this contract.
 */
interface SubscriptionRefreshScheduler {
    /**
     * Enqueue/update the unique periodic refresh job, or cancel it when the
     * resolved interval is null (manual-only, disabled, or auto-refresh off).
     * Interval precedence: user override > provider hint > manual only;
     * floored at the platform minimum.
     */
    suspend fun schedule(
        subscriptionId: Long,
        providerMinutes: Int?,
        userOverrideMinutes: Int,
        enabled: Boolean,
    )

    fun cancel(subscriptionId: Long)
}
