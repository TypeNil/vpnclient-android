package dev.typenil.vpnclient.data

import dev.typenil.vpnclient.core.engine.RoutingRule
import dev.typenil.vpnclient.core.engine.singbox.ConfigCompiler
import dev.typenil.vpnclient.core.engine.singbox.RuleSetStore
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Engine pre-check for a candidate user rule set: compiles the current
 * usable node config with [candidate] in place of the stored rules and runs
 * native `checkConfig` — the same validation the connect path performs, so
 * a bad rule surfaces at add time instead of at the next connect.
 *
 * Engine isolation: this lives in the data layer like
 * [CandidateValidatorImpl]; the UI never touches libbox.
 */
@Singleton
class RoutingRuleSetValidator
    @Inject
    constructor(
        private val compiler: ConfigCompiler,
        private val settings: SettingsRepository,
        private val ruleSetStore: RuleSetStore,
        private val nodeDao: NodeDao,
    ) {
        /**
         * True when [candidate] compiles into the current config. With no
         * usable nodes (no subscriptions) there is nothing to compile —
         * pattern validation is the gate, so this succeeds without an
         * engine round-trip.
         *
         * Failure detail is deliberately not exposed: checkConfig/compile
         * messages can embed config data, and the caller only needs the
         * boolean. Runs on IO — `ensureReady` may fetch rule-set files on
         * first use, and the compile itself is CPU/native work.
         */
        suspend fun validateRules(candidate: List<RoutingRule>): Boolean =
            withContext(Dispatchers.IO) {
                val nodes = nodeDao.getUsable().map { it.toDomain() }
                if (nodes.isEmpty()) return@withContext true
                try {
                    val routeMode = settings.routeMode.first()
                    compiler.compile(
                        nodes = nodes,
                        selectedNodeId = null,
                        ipv6Enabled = true,
                        routeMode = routeMode,
                        ruleSetPaths = ruleSetStore.ensureReady(routeMode),
                        userRules = candidate,
                    )
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
    }
