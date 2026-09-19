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
}
