package dev.typenil.vpnclient.data

import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.singbox.ConfigCompiler
import dev.typenil.vpnclient.core.engine.singbox.RuleSetStore
import dev.typenil.vpnclient.core.subscription.SubscriptionCandidateValidator
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Compiles the full candidate config (every node outbound) and runs native
 * `checkConfig` — the same validation the connect path performs. A refresh
 * must never commit nodes the engine cannot run.
 */
@Singleton
class CandidateValidatorImpl @Inject constructor(
    private val compiler: ConfigCompiler,
    private val settings: SettingsRepository,
    private val ruleSetStore: RuleSetStore,
) : SubscriptionCandidateValidator {

    override suspend fun validate(nodes: List<ProxyNode>) {
        try {
            // Validate the config the user would actually run — the active
            // route mode with its local rule sets, not a default ALL.
            val routeMode = settings.routeMode.first()
            compiler.compile(
                nodes = nodes,
                selectedNodeId = null,
                ipv6Enabled = true,
                routeMode = routeMode,
                ruleSetPaths = ruleSetStore.ensureReady(routeMode),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineError) {
            throw e
        } catch (e: Exception) {
            // checkConfig/serialization messages can embed node data — the
            // typed error carries a fixed message only.
            throw EngineError.InvalidConfig("candidate config rejected")
        }
    }
}
