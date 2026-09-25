package dev.typenil.vpnclient.core.vpn

import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both start paths — the service's process-death restore and the in-session
 * rebuild — branch on this classification. Collapsing "nothing enabled" into
 * "compile failed" (or both into a null config, as the restore path used to)
 * is what made a refused configuration read as "no server selected".
 */
class CompileOutcomeTest {
    private class Provider(
        private val result: () -> EngineConfig?,
    ) : NodeConfigProvider {
        override suspend fun compileSelected(): EngineConfig? = result()

        override val selectedNodeId: Flow<String?> = MutableStateFlow(null)

        override suspend fun nodeSummary(id: String): NodeSummary? = null

        override val enabledNodeSetFingerprint: Flow<String> = MutableStateFlow("fingerprint")

        override val compiledNodeSetFingerprint: StateFlow<String?> = MutableStateFlow(null)
    }

    private val config =
        EngineConfig(
            configJson = "{}",
            node = NodeSummary("n1", "Node", ProtocolType.VLESS, "a.example.com"),
        )

    @Test
    fun `a compiled config is ready`() =
        runTest {
            assertEquals(CompileOutcome.Ready(config), Provider { config }.compileOutcome())
        }

    @Test
    fun `no enabled nodes is a state, not a failure`() =
        runTest {
            assertEquals(CompileOutcome.NoNodes, Provider { null }.compileOutcome())
        }

    @Test
    fun `an engine rejection keeps its own cause`() =
        runTest {
            val rejection = EngineError.InvalidConfig("bad outbound")

            val outcome = Provider { throw rejection }.compileOutcome()

            assertEquals(CompileOutcome.Failed(rejection), outcome)
        }

    @Test
    fun `a non-engine failure keeps its own cause`() =
        runTest {
            // e.g. RuleSetStore's "missing rule set files".
            val failure = IllegalStateException("missing rule set files")

            assertEquals(CompileOutcome.Failed(failure), Provider { throw failure }.compileOutcome())
        }

    @Test
    fun `cancellation propagates instead of becoming a failure`() =
        runTest {
            var cancelled = false
            try {
                Provider { throw CancellationException("cancelled") }.compileOutcome()
            } catch (e: CancellationException) {
                cancelled = true
            }

            assertTrue(cancelled)
        }
}
