package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.RefreshPolicy
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
    val hwidConsent: Flow<HwidConsent>
    suspend fun setHwidConsent(consent: HwidConsent)
    /** Atomic consent check; never creates an id unless Allowed. */
    suspend fun getOrCreateHwid(): String?
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
     * resolved interval is null. [policy] is the subscription's own refresh
     * policy — only [RefreshPolicy.InheritGlobal] consults the app-wide
     * [userOverrideMinutes] (`-1` off / `0` provider / `>0` fixed); explicit
     * Provider/Fixed ignore it, Disabled never schedules. Resolved intervals
     * are floored at the platform minimum.
     */
    suspend fun schedule(
        subscriptionId: Long,
        providerMinutes: Int?,
        policy: RefreshPolicy,
        userOverrideMinutes: Int,
        enabled: Boolean,
    )

    fun cancel(subscriptionId: Long)

    /**
     * Remove scheduled work whose subscription no longer exists — covers
     * rows wiped by destructive migration or a remove() that raced a
     * reconcile pass. [exists] is answered per candidate work item against
     * the CURRENT row at prune time: the caller's earlier list read is a
     * stale snapshot, so a row added since then must not be pruned. Query
     * and cancellation failures propagate — a failed pass must surface as
     * incomplete, not as "no orphans".
     */
    suspend fun reconcile(exists: suspend (Long) -> Boolean) = Unit
}

/** Platform floor for periodic work — 15 minutes. */
internal const val MIN_INTERVAL_MINUTES = 15L

/**
 * Resolve the effective refresh interval from the per-subscription [policy].
 * `null` means "no scheduled job"; every result is floored at the platform
 * minimum. Pure — JVM-tested.
 *
 * - [RefreshPolicy.InheritGlobal] keeps the legacy app-wide int semantics:
 *   `-1` off, `0` follow the provider hint, `>0` fixed user override.
 * - [RefreshPolicy.Provider] uses ONLY the provider hint — no usable hint
 *   means manual-only, never a fallback to the global value.
 * - [RefreshPolicy.Fixed] uses its own minutes; both it and Provider are
 *   unaffected by the global value (including "off").
 * - [RefreshPolicy.Disabled] and `enabled = false` never schedule.
 *
 * The scheduler enqueues exactly what this returns; the repository also
 * re-runs it as the periodic run's eligibility gate, so a scheduled slot
 * and an already-awake worker agree on "should this subscription fetch".
 */
internal fun resolveRefreshIntervalMinutes(
    policy: RefreshPolicy,
    userOverrideMinutes: Int,
    providerMinutes: Int?,
    enabled: Boolean,
): Long? {
    if (!enabled) return null
    // Only a positive hint counts — the fetcher stores positive values only,
    // and anything else would floor to the minimum as a phantom schedule.
    val provider = providerMinutes?.takeIf { it > 0 }?.toLong()
    return when (policy) {
        RefreshPolicy.Disabled -> null
        is RefreshPolicy.Fixed ->
            maxOf(policy.minutes.toLong(), MIN_INTERVAL_MINUTES)
        RefreshPolicy.Provider ->
            provider?.let { maxOf(it, MIN_INTERVAL_MINUTES) }
        RefreshPolicy.InheritGlobal ->
            when {
                userOverrideMinutes < 0 -> null
                userOverrideMinutes > 0 ->
                    maxOf(userOverrideMinutes.toLong(), MIN_INTERVAL_MINUTES)
                else -> provider?.let { maxOf(it, MIN_INTERVAL_MINUTES) }
            }
    }
}
