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
     * Clear the selection only if it still equals [expected] — the
     * check-and-clear is a single DataStore edit, so a concurrent user
     * selection can't be wiped.
     */
    suspend fun clearSelectedNodeIdIf(expected: String)
    /**
     * Auto-refresh override in minutes: `-1` = manual only, `0` = follow the
     * provider's `profile-update-interval`, `>0` = fixed user interval.
     */
    val autoRefreshMinutes: Flow<Int>
    /** Keys of expiry alerts already posted — `"$subscriptionId:$expireEpochSeconds"`. */
    val expiryAlerted: Flow<Set<String>>
    /** Record an expiry alert as posted (see [expiryAlerted] for the key format). */
    suspend fun markExpiryAlerted(key: String)
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

    /**
     * Remove scheduled work whose subscription no longer exists — covers
     * rows wiped by destructive migration or a remove() that raced a
     * reconcile pass.
     */
    suspend fun reconcile(activeSubscriptionIds: Set<Long>) = Unit
}
